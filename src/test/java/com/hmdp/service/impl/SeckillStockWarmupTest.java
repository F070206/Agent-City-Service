package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.SnowflakeIdWorker;
import com.hmdp.utils.UserHolder;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeckillStockWarmupTest {

    private VoucherOrderServiceImpl orders;
    private VoucherOrderMapper mapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ISeckillVoucherService stock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orders = new VoucherOrderServiceImpl();
        mapper = mock(VoucherOrderMapper.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        stock = mock(ISeckillVoucherService.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(any(RedisScript.class), anyList(), eq("10"), eq("7"))).thenReturn(3L);
        ReflectionTestUtils.setField(orders, "baseMapper", mapper);
        ReflectionTestUtils.setField(orders, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(orders, "seckillVoucherService", stock);
        ReflectionTestUtils.setField(orders, "snowflakeIdWorker", new SnowflakeIdWorker(3, 2));
        ReflectionTestUtils.setField(orders, "rocketMQTemplate", mock(RocketMQTemplate.class));
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
    void existingOrdersBlockUnsafeRebuildFromDatabase() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertFalse(orders.seckillVoucher(10L).getSuccess());
        verifyNoInteractions(values, stock);
    }

    @Test
    void inFlightReservationBlocksRebuildBeforeOrderIsPersisted() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(redis.hasKey("seckill:order:10")).thenReturn(true);
        assertFalse(orders.seckillVoucher(10L).getSuccess());
        verifyNoInteractions(values, stock);
    }

    @Test
    @SuppressWarnings("unchecked")
    void competingWarmupDoesNotOverwriteStockAlreadyInitialized() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(stock.getById(10L)).thenReturn(new SeckillVoucher().setVoucherId(10L).setStock(100));
        when(values.setIfAbsent("seckill:stock:10", "100")).thenReturn(false);
        when(redis.execute(any(RedisScript.class), anyList(), eq("10"), eq("7"))).thenReturn(3L, 0L);
        assertTrue(orders.seckillVoucher(10L).getSuccess());
        verify(values).setIfAbsent("seckill:stock:10", "100");
        verify(values, never()).set(eq("seckill:stock:10"), anyString());
    }
}
