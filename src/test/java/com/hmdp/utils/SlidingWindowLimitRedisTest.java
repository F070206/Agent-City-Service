package com.hmdp.utils;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "RATE_LIMIT_TEST_REDIS_HOST", matches = ".+")
class SlidingWindowLimitRedisTest {
    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class AopConfiguration {
    }

    @SlidingWindowLimit(maxRequests = 1, globalLimit = true)
    static class RateLimitedController {
        public String call() {
            return "ok";
        }
    }

    @Test
    void redisServerClockEnforcesTheSlidingWindow() {
        String remoteAddress = "rate-limit-test-" + UUID.randomUUID();
        String key = "rate_limit:global:#request.remoteAddr:" + remoteAddress;
        LettuceConnectionFactory connection = new LettuceConnectionFactory(
                System.getenv("RATE_LIMIT_TEST_REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("RATE_LIMIT_TEST_REDIS_PORT", "6379")));
        connection.setDatabase(Integer.parseInt(System.getenv().getOrDefault("RATE_LIMIT_TEST_REDIS_DATABASE", "0")));
        connection.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AopConfiguration.class, SlidingWindowLimitAspect.class, RateLimitedController.class);
            context.registerBean(StringRedisTemplate.class, () -> redis);
            context.refresh();

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr(remoteAddress);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try {
                RateLimitedController controller = context.getBean(RateLimitedController.class);
                assertEquals("ok", controller.call());
                assertThrows(RateLimitExceededException.class, controller::call);
                assertEquals(1L, redis.opsForZSet().size(key));
            } finally {
                RequestContextHolder.resetRequestAttributes();
                redis.delete(key);
            }
        } finally {
            connection.destroy();
        }
    }

}
