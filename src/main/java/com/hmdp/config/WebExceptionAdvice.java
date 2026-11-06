package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.utils.RateLimitExceededException;
import com.hmdp.utils.RateLimitUnavailableException;
import com.hmdp.utils.AuthenticationStateUnavailableException;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result handleSecurityException(SecurityException e) {
        return Result.fail("无权访问内部接口");
    }

    @ExceptionHandler({RestClientException.class, FeignException.class})
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Result handleDependencyFailure(RuntimeException e) {
        log.error("内部服务调用失败", e);
        return Result.fail("依赖服务暂不可用");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Result handleRateLimitExceededException(RateLimitExceededException e) {
        log.warn("请求触发限流: {}", e.getMessage());
        return Result.fail("请求过于频繁，请稍后再试");
    }

    @ExceptionHandler(RateLimitUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result handleRateLimitUnavailableException(RateLimitUnavailableException e) {
        log.error("限流服务暂不可用", e);
        return Result.fail("请求保护服务暂不可用，请稍后再试");
    }

    @ExceptionHandler(AuthenticationStateUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result handleAuthenticationStateUnavailable(AuthenticationStateUnavailableException e) {
        log.error("共享登录状态服务暂不可用", e);
        return Result.fail("登录状态服务暂不可用，请稍后再试");
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result handleRedisUnavailable(RedisConnectionFailureException e) {
        log.error("Redis 服务暂不可用", e);
        return Result.fail("缓存服务暂不可用，请稍后再试");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result> handleResponseStatusException(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Result.fail(e.getReason()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Result handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        return Result.fail("图片不能超过 8 MiB");
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
