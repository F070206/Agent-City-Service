package com.hmdp.utils;

/** Raised when the shared rate-limit store cannot decide whether a request is allowed. */
public class RateLimitUnavailableException extends RuntimeException {
    public RateLimitUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
