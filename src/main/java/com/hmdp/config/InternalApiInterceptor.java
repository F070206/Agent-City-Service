package com.hmdp.config;

import com.hmdp.client.InternalApiGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class InternalApiInterceptor implements HandlerInterceptor {
    private final InternalApiGuard guard;

    public InternalApiInterceptor(InternalApiGuard guard) {
        this.guard = guard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            guard.verify(request.getHeader("X-Internal-Token"));
            return true;
        } catch (SecurityException e) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
    }
}
