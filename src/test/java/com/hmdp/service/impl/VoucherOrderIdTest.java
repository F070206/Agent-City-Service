package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.JacksonConfig;
import com.hmdp.config.RocketMQConstants;
import com.hmdp.component.SeckillActivityCache;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillVoucherLookup;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillOrderDispatch;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import com.hmdp.mapper.SeckillOrderGuardMapper;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.SnowflakeIdWorker;
import com.hmdp.utils.UserHolder;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.client.producer.SendCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.*;

class VoucherOrderIdTest {

    private VoucherOrderServiceImpl service;
    private VoucherOrderMapper orderMapper;
    private IVoucherService voucherService;
    private StringRedisTemplate redisTemplate;
    private RocketMQTemplate rocketMQTemplate;
    private ObjectMapper objectMapper;
    private SeckillOrderDispatchMapper dispatchMapper;
    private ISeckillVoucherService seckillVoucherService;
    private SeckillVoucherMapper seckillVoucherMapper;
    private SeckillOrderGuardMapper orderGuardMapper;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderServiceImpl();
        orderMapper = mock(VoucherOrderMapper.class);
        voucherService = mock(IVoucherService.class);
        seckillVoucherService = mock(ISeckillVoucherService.class);
        seckillVoucherMapper = mock(SeckillVoucherMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);

        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JacksonConfig().jacksonLongToStringCustomizer().customize(builder);
        objectMapper = builder.build();

        ReflectionTestUtils.setField(service, "baseMapper", orderMapper);
        ReflectionTestUtils.setField(service, "voucherService", voucherService);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "seckillVoucherMapper", seckillVoucherMapper);
        ReflectionTestUtils.setField(service, "seckillActivityCache", mock(SeckillActivityCache.class));
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        dispatchMapper = mock(SeckillOrderDispatchMapper.class);
        ReflectionTestUtils.setField(service, "seckillOrderDispatchMapper", dispatchMapper);
        orderGuardMapper = mock(SeckillOrderGuardMapper.class);
        ReflectionTestUtils.setField(service, "seckillOrderGuardMapper", orderGuardMapper);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "snowflakeIdWorker", new SnowflakeIdWorker(3, 2));

        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void normalOrderShouldPersistSnowflakeIdWithoutRedisAndReturnItAsString() throws Exception {
        Voucher voucher = new Voucher().setId(10L).setStatus(1).setType(0);
        when(voucherService.getById(10L)).thenReturn(voucher);
        when(orderMapper.insert(any(VoucherOrder.class))).thenReturn(1);

        Result result = service.buyVoucher(10L);

        assertTrue(result.getSuccess());
        long orderId = (Long) result.getData();
        assertConfiguredSnowflake(orderId);
        ArgumentCaptor<VoucherOrder> order = ArgumentCaptor.forClass(VoucherOrder.class);
        verify(orderMapper).insert(order.capture());
        assertEquals(orderId, order.getValue().getId());
        assertEquals(7L, order.getValue().getUserId());
        verifyNoInteractions(redisTemplate, rocketMQTemplate);

        JsonNode response = objectMapper.readTree(objectMapper.writeValueAsString(result));
        assertTrue(response.get("data").isTextual());
        assertEquals(Long.toString(orderId), response.get("data").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingSeckillActivityMustNotReserveStock() {
        when(seckillVoucherMapper.findForSeckill(10L)).thenReturn(null);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get("seckill:stock:10")).thenReturn("1");
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.emptyList()), eq("10"), eq("7"),
                anyString(), anyString())).thenReturn(3L);
        Result result = service.seckillVoucher(10L);
        assertTrue(!result.getSuccess());
        assertEquals("秒杀活动暂不可用，请稍后重试", result.getErrorMsg());
        verify(seckillVoucherMapper).findForSeckill(10L);
        verifyNoInteractions(voucherService, seckillVoucherService);
        verifyNoInteractions(dispatchMapper, rocketMQTemplate);
    }

    @Test
    void inactiveSeckillVoucherMustNotReserveStock() {
        SeckillVoucherLookup lookup = availableSeckillVoucher();
        lookup.setVoucherStatus(2);
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.emptyList()), eq("10"), eq("7"),
                anyString(), anyString())).thenReturn(7L);

        Result result = service.seckillVoucher(10L);

        assertTrue(!result.getSuccess());
        assertEquals("秒杀券不存在或已下架", result.getErrorMsg());
        verifyNoInteractions(seckillVoucherMapper);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq("10"), eq("7"), anyString(), anyString());
        verifyNoInteractions(voucherService, seckillVoucherService);
        verifyNoInteractions(dispatchMapper, rocketMQTemplate);
    }

    @Test
    void normalVoucherCannotUseSeckillEndpoint() {
        SeckillVoucherLookup lookup = availableSeckillVoucher();
        lookup.setVoucherType(0);
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.emptyList()), eq("10"), eq("7"),
                anyString(), anyString())).thenReturn(7L);

        Result result = service.seckillVoucher(10L);

        assertTrue(!result.getSuccess());
        assertEquals("秒杀券不存在或已下架", result.getErrorMsg());
        verifyNoInteractions(seckillVoucherMapper);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq("10"), eq("7"), anyString(), anyString());
        verifyNoInteractions(voucherService, seckillVoucherService);
        verifyNoInteractions(dispatchMapper, rocketMQTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void seckillBeforeStartMustNotReserveStock() {
        SeckillVoucherLookup lookup = availableSeckillVoucher();
        lookup.setBeginTime(LocalDateTime.now().plusDays(1));
        lookup.setEndTime(LocalDateTime.now().plusDays(2));
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.emptyList()), eq("10"), eq("7"),
                anyString(), anyString())).thenReturn(4L);

        assertTrue(!service.seckillVoucher(10L).getSuccess());
        verifyNoInteractions(voucherService, seckillVoucherService);
        verifyNoInteractions(dispatchMapper, rocketMQTemplate);
        verifyNoInteractions(seckillVoucherMapper);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq("10"), eq("7"),
                anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void seckillAfterEndMustNotReserveStock() {
        SeckillVoucherLookup lookup = availableSeckillVoucher();
        lookup.setBeginTime(LocalDateTime.now().minusDays(2));
        lookup.setEndTime(LocalDateTime.now().minusDays(1));
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.emptyList()), eq("10"), eq("7"),
                anyString(), anyString())).thenReturn(5L);

        assertTrue(!service.seckillVoucher(10L).getSuccess());
        verifyNoInteractions(voucherService, seckillVoucherService);
        verifyNoInteractions(dispatchMapper, rocketMQTemplate);
        verifyNoInteractions(seckillVoucherMapper);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq("10"), eq("7"),
                anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistsDispatchBeforeReturningSeckillAccepted() throws Exception {
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.emptyList()), eq("10"), eq("7"),
                anyString(), anyString()))
                .thenReturn(0L);
        when(dispatchMapper.insertRecoveredTasks(anyList())).thenReturn(1);

        Result result = service.seckillVoucher(10L);

        assertTrue(result.getSuccess());
        verifyNoInteractions(seckillVoucherMapper);
        verifyNoInteractions(voucherService, seckillVoucherService);
        long orderId = (Long) result.getData();
        assertConfiguredSnowflake(orderId);
        ArgumentCaptor<String> encodedOrderId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> encodedPayload = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).execute(any(RedisScript.class), eq(Collections.emptyList()), eq("10"), eq("7"),
                encodedOrderId.capture(), encodedPayload.capture());
        assertEquals(Long.toString(orderId), encodedOrderId.getValue());
        JsonNode payload = objectMapper.readTree(encodedPayload.getValue());
        assertTrue(payload.get("id").isTextual());
        assertEquals(Long.toString(orderId), payload.get("id").asText());
        assertEquals(orderId, objectMapper.readValue(encodedPayload.getValue(), VoucherOrder.class).getId());
        ArgumentCaptor<List<SeckillOrderDispatch>> dispatchTasks = ArgumentCaptor.forClass(List.class);
        verify(dispatchMapper).insertRecoveredTasks(dispatchTasks.capture());
        SeckillOrderDispatch dispatchTask = dispatchTasks.getValue().get(0);
        assertEquals(orderId, dispatchTask.getOrderId());
        assertEquals(7L, dispatchTask.getUserId());
        assertEquals(encodedPayload.getValue(), dispatchTask.getPayload());
        assertEquals(objectMapper.readValue(encodedPayload.getValue(), VoucherOrder.class).getCreateTime()
                .plusNanos(com.hmdp.config.RocketMQConstants.ORDER_PAYMENT_TIMEOUT_MILLIS * 1_000_000L),
                dispatchTask.getTimeoutRetryTime());
        verifyNoInteractions(rocketMQTemplate);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void databaseOutboxFailureReturnsPendingAndKeepsRedisRecoveryCopy() {
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.emptyList()), eq("10"), eq("7"),
                anyString(), anyString())).thenReturn(0L);
        when(dispatchMapper.insertRecoveredTasks(anyList()))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(dispatchMapper.findCompletionStatus(anyLong())).thenReturn(null);

        Result result = service.seckillVoucher(10L);

        assertTrue(!result.getSuccess());
        assertEquals("订单状态待确认", result.getErrorMsg());
        var pending = assertInstanceOf(java.util.Map.class, result.getData());
        assertEquals(true, pending.get("pending"));
        assertInstanceOf(String.class, pending.get("orderId"));
        verify(dispatchMapper).insertRecoveredTasks(anyList());
        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    @SuppressWarnings("unchecked")
    void lostDatabaseInsertAcknowledgementIsConfirmedByDurableOutboxRow() {
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.emptyList()), eq("10"), eq("7"),
                anyString(), anyString())).thenReturn(0L);
        when(dispatchMapper.insertRecoveredTasks(anyList()))
                .thenThrow(new IllegalStateException("database commit acknowledgement lost"));
        when(dispatchMapper.findCompletionStatus(anyLong())).thenReturn(0);

        Result result = service.seckillVoucher(10L);

        assertTrue(result.getSuccess());
        assertConfiguredSnowflake((Long) result.getData());
        verify(dispatchMapper).findCompletionStatus((Long) result.getData());
        verifyNoInteractions(rocketMQTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void uncertainSeckillResultReturnsOrderIdAsStructuredPendingData() {
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.emptyList()), eq("10"), eq("7"),
                anyString(), anyString())).thenThrow(new IllegalStateException("connection lost"));

        Result result = service.seckillVoucher(10L);

        assertTrue(!result.getSuccess());
        assertEquals("订单状态待确认", result.getErrorMsg());
        var pending = assertInstanceOf(java.util.Map.class, result.getData());
        assertEquals(true, pending.get("pending"));
        assertInstanceOf(String.class, pending.get("orderId"));
        assertConfiguredSnowflake(Long.parseLong((String) pending.get("orderId")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateDeliveryForSameInFlightOrderDoesNotRefundItsReservation() {
        long orderId = 123L;
        VoucherOrder order = new VoucherOrder().setId(orderId).setUserId(7L).setVoucherId(10L)
                .setStatus(VoucherOrder.STATUS_UNPAID);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(orderMapper.selectById(orderId)).thenReturn(null);
        when(orderGuardMapper.ensureReservationRow(7L, 10L)).thenReturn(1);
        when(orderGuardMapper.reserve(7L, 10L, orderId)).thenReturn(0);
        when(orderGuardMapper.findActiveOrderIdForUpdate(7L, 10L)).thenReturn(orderId);
        when(dispatchMapper.markCompleted(orderId)).thenReturn(0);
        when(dispatchMapper.findCompletionStatus(orderId)).thenReturn(null);
        when(valueOperations.get("seckill:order:pending:" + orderId)).thenReturn("7");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("7"))).thenReturn(1L);

        service.createVoucherOrder(order);

        verify(orderGuardMapper).findActiveOrderIdForUpdate(7L, 10L);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), eq("7"));
    }

    private void assertConfiguredSnowflake(long id) {
        assertTrue(id > 9007199254740991L, "Test must cover an ID beyond JavaScript's safe integer range");
        assertEquals(3, (id >>> 12) & 31);
        assertEquals(2, (id >>> 17) & 31);
    }

    private SeckillVoucherLookup availableSeckillVoucher() {
        SeckillVoucherLookup lookup = new SeckillVoucherLookup();
        lookup.setVoucherId(10L);
        lookup.setStock(1);
        lookup.setBeginTime(LocalDateTime.now().minusDays(1));
        lookup.setEndTime(LocalDateTime.now().plusDays(1));
        lookup.setVoucherStatus(1);
        lookup.setVoucherType(1);
        return lookup;
    }
}
