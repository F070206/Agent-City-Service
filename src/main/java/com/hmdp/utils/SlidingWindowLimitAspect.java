package com.hmdp.utils;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.annotation.Resource;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SlidingWindowLimitAspect {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private Environment environment;
    private static final Logger log = LoggerFactory.getLogger(SlidingWindowLimitAspect.class);

    private static final String RATE_LIMIT_PREFIX = "rate_limit";
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = createRateLimitScript();
    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();
    static final ConcurrentMap<String, Expression> EXPRESSION_CACHE = new ConcurrentHashMap<>();

    private static DefaultRedisScript<Long> createRateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("RateLimit.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Around("@annotation(slidingWindowLimit)")
    public Object around(ProceedingJoinPoint joinPoint, SlidingWindowLimit slidingWindowLimit) throws Throwable {
        return enforce(joinPoint, slidingWindowLimit);
    }

    @Around("@within(slidingWindowLimit)")
    public Object aroundClass(ProceedingJoinPoint joinPoint, SlidingWindowLimit slidingWindowLimit) throws Throwable {
        // Microservice public ingress has a shared Gateway limiter; retain method limits for expensive actions.
        if (environment.acceptsProfiles(Profiles.of("micro")) && slidingWindowLimit.globalLimit()) {
            return joinPoint.proceed();
        }
        return enforce(joinPoint, slidingWindowLimit);
    }

    private Object enforce(ProceedingJoinPoint joinPoint, SlidingWindowLimit slidingWindowLimit) throws Throwable {
        //获取方法签名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String methodName = method.getName();
        //判断是否为Http请求
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if(attributes == null){
            log.warn("方法 {} 不在 Web 请求上下文中，跳过限流检查。", methodName);
            return joinPoint.proceed();
        }
        try {
            Object parseSpEL = parseSpEL(joinPoint, signature, slidingWindowLimit.keySpEL());
            String rateLimitKey = buildRateLimitKey(parseSpEL, slidingWindowLimit);
            if(isRateLimited(rateLimitKey, slidingWindowLimit.windowSize(), slidingWindowLimit.timeUnit(), slidingWindowLimit.maxRequests())){
                throw new RateLimitExceededException("Rate limit exceeded");
            }
            return joinPoint.proceed();
        }catch (RateLimitExceededException e){
            log.warn("方法 {} 触发了限流，已拒绝访问。", methodName);
            throw e;
        }catch (Exception e){
            log.error("方法 {} 触发了异常，已拒绝访问。", methodName, e);
            throw e;
        }
    }

    private Object parseSpEL(ProceedingJoinPoint joinPoint,MethodSignature signature, String keySpEL){
        StandardEvaluationContext context = new StandardEvaluationContext();
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            context.setVariable("request", attributes.getRequest());
        }

        Object[] args = joinPoint.getArgs();
        String[] parameterNames = signature.getParameterNames();

        for (int i = 0; i < args.length; i++) {
            if(parameterNames!=null && i< parameterNames.length){
                context.setVariable(parameterNames[i], args[i]);
            }else {
                context.setVariable("arg" + i, args[i]);
            }
        }

        return expressionFor(keySpEL).getValue(context);
    }

    static Expression expressionFor(String keySpEL) {
        return EXPRESSION_CACHE.computeIfAbsent(keySpEL, EXPRESSION_PARSER::parseExpression);
    }

    private String buildRateLimitKey(Object keyValue, SlidingWindowLimit slidingWindowLimit){
        if(keyValue == null){
            throw new IllegalArgumentException("限流参数不能为空");
        }
        
        // 判断是否开启全局限流
        if(slidingWindowLimit.globalLimit()){
            // 全局限流：所有用户共享一个限流桶
            return String.format("%s:global:%s:%s",
                    RATE_LIMIT_PREFIX,
                    slidingWindowLimit.keySpEL(),
                    keyValue);
        } else {
            // 用户级限流：每个用户独立限流
            Long userId = UserHolder.getUser().getId();
            return String.format("%s:%d:%s:%s",
                    RATE_LIMIT_PREFIX,
                    userId,
                    slidingWindowLimit.keySpEL(),
                    keyValue);
        }
    }

    private boolean isRateLimited(String key, int windowSize, TimeUnit timeUnit, int maxRequests) {
        // 1. 计算窗口大小（毫秒）
        long windowMillis = convertToMillis(windowSize, timeUnit);
        // 2. 计算过期时间（秒），兜底为60秒，避免负数
        long expireSeconds = Math.max(calculateExpireSeconds(windowMillis), 60);

        try {
            Long result = stringRedisTemplate.execute(RATE_LIMIT_SCRIPT, Collections.singletonList(key),
                    Long.toString(windowMillis), Integer.toString(maxRequests), Long.toString(expireSeconds));
            if (result == null || (result != 0 && result != 1)) {
                throw new IllegalStateException("Rate limit script returned an invalid decision");
            }
            // 返回true表示触发限流，false表示放行
            return result == 1;
        } catch (Exception e) {
            throw new RateLimitUnavailableException("Rate limit service is unavailable", e);
        }
    }
    private long calculateExpireSeconds(long windowMillis) {
        // 过期时间 = 2 * 窗口大小（秒），向上取整
        double expireSec = (windowMillis * 2.0) / 1000;
        long result = (long) Math.ceil(expireSec);
        return Math.max(1, result); // 至少1秒
    }


    /**
     * 时间单位转换，将时间单位转换为毫秒数
     * @param windowSize 窗口大小
     * @param timeUnit 时间单位
     * @return
     */
    private long convertToMillis(int windowSize, TimeUnit timeUnit){
        return switch (timeUnit){
            case NANOSECONDS, SECONDS, MICROSECONDS, MINUTES, HOURS, DAYS -> timeUnit.toMillis(windowSize);
            case MILLISECONDS -> windowSize;
            default -> throw new IllegalArgumentException("不支持的时间单位: " + timeUnit);
        };
    }
}


