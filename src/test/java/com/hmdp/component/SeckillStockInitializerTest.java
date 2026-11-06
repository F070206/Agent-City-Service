package com.hmdp.component;

import com.hmdp.dto.SeckillVoucherLookup;
import com.hmdp.mapper.SeckillVoucherMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SeckillStockInitializerTest {

    private SeckillVoucherMapper vouchers;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SeckillStockInitializer initializer;
    private SeckillActivityCache activityCache;
    private SeckillStockRecovery stockRecovery;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        vouchers = mock(SeckillVoucherMapper.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        activityCache = mock(SeckillActivityCache.class);
        stockRecovery = mock(SeckillStockRecovery.class);
        SeckillVoucherLookup lookup = new SeckillVoucherLookup();
        lookup.setVoucherId(10L);
        lookup.setStock(100);
        lookup.setBeginTime(LocalDateTime.now().minusDays(1));
        lookup.setEndTime(LocalDateTime.now().plusDays(1));
        lookup.setVoucherStatus(1);
        lookup.setVoucherType(1);
        when(vouchers.findAllForSeckill()).thenReturn(List.of(lookup));
        when(redis.opsForValue()).thenReturn(values);
        initializer = new SeckillStockInitializer();
        ReflectionTestUtils.setField(initializer, "seckillVoucherMapper", vouchers);
        ReflectionTestUtils.setField(initializer, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(initializer, "seckillActivityCache", activityCache);
        ReflectionTestUtils.setField(initializer, "seckillStockRecovery", stockRecovery);
    }

    @Test
    void restartDoesNotOverwriteReservedStock() {
        when(values.get("seckill:stock:10")).thenReturn("99");
        initializer.afterSingletonsInstantiated();
        verify(values, never()).set(eq("seckill:stock:10"), anyString());
        verify(values, never()).setIfAbsent(eq("seckill:stock:10"), anyString());
        verifyNoInteractions(stockRecovery);
        verify(activityCache).put(any(SeckillVoucherLookup.class));
    }

    @Test
    void safeRecoveryCanRebuildMissingStock() {
        when(stockRecovery.recoverMissingStock(any())).thenReturn(true);
        initializer.afterSingletonsInstantiated();
        verify(stockRecovery).recoverMissingStock(any());
    }

    @Test
    void unsafeOrIncompleteRecoveryIsLeftForReconciliation() {
        when(stockRecovery.recoverMissingStock(any())).thenReturn(false);
        initializer.afterSingletonsInstantiated();
        verify(stockRecovery).recoverMissingStock(any());
        verify(values, never()).setIfAbsent(eq("seckill:stock:10"), anyString());
    }

    @Test
    void recoveryFailureStopsStartupInsteadOfSilentlyContinuing() {
        when(stockRecovery.recoverMissingStock(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, initializer::afterSingletonsInstantiated);
    }
}
