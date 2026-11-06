package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.component.SeckillActivityCache;
import com.hmdp.component.SeckillStockRecovery;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.dto.SeckillVoucherLookup;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.SnowflakeIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeckillStockWarmupTest {

    private VoucherOrderServiceImpl orders;
    private VoucherOrderMapper mapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ZSetOperations<String, String> zsets;
    private IVoucherService voucherService;
    private SeckillOrderDispatchMapper dispatchMapper;
    private SeckillVoucherMapper seckillVoucherMapper;
    private SeckillActivityCache activityCache;
    private SeckillStockRecovery stockRecovery;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orders = new VoucherOrderServiceImpl();
        mapper = mock(VoucherOrderMapper.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        zsets = mock(ZSetOperations.class);
        voucherService = mock(IVoucherService.class);
        seckillVoucherMapper = mock(SeckillVoucherMapper.class);
        activityCache = mock(SeckillActivityCache.class);
        stockRecovery = mock(SeckillStockRecovery.class);
        SeckillVoucherLookup activity = new SeckillVoucherLookup();
        activity.setVoucherId(10L);
        activity.setStock(100);
        activity.setBeginTime(LocalDateTime.now().minusDays(1));
        activity.setEndTime(LocalDateTime.now().plusDays(1));
        activity.setVoucherStatus(1);
        activity.setVoucherType(1);
        when(seckillVoucherMapper.findForSeckill(10L)).thenReturn(activity);
        when(voucherService.getById(10L)).thenReturn(new Voucher().setId(10L).setStatus(1).setType(1));
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(redis.execute(any(RedisScript.class), anyList(), eq("10"), eq("7"),
                anyString(), anyString())).thenReturn(3L);
        ReflectionTestUtils.setField(orders, "baseMapper", mapper);
        ReflectionTestUtils.setField(orders, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(orders, "voucherService", voucherService);
        ReflectionTestUtils.setField(orders, "snowflakeIdWorker", new SnowflakeIdWorker(3, 2));
        dispatchMapper = mock(SeckillOrderDispatchMapper.class);
        when(dispatchMapper.insertRecoveredTasks(anyList())).thenReturn(1);
        ReflectionTestUtils.setField(orders, "seckillOrderDispatchMapper", dispatchMapper);
        ReflectionTestUtils.setField(orders, "seckillVoucherMapper", seckillVoucherMapper);
        ReflectionTestUtils.setField(orders, "seckillActivityCache", activityCache);
        ReflectionTestUtils.setField(orders, "seckillStockRecovery", stockRecovery);
        ReflectionTestUtils.setField(orders, "objectMapper", new ObjectMapper().findAndRegisterModules());
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void failedSafeRebuildKeepsRequestBlockedWhenOrdersExist() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(stockRecovery.recoverMissingStock(any())).thenReturn(false);
        assertFalse(orders.seckillVoucher(10L).getSuccess());
        verify(values).get("seckill:stock:10");
        verify(stockRecovery).recoverMissingStock(any());
    }

    @Test
    void inFlightReservationBlocksRebuildBeforeOrderIsPersisted() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(stockRecovery.recoverMissingStock(any())).thenReturn(false);
        assertFalse(orders.seckillVoucher(10L).getSuccess());
        verify(values).get("seckill:stock:10");
        verify(stockRecovery).recoverMissingStock(any());
    }

    @Test
    void pendingDispatchBlocksRebuildAfterRedisReservationWasLost() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(stockRecovery.recoverMissingStock(any())).thenReturn(false);

        assertFalse(orders.seckillVoucher(10L).getSuccess());
        verify(stockRecovery).recoverMissingStock(any());
        verify(values, never()).setIfAbsent(eq("seckill:stock:10"), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void competingWarmupDoesNotOverwriteStockAlreadyInitialized() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(stockRecovery.recoverMissingStock(any())).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), eq("10"), eq("7"),
                anyString(), anyString())).thenReturn(3L, 0L);
        assertTrue(orders.seckillVoucher(10L).getSuccess());
        verify(stockRecovery).recoverMissingStock(any());
        verify(activityCache).put(any(SeckillVoucherLookup.class));
        verify(values, never()).set(eq("seckill:stock:10"), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingActivityMetadataRefreshesWithoutRebuildingExistingStock() {
        when(values.get("seckill:stock:10")).thenReturn("42");
        when(redis.execute(any(RedisScript.class), anyList(), eq("10"), eq("7"),
                anyString(), anyString())).thenReturn(3L, 0L);

        assertTrue(orders.seckillVoucher(10L).getSuccess());

        verify(seckillVoucherMapper).findForSeckill(10L);
        verify(activityCache).put(any(SeckillVoucherLookup.class));
        verify(values, never()).setIfAbsent(eq("seckill:stock:10"), anyString());
        verify(values, never()).set(eq("seckill:stock:10"), anyString());
        verify(redis, times(2)).execute(any(RedisScript.class), anyList(), eq("10"), eq("7"),
                anyString(), anyString());
    }
}
