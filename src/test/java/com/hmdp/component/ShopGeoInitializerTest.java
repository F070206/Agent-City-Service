package com.hmdp.component;

import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.redisson.api.RBloomFilter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.ArrayList;
import java.util.List;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ShopGeoInitializerTest {
    @Test
    @SuppressWarnings("unchecked")
    void backfillsValidLocationsInIdPagesGroupedByShopType() {
        ShopMapper mapper = mock(ShopMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        GeoOperations<String, String> geo = mock(GeoOperations.class);
        RBloomFilter<Long> bloom = mock(RBloomFilter.class);
        when(redis.opsForGeo()).thenReturn(geo);
        List<Shop> firstPage = new ArrayList<>();
        for (long id = 1; id <= 1000; id++) {
            firstPage.add(new Shop().setId(id).setTypeId(id % 2 == 0 ? 10L : 20L)
                    .setX(121.0).setY(31.0));
        }
        when(mapper.selectGeoDataAfter(0L, 1000)).thenReturn(firstPage);
        when(mapper.selectGeoDataAfter(1000L, 1000)).thenReturn(List.of(
                new Shop().setId(1001L).setTypeId(10L).setX(122.0).setY(32.0)));
        new ShopGeoInitializer(mapper, redis, bloom).afterSingletonsInstantiated();
        verify(geo, times(2)).add(eq("shop:geo:10"), anyList());
        verify(geo).add(eq("shop:geo:20"), anyList());
        verify(mapper, times(1)).selectGeoDataAfter(1000L, 1000);
        verify(bloom).tryInit(100000L, 0.01);
        verify(bloom).add(1001L);
        verify(bloom).add(4L);
    }
}
