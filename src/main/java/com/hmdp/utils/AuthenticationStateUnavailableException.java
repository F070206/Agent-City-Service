package com.hmdp.utils;

public class AuthenticationStateUnavailableException extends RuntimeException {
    public AuthenticationStateUnavailableException(Throwable cause) {
        super("登录状态存储暂不可用", cause);
    }
}
