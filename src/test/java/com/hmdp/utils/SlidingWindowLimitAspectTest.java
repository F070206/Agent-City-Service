package com.hmdp.utils;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SlidingWindowLimitAspectTest {
    @Test
    void concurrentRequestsReuseOneParsedExpression() throws Exception {
        String expression = "#request";
        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Expression>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    Expression parsed = SlidingWindowLimitAspect.expressionFor(expression);
                    StandardEvaluationContext context = new StandardEvaluationContext();
                    String expected = Thread.currentThread().getName();
                    context.setVariable("request", expected);
                    assertEquals(expected, parsed.getValue(context, String.class));
                    return parsed;
                }));
            }
            ready.await();
            start.countDown();
            var parsed = futures.get(0).get();
            for (Future<Expression> future : futures) {
                assertSame(parsed, future.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class AopConfiguration {
    }

    @SlidingWindowLimit(globalLimit = true)
    static class AnnotatedController {
        public String call() {
            return "ok";
        }

        @SlidingWindowLimit(keySpEL = "#id", globalLimit = true)
        public String callWithMethodLimit(String id) {
            return id;
        }
    }

    @Test
    void classLevelLimitRunsForControllerMethod() {
        StringRedisTemplate redis = allowRequests();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AopConfiguration.class, SlidingWindowLimitAspect.class, AnnotatedController.class);
            context.registerBean(StringRedisTemplate.class, () -> redis);
            context.refresh();
            assertEquals("ok", context.getBean(AnnotatedController.class).call());
            verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void microserviceModeSkipsClassLimitButKeepsMethodLimit() {
        StringRedisTemplate redis = allowRequests();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("micro");
            context.register(AopConfiguration.class, SlidingWindowLimitAspect.class, AnnotatedController.class);
            context.registerBean(StringRedisTemplate.class, () -> redis);
            context.refresh();
            AnnotatedController controller = context.getBean(AnnotatedController.class);

            assertEquals("ok", controller.call());
            verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));

            assertEquals("42", controller.callWithMethodLimit("42"));
            verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void slidingWindowUsesRedisServerClock() {
        StringRedisTemplate redis = allowRequests();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AopConfiguration.class, SlidingWindowLimitAspect.class, AnnotatedController.class);
            context.registerBean(StringRedisTemplate.class, () -> redis);
            context.refresh();

            assertEquals("ok", context.getBean(AnnotatedController.class).call());

            verify(redis).execute(
                    argThat((RedisScript<Long> script) -> script.getScriptAsString().contains("redis.call('TIME')")),
                    eq(List.of("rate_limit:global:#request.remoteAddr:127.0.0.1")),
                    eq("1000"), eq("1000"), eq("60"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void classAndMethodLimitsBothRun() {
        StringRedisTemplate redis = allowRequests();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AopConfiguration.class, SlidingWindowLimitAspect.class, AnnotatedController.class);
            context.registerBean(StringRedisTemplate.class, () -> redis);
            context.refresh();
            AnnotatedController controller = context.getBean(AnnotatedController.class);
            assertEquals("42", controller.callWithMethodLimit("42"));
            var cachedExpression = SlidingWindowLimitAspect.EXPRESSION_CACHE.get("#id");
            assertEquals("42", controller.callWithMethodLimit("42"));
            assertSame(cachedExpression, SlidingWindowLimitAspect.EXPRESSION_CACHE.get("#id"));
            ArgumentCaptor<RedisScript<Long>> scripts = ArgumentCaptor.forClass(RedisScript.class);
            verify(redis, times(4)).execute(scripts.capture(), anyList(), any(Object[].class));
            RedisScript<Long> reusedScript = scripts.getValue();
            scripts.getAllValues().forEach(script -> assertSame(reusedScript, script));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void rateLimitStoreFailureRejectsRequest() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis unavailable"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AopConfiguration.class, SlidingWindowLimitAspect.class, AnnotatedController.class);
            context.registerBean(StringRedisTemplate.class, () -> redis);
            context.refresh();

            assertThrows(RateLimitUnavailableException.class,
                    () -> context.getBean(AnnotatedController.class).call());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void missingRateLimitDecisionRejectsRequest() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AopConfiguration.class, SlidingWindowLimitAspect.class, AnnotatedController.class);
            context.registerBean(StringRedisTemplate.class, () -> redis);
            context.refresh();

            assertThrows(RateLimitUnavailableException.class,
                    () -> context.getBean(AnnotatedController.class).call());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private StringRedisTemplate allowRequests() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        return redis;
    }
}
