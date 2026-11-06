package com.hmdp.config;

import com.hmdp.utils.RateLimitExceededException;
import com.hmdp.utils.RateLimitUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebExceptionAdviceTest {
    @RestController
    static class LimitedController {
        @GetMapping("/limited")
        public void limited() {
            throw new RateLimitExceededException("Rate limit exceeded");
        }

        @GetMapping("/limit-store-unavailable")
        public void limitStoreUnavailable() {
            throw new RateLimitUnavailableException("Rate limit service is unavailable",
                    new IllegalStateException("redis unavailable"));
        }

        @GetMapping("/index-busy")
        public void indexBusy() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "index rebuild in progress");
        }

        @GetMapping("/index-lock-unavailable")
        public void indexLockUnavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "index lock unavailable");
        }

        @GetMapping("/redis-unavailable")
        public void redisUnavailable() {
            throw new RedisConnectionFailureException("redis unavailable");
        }

        @GetMapping("/runtime-failure")
        public void runtimeFailure() {
            throw new IllegalStateException("unexpected failure");
        }

        @GetMapping("/upload-too-large")
        public void uploadTooLarge() {
            throw new MaxUploadSizeExceededException(8L * 1024 * 1024);
        }
    }

    @Test
    void rateLimitReturnsHttpTooManyRequests() throws Exception {
        MockMvcBuilders.standaloneSetup(new LimitedController())
                .setControllerAdvice(new WebExceptionAdvice())
                .build()
                .perform(get("/limited"))
                .andExpect(status().is(HttpStatus.TOO_MANY_REQUESTS.value()));
    }

    @Test
    void rateLimitStoreFailureReturnsServiceUnavailable() throws Exception {
        MockMvcBuilders.standaloneSetup(new LimitedController())
                .setControllerAdvice(new WebExceptionAdvice())
                .build()
                .perform(get("/limit-store-unavailable"))
                .andExpect(status().is(HttpStatus.SERVICE_UNAVAILABLE.value()));
    }

    @Test
    void indexContentionAndLockFailureUseConflictAndUnavailableStatuses() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new LimitedController())
                .setControllerAdvice(new WebExceptionAdvice()).build();
        mvc.perform(get("/index-busy")).andExpect(status().is(HttpStatus.CONFLICT.value()));
        mvc.perform(get("/index-lock-unavailable"))
                .andExpect(status().is(HttpStatus.SERVICE_UNAVAILABLE.value()));
    }

    @Test
    void redisConnectionFailureReturnsServiceUnavailable() throws Exception {
        MockMvcBuilders.standaloneSetup(new LimitedController())
                .setControllerAdvice(new WebExceptionAdvice())
                .build()
                .perform(get("/redis-unavailable"))
                .andExpect(status().is(HttpStatus.SERVICE_UNAVAILABLE.value()));
    }

    @Test
    void unhandledRuntimeFailureReturnsHttpInternalServerError() throws Exception {
        MockMvcBuilders.standaloneSetup(new LimitedController())
                .setControllerAdvice(new WebExceptionAdvice())
                .build()
                .perform(get("/runtime-failure"))
                .andExpect(status().is(HttpStatus.INTERNAL_SERVER_ERROR.value()));
    }

    @Test
    void oversizedUploadReturnsPayloadTooLarge() throws Exception {
        MockMvcBuilders.standaloneSetup(new LimitedController())
                .setControllerAdvice(new WebExceptionAdvice())
                .build()
                .perform(get("/upload-too-large"))
                .andExpect(status().is(HttpStatus.PAYLOAD_TOO_LARGE.value()));
    }
}
