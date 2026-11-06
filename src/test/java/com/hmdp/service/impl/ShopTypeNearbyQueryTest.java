package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.geo.Distance;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopTypeNearbyQueryTest {
    @Test
    void rejectsInvalidPageAndCoordinateInputsBeforeRedisOrDatabaseAccess() {
        ShopServiceImpl service = spy(new ShopServiceImpl());
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        assertFalse(service.queryShopByType(1, 0, null, null).getSuccess());
        assertFalse(service.queryShopByType(1, 1, 121.0, null).getSuccess());
        assertFalse(service.queryShopByType(1, 1, Double.NaN, 31.0).getSuccess());
        assertFalse(service.queryShopByType(1, 1, 181.0, 31.0).getSuccess());
        assertFalse(service.queryShopByType(1, Integer.MAX_VALUE, 121.0, 31.0).getSuccess());
        assertFalse(service.queryShopByType(1, Integer.MAX_VALUE, null, null).getSuccess());
        verifyNoInteractions(redis);
        verify(service, never()).query();
    }

    @Test
    void allowsPageBoundaryWithinBoundedGeoWindow() {
        ShopServiceImpl service = spy(new ShopServiceImpl());
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        org.springframework.data.redis.core.GeoOperations<String, String> geo = mock(org.springframework.data.redis.core.GeoOperations.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        when(redis.opsForGeo()).thenReturn(geo);
        when(geo.search(anyString(), org.mockito.ArgumentMatchers.<GeoReference<String>>any(),
                any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class))).thenReturn(null);
        Result result = service.queryShopByType(1, 100, 121.0, 31.0);
        assertTrue(result.getSuccess());
        verify(geo).search(eq("shop:geo:1"), org.mockito.ArgumentMatchers.<GeoReference<String>>any(),
                any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class));
    }

    @Test
    void acceptsValidNegativeLongitudeAndLatitude() {
        ShopServiceImpl service = spy(new ShopServiceImpl());
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        org.springframework.data.redis.core.GeoOperations<String, String> geo = mock(org.springframework.data.redis.core.GeoOperations.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        when(redis.opsForGeo()).thenReturn(geo);
        when(geo.search(anyString(), org.mockito.ArgumentMatchers.<GeoReference<String>>any(),
                any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class))).thenReturn(null);

        Result result = service.queryShopByType(1, 1, -73.9857, -40.7128);

        assertTrue(result.getSuccess());
        verify(geo).search(eq("shop:geo:1"), org.mockito.ArgumentMatchers.<GeoReference<String>>any(),
                any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class));
    }

}
