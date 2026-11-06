package com.hmdp.gateway;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class BusinessRateLimitConfigurationTest {
    @Test
    void authenticationAndUserRoutesHaveSeparateTokenBuckets() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        KeyResolver resolver = resolver(redis);
        var login = MockServerWebExchange.from(MockServerHttpRequest.post("/user/login")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345)));
        var profile = MockServerWebExchange.from(MockServerHttpRequest.get("/user/me")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345)));

        assertEquals("user-auth:ip:127.0.0.1", resolver.resolve(login).block());
        assertEquals("user:ip:127.0.0.1", resolver.resolve(profile).block());
        verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validTokenUsesPersistedUserIdAndBusinessResource() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveHashOperations<String, Object, Object> hashes = mock(ReactiveHashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        String token = "0123456789abcdef0123456789abcdef";
        when(hashes.get("login:token:" + token, "id")).thenReturn(Mono.just("7"));
        KeyResolver resolver = resolver(redis);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/voucher-order/seckill/10")
                .header("authorization", token).remoteAddress(new InetSocketAddress("127.0.0.1", 12345)));

        assertEquals("voucher-order:user:7", resolver.resolve(exchange).block());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reusesShortLivedTokenOwnerLookupAcrossRequests() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveHashOperations<String, Object, Object> hashes = mock(ReactiveHashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        String token = "0123456789abcdef0123456789abcdef";
        when(hashes.get("login:token:" + token, "id")).thenReturn(Mono.just("7"));
        KeyResolver resolver = resolver(redis);
        var first = MockServerWebExchange.from(MockServerHttpRequest.get("/voucher/1")
                .header("authorization", token).remoteAddress(new InetSocketAddress("127.0.0.1", 12345)));
        var second = MockServerWebExchange.from(MockServerHttpRequest.get("/voucher/2")
                .header("authorization", token).remoteAddress(new InetSocketAddress("127.0.0.1", 12345)));

        assertEquals("voucher:user:7", resolver.resolve(first).block());
        assertEquals("voucher:user:7", resolver.resolve(second).block());
        verify(hashes, times(1)).get("login:token:" + token, "id");
    }

    @Test
    void unauthenticatedRequestUsesConnectionAddress() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        KeyResolver resolver = resolver(redis);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/ai/chat")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345)));

        assertEquals("ai:ip:127.0.0.1", resolver.resolve(exchange).block());
        verifyNoInteractions(redis);
    }

    @Test
    void authenticationRouteUsesIpEvenWhenTokenIsValid() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        KeyResolver resolver = resolver(redis);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/user/code")
                .header("authorization", "0123456789abcdef0123456789abcdef")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345)));

        assertEquals("user-auth:ip:127.0.0.1", resolver.resolve(exchange).block());
        verifyNoInteractions(redis);
    }

    @Test
    void trustedProxyUsesRightmostUntrustedAddressFromForwardedChain() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        KeyResolver resolver = new BusinessRateLimitConfiguration()
                .businessKeyResolver(redis, "10.0.0.0/8,2001:db8:ffff::/48");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/ai/chat")
                .header("X-Forwarded-For", "203.0.113.99, 198.51.100.7, 10.0.0.8")
                .remoteAddress(new InetSocketAddress("10.0.0.2", 12345)));

        assertEquals("ai:ip:198.51.100.7", resolver.resolve(exchange).block());
        verifyNoInteractions(redis);
    }

    @Test
    void ignoresForwardedHeaderFromUntrustedPeer() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        KeyResolver resolver = new BusinessRateLimitConfiguration()
                .businessKeyResolver(redis, "10.0.0.0/8");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/ai/chat")
                .header("X-Forwarded-For", "198.51.100.7")
                .remoteAddress(new InetSocketAddress("203.0.113.10", 12345)));

        assertEquals("ai:ip:203.0.113.10", resolver.resolve(exchange).block());
    }

    @Test
    void malformedForwardedChainFallsBackToTrustedProxyAddress() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        KeyResolver resolver = new BusinessRateLimitConfiguration()
                .businessKeyResolver(redis, "10.0.0.0/8");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/ai/chat")
                .header("X-Forwarded-For", "spoof.example, 198.51.100.7")
                .remoteAddress(new InetSocketAddress("10.0.0.2", 12345)));

        assertEquals("ai:ip:10.0.0.2", resolver.resolve(exchange).block());
    }

    @Test
    void combinesForwardedHeaderLinesBeforeResolvingClient() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        KeyResolver resolver = new BusinessRateLimitConfiguration()
                .businessKeyResolver(redis, "10.0.0.0/8");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/ai/chat")
                .header("X-Forwarded-For", "203.0.113.99", "198.51.100.7")
                .remoteAddress(new InetSocketAddress("10.0.0.2", 12345)));

        assertEquals("ai:ip:198.51.100.7", resolver.resolve(exchange).block());
    }

    @Test
    void supportsIpv6TrustedProxyNetworks() throws Exception {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        KeyResolver resolver = new BusinessRateLimitConfiguration()
                .businessKeyResolver(redis, "2001:db8:ffff::/48");
        InetAddress proxy = InetAddress.getByName("2001:db8:ffff::2");
        InetAddress client = InetAddress.getByName("2001:db8::5");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/ai/chat")
                .header("X-Forwarded-For", client.getHostAddress())
                .remoteAddress(new InetSocketAddress(proxy, 12345)));

        assertEquals("ai:ip:" + client.getHostAddress(), resolver.resolve(exchange).block());
    }

    private KeyResolver resolver(ReactiveStringRedisTemplate redis) {
        return new BusinessRateLimitConfiguration().businessKeyResolver(redis, "");
    }
}
