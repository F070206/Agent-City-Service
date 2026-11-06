package com.hmdp.component;

import com.hmdp.dto.SeckillVoucherLookup;
import com.hmdp.entity.SeckillOrderReservation;
import com.hmdp.mapper.OrderStockSyncMapper;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import com.hmdp.mapper.SeckillOrderGuardMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SeckillStockRecoveryTest {

    private StringRedisTemplate redis;
    private SeckillOrderDispatchMapper dispatch;
    private SeckillOrderGuardMapper guard;
    private OrderStockSyncMapper stockSync;
    private ValueOperations<String, String> values;
    private SetOperations<String, String> sets;
    private HashOperations<String, Object, Object> hashes;
    private SeckillStockRecovery recovery;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        dispatch = mock(SeckillOrderDispatchMapper.class);
        guard = mock(SeckillOrderGuardMapper.class);
        stockSync = mock(OrderStockSyncMapper.class);
        values = mock(ValueOperations.class);
        sets = mock(SetOperations.class);
        hashes = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.type(anyString())).thenReturn(DataType.NONE);
        when(values.get("seckill:stock:10")).thenReturn(null, "4");
        when(values.setIfAbsent("seckill:stock:10", "4")).thenReturn(true);
        when(hashes.entries("seckill:order:reservation:10")).thenReturn(Map.of());
        when(guard.findActiveReservations(10L)).thenReturn(List.of(reservation(7L, 91L)));
        recovery = new SeckillStockRecovery(redis, dispatch, guard, stockSync);
    }

    @Test
    void restoresCommittedUserReservationsBeforePublishingDatabaseStock() {
        assertTrue(recovery.recoverMissingStock(activity()));

        verify(sets).add("seckill:order:10", "7");
        verify(hashes).putAll("seckill:order:reservation:10", Map.of("7", "91"));
        verify(values).setIfAbsent("seckill:stock:10", "4");
        verify(dispatch).hasPending();
        verify(stockSync).hasPendingForVoucher(10L);
    }

    @Test
    void refusesRebuildWhileAnyDispatchIsUnfinished() {
        when(dispatch.hasPending()).thenReturn(true);

        assertFalse(recovery.recoverMissingStock(activity()));

        verifyNoInteractions(guard, sets, hashes);
        verify(values, never()).setIfAbsent(eq("seckill:stock:10"), anyString());
    }

    @Test
    void refusesToOverwriteReservationForDifferentActiveOrder() {
        when(hashes.entries("seckill:order:reservation:10")).thenReturn(Map.of("7", "92"));

        assertFalse(recovery.recoverMissingStock(activity()));

        verifyNoInteractions(sets);
        verify(hashes, never()).putAll(anyString(), anyMap());
        verify(values, never()).setIfAbsent(eq("seckill:stock:10"), anyString());
    }

    @Test
    void refusesRebuildWhileCloseStockSyncIsUnfinished() {
        when(stockSync.hasPendingForVoucher(10L)).thenReturn(true);

        assertFalse(recovery.recoverMissingStock(activity()));

        verifyNoInteractions(guard, sets, hashes);
        verify(values, never()).setIfAbsent(eq("seckill:stock:10"), anyString());
    }

    @Test
    void doesNotPublishStockIfReservationRestoreFails() {
        doThrow(new IllegalStateException("Redis write failed"))
                .when(hashes).putAll("seckill:order:reservation:10", Map.of("7", "91"));

        assertFalse(recovery.recoverMissingStock(activity()));

        verify(values, never()).setIfAbsent(eq("seckill:stock:10"), anyString());
    }

    @Test
    void refusesRebuildWhenRedisContainsAnUnknownReservedUser() {
        when(sets.members("seckill:order:10")).thenReturn(Set.of("99"));

        assertFalse(recovery.recoverMissingStock(activity()));

        verify(sets, never()).add(eq("seckill:order:10"), any(String[].class));
        verify(values, never()).setIfAbsent(eq("seckill:stock:10"), anyString());
    }

    private SeckillOrderReservation reservation(long userId, long orderId) {
        SeckillOrderReservation reservation = new SeckillOrderReservation();
        reservation.setUserId(userId);
        reservation.setOrderId(orderId);
        return reservation;
    }

    private SeckillVoucherLookup activity() {
        SeckillVoucherLookup lookup = new SeckillVoucherLookup();
        lookup.setVoucherId(10L);
        lookup.setStock(4);
        return lookup;
    }
}
