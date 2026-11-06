package com.hmdp.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
public class BusinessRateLimitConfiguration {
    @Bean
    public KeyResolver businessKeyResolver(ReactiveStringRedisTemplate redis,
            @Value("${cityai.gateway.trusted-proxies:}") String trustedProxyCidrs) {
        TrustedProxyClientAddressResolver clientAddressResolver =
                new TrustedProxyClientAddressResolver(trustedProxyCidrs);
        TokenUserIdCache tokenUserIds = new TokenUserIdCache();
        return exchange -> {
            String resource = resource(exchange);
            String fallback = "ip:" + clientAddressResolver.resolve(exchange);
            if ("user-auth".equals(resource)) {
                return Mono.just(resource + ":" + fallback);
            }
            String token = exchange.getRequest().getHeaders().getFirst("authorization");
            if (token == null || !token.matches("[0-9a-fA-F]{32}")) {
                return Mono.just(resource + ":" + fallback);
            }
            String cachedUserId = tokenUserIds.get(token);
            if (cachedUserId != null) {
                return Mono.just(resource + ":user:" + cachedUserId);
            }
            return redis.opsForHash().get("login:token:" + token, "id")
                    .map(Object::toString)
                    .filter(id -> id.matches("[0-9]+"))
                    .map(id -> {
                        tokenUserIds.put(token, id);
                        return resource + ":user:" + id;
                    })
                    .switchIfEmpty(Mono.just(resource + ":" + fallback));
        };
    }

    private String resource(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        if ("/user/code".equals(path) || "/user/login".equals(path)) {
            return "user-auth";
        }
        int start = path.startsWith("/") ? 1 : 0;
        int end = path.indexOf('/', start);
        return end < 0 ? path.substring(start) : path.substring(start, end);
    }

}
