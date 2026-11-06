package com.hmdp.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "ROCKETMQ_TEST_NAMESRV", matches = ".+")
class SeckillRocketMqIntegrationTest {
    @Test
    void delayedMqDeliveryInvokesTimeoutListener() throws Exception {
        String nameServer = System.getenv("ROCKETMQ_TEST_NAMESRV");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String topic = "cityai_it_" + suffix;
        IVoucherOrderService orders = mock(IVoucherOrderService.class);
        OrderTimeoutListener listener = new OrderTimeoutListener(orders);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong deliveredAt = new AtomicLong();
        DefaultMQProducer producer = new DefaultMQProducer("cityai-it-producer-" + suffix);
        producer.setNamesrvAddr(nameServer);
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("cityai-it-consumer-" + suffix);
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        try {
            producer.start();
            producer.send(new Message(topic, "warmup", "warmup".getBytes(StandardCharsets.UTF_8)));
            consumer.subscribe(topic, "close");
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                try {
                    for (var message : messages) {
                        deliveredAt.set(System.nanoTime());
                        listener.onMessage(new String(message.getBody(), StandardCharsets.UTF_8));
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                } catch (Throwable ex) {
                    failure.set(ex);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                } finally {
                    received.countDown();
                }
            });
            consumer.start();
            Message delayed = new Message(topic, "close", "601".getBytes(StandardCharsets.UTF_8));
            delayed.setDelayTimeLevel(1);
            long sentAt = System.nanoTime();
            producer.send(delayed);

            assertTrue(received.await(20, TimeUnit.SECONDS), "Delayed RocketMQ message was not delivered");
            assertNull(failure.get());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(deliveredAt.get() - sentAt) >= 700,
                    "RocketMQ delivered the close message before its delay");
            verify(orders).closeTimeoutOrder(601L);
        } finally {
            consumer.shutdown();
            producer.shutdown();
        }
    }

    @Test
    void realMqDeliveryInvokesOrderListener() throws Exception {
        String nameServer = System.getenv("ROCKETMQ_TEST_NAMESRV");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String topic = "cityai_it_" + suffix;
        IVoucherOrderService orders = mock(IVoucherOrderService.class);
        SeckillVoucherListener listener = new SeckillVoucherListener(orders, new ObjectMapper());
        VoucherOrder order = new VoucherOrder();
        order.setId(501L);
        order.setUserId(502L);
        order.setVoucherId(503L);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        DefaultMQProducer producer = new DefaultMQProducer("cityai-it-producer-" + suffix);
        producer.setNamesrvAddr(nameServer);
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("cityai-it-consumer-" + suffix);
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        try {
            producer.start();
            producer.send(new Message(topic, "create",
                    new ObjectMapper().writeValueAsBytes(order)));
            consumer.subscribe(topic, "create");
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                try {
                    for (var message : messages) {
                        listener.onMessage(new String(message.getBody(), StandardCharsets.UTF_8));
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                } catch (Throwable ex) {
                    failure.set(ex);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                } finally {
                    received.countDown();
                }
            });
            consumer.start();

            assertTrue(received.await(20, TimeUnit.SECONDS), "RocketMQ message was not delivered");
            assertNull(failure.get());
            verify(orders).createVoucherOrder(argThat(actual -> actual.getId().equals(501L)
                    && actual.getUserId().equals(502L) && actual.getVoucherId().equals(503L)));
        } finally {
            consumer.shutdown();
            producer.shutdown();
        }
    }
}
