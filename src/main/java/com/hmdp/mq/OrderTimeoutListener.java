package com.hmdp.mq;

import com.hmdp.config.RocketMQConstants;
import com.hmdp.service.IVoucherOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        consumerGroup = RocketMQConstants.ORDER_TIMEOUT_CONSUMER_GROUP,
        topic = RocketMQConstants.ORDER_TIMEOUT_TOPIC,
        selectorExpression = RocketMQConstants.ORDER_TIMEOUT_TAG,
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING
)
public class OrderTimeoutListener implements RocketMQListener<String> {

    private final IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(String orderIdStr) {
        log.info("收到订单超时消息：订单ID={}", orderIdStr);
        
        try {
            Long orderId = Long.parseLong(orderIdStr);
            // 条件更新负责互斥；异常继续交给 MQ 重试，不因抢锁失败丢弃消息。
            voucherOrderService.closeTimeoutOrder(orderId);
            
        } catch (Exception e) {
            log.error("订单超时处理异常，订单ID={}", orderIdStr, e);
            throw new IllegalStateException("订单超时处理失败，订单ID=" + orderIdStr, e);
        }
    }
}
