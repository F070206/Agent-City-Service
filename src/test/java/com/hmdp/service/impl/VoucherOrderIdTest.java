package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.JacksonConfig;
import com.hmdp.config.RocketMQConstants;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.SnowflakeIdWorker;
import com.hmdp.utils.UserHolder;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_PENDING_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_PENDING_TTL_MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VoucherOrderIdTest {

    private VoucherOrderServiceImpl service;
    private VoucherOrderMapper orderMapper;
    private IVoucherService voucherService;
    private StringRedisTemplate redisTemplate;
    private RocketMQTemplate rocketMQTemplate;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderServiceImpl();
        orderMapper = mock(VoucherOrderMapper.class);
        voucherService = mock(IVoucherService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);

        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JacksonConfig().jacksonLongToStringCustomizer().customize(builder);
        objectMapper = builder.build();

        ReflectionTestUtils.setField(service, "baseMapper", orderMapper);
        ReflectionTestUtils.setField(service, "voucherService", voucherService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "rocketMQTemplate", rocketMQTemplate);
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
    void seckillShouldUseSameExactIdForResponsePendingKeyAndBothMessages() throws Exception {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.emptyList()), eq("10"), eq("7")))
                .thenReturn(0L);

        Result result = service.seckillVoucher(10L);

        assertTrue(result.getSuccess());
        long orderId = (Long) result.getData();
        assertConfiguredSnowflake(orderId);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(rocketMQTemplate).syncSend(eq(RocketMQConstants.SECKILL_ORDER_DESTINATION), message.capture());
        JsonNode payload = objectMapper.readTree(message.getValue());
        assertTrue(payload.get("id").isTextual());
        assertEquals(Long.toString(orderId), payload.get("id").asText());
        assertEquals(orderId, objectMapper.readValue(message.getValue(), VoucherOrder.class).getId());

        verify(values).set(SECKILL_ORDER_PENDING_KEY + orderId, "7",
                SECKILL_ORDER_PENDING_TTL_MINUTES, TimeUnit.MINUTES);
        verify(values, never()).increment(anyString());
        verify(rocketMQTemplate).syncSendDelayTimeMills(RocketMQConstants.ORDER_TIMEOUT_DESTINATION,
                Long.toString(orderId), RocketMQConstants.ORDER_PAYMENT_TIMEOUT_MILLIS);
    }

    private void assertConfiguredSnowflake(long id) {
        assertTrue(id > 9007199254740991L, "Test must cover an ID beyond JavaScript's safe integer range");
        assertEquals(3, (id >>> 12) & 31);
        assertEquals(2, (id >>> 17) & 31);
    }
}
