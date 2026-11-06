package com.hmdp.component;

import com.hmdp.config.RocketMQConstants;
import com.hmdp.entity.SeckillOrderDispatch;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDateTime;

import static com.hmdp.utils.RedisConstants.SECKILL_DISPATCH_PAYLOAD_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_DISPATCH_RECOVERY_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_PENDING_KEY;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderDispatchWorker {
    private final SeckillOrderDispatchMapper taskMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${order.dispatch.delay-ms:250}")
    public void retryPending() {
        try {
            recoverMissingDispatchTasks();
        } catch (Exception e) {
            // Redis 恢复队列不可用时，仍继续处理数据库中已经持久化的重投任务。
            log.error("扫描 Redis 秒杀恢复队列失败，将继续重投数据库任务", e);
        }
        try {
            sendDueTimeoutMessages();
        } catch (Exception e) {
            // 超时投递存储故障不能阻断秒杀下单消息的重试。
            log.error("扫描超时关单投递任务失败，将在后续调度重试", e);
        }
        String claimToken = UUID.randomUUID().toString();
        taskMapper.claimPending(claimToken);
        for (SeckillOrderDispatch task : taskMapper.findClaimed(claimToken)) {
            try {
                requireSendAccepted(rocketMQTemplate.syncSend(
                        RocketMQConstants.SECKILL_ORDER_DESTINATION, task.getPayload()));
            } catch (Exception e) {
                log.error("重投秒杀订单失败，订单ID={}", task.getOrderId(), e);
            } finally {
                try {
                    taskMapper.recordAttempt(task.getOrderId(), claimToken);
                } catch (Exception e) {
                    log.error("记录秒杀订单重投时间失败，订单ID={}", task.getOrderId(), e);
                }
            }
        }
    }

    private void recoverMissingDispatchTasks() {
        Set<String> orderIds = redisTemplate.opsForZSet().range(SECKILL_DISPATCH_RECOVERY_KEY, 0, 499);
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        List<String> memberIds = new ArrayList<>(orderIds);
        Map<String, Long> parsedOrderIds = new HashMap<>();
        for (String orderIdValue : memberIds) {
            try {
                parsedOrderIds.put(orderIdValue, Long.valueOf(orderIdValue));
            } catch (Exception e) {
                log.error("秒杀恢复队列中的订单 ID 无效，延后重试，订单ID={}", orderIdValue, e);
                deferRecoveryRecord(orderIdValue);
            }
        }
        if (parsedOrderIds.isEmpty()) {
            return;
        }

        List<Long> parsedIds = new ArrayList<>(parsedOrderIds.values());
        List<SeckillOrderDispatch> existingTasks;
        try {
            existingTasks = taskMapper.findCompletionStatuses(parsedIds);
        } catch (Exception e) {
            parsedOrderIds.keySet().forEach(this::deferRecoveryRecord);
            throw e;
        }
        Map<Long, Integer> completionStatuses = new HashMap<>();
        if (existingTasks != null) {
            for (SeckillOrderDispatch task : existingTasks) {
                completionStatuses.put(task.getOrderId(), task.getCompleted());
            }
        }

        List<String> existingMembers = new ArrayList<>();
        List<String> completedMembers = new ArrayList<>();
        List<String> missingMembers = new ArrayList<>();
        for (Map.Entry<String, Long> entry : parsedOrderIds.entrySet()) {
            Integer status = completionStatuses.get(entry.getValue());
            if (status == null) {
                missingMembers.add(entry.getKey());
            } else {
                existingMembers.add(entry.getKey());
                if (status == 1) {
                    completedMembers.add(entry.getKey());
                }
            }
        }
        cleanupRecoveryRecords(existingMembers, completedMembers);
        if (missingMembers.isEmpty()) {
            return;
        }

        List<String> missingIds = new ArrayList<>(missingMembers.size());
        List<String> payloadKeys = new ArrayList<>(missingMembers.size());
        for (String member : missingMembers) {
            missingIds.add(member);
            payloadKeys.add(SECKILL_DISPATCH_PAYLOAD_KEY + member);
        }
        List<String> payloads = redisTemplate.opsForValue().multiGet(payloadKeys);
        List<SeckillOrderDispatch> recoveryTasks = new ArrayList<>();
        List<String> recoveryMembers = new ArrayList<>();
        for (int i = 0; i < missingIds.size(); i++) {
            String orderIdValue = missingIds.get(i);
            try {
                String payload = payloads == null || i >= payloads.size() ? null : payloads.get(i);
                if (!StringUtils.hasText(payload)) {
                    log.error("秒杀恢复队列存在订单但缺少消息内容，保留记录待核对，订单ID={}", orderIdValue);
                    deferRecoveryRecord(orderIdValue);
                    continue;
                }
                VoucherOrder order = objectMapper.readValue(payload, VoucherOrder.class);
                Long orderId = parsedOrderIds.get(orderIdValue);
                if (!orderId.equals(order.getId()) || order.getUserId() == null || order.getCreateTime() == null) {
                    log.error("秒杀恢复消息字段不匹配，保留记录待核对，订单ID={}", orderIdValue);
                    deferRecoveryRecord(orderIdValue);
                    continue;
                }
                SeckillOrderDispatch task = new SeckillOrderDispatch();
                task.setOrderId(orderId);
                task.setUserId(order.getUserId());
                task.setPayload(payload);
                task.setTimeoutRetryTime(order.getCreateTime().plusNanos(
                        RocketMQConstants.ORDER_PAYMENT_TIMEOUT_MILLIS * 1_000_000L));
                recoveryTasks.add(task);
                recoveryMembers.add(orderIdValue);
            } catch (Exception e) {
                log.error("秒杀恢复消息解析失败，保留记录待核对，订单ID={}", orderIdValue, e);
                deferRecoveryRecord(orderIdValue);
            }
        }
        if (recoveryTasks.isEmpty()) {
            return;
        }

        try {
            taskMapper.insertRecoveredTasks(recoveryTasks);
        } catch (Exception e) {
            recoveryMembers.forEach(this::deferRecoveryRecord);
            throw e;
        }
        List<Long> recoveredIds = new ArrayList<>(recoveryTasks.size());
        for (SeckillOrderDispatch task : recoveryTasks) {
            recoveredIds.add(task.getOrderId());
        }
        List<SeckillOrderDispatch> persistedTasks;
        try {
            persistedTasks = taskMapper.findCompletionStatuses(recoveredIds);
        } catch (Exception e) {
            recoveryMembers.forEach(this::deferRecoveryRecord);
            throw e;
        }
        completionStatuses.clear();
        if (persistedTasks != null) {
            for (SeckillOrderDispatch task : persistedTasks) {
                completionStatuses.put(task.getOrderId(), task.getCompleted());
            }
        }
        List<String> persistedMembers = new ArrayList<>();
        completedMembers.clear();
        for (String member : recoveryMembers) {
            Integer status = completionStatuses.get(parsedOrderIds.get(member));
            if (status == null) {
                log.error("秒杀恢复任务写入后仍无法查到，保留 Redis 副本，订单ID={}", member);
                deferRecoveryRecord(member);
                continue;
            }
            persistedMembers.add(member);
            if (status == 1) {
                completedMembers.add(member);
            }
        }
        cleanupRecoveryRecords(persistedMembers, completedMembers);
    }

    private void cleanupRecoveryRecords(List<String> orderIds, List<String> completedOrderIds) {
        if (orderIds.isEmpty()) {
            return;
        }
        if (!completedOrderIds.isEmpty()) {
            List<String> pendingKeys = completedOrderIds.stream()
                    .map(orderId -> SECKILL_ORDER_PENDING_KEY + orderId)
                    .toList();
            redisTemplate.delete(pendingKeys);
        }
        List<String> payloadKeys = orderIds.stream()
                .map(orderId -> SECKILL_DISPATCH_PAYLOAD_KEY + orderId)
                .toList();
        redisTemplate.delete(payloadKeys);
        redisTemplate.opsForZSet().remove(SECKILL_DISPATCH_RECOVERY_KEY, orderIds.toArray());
    }

    private void deferRecoveryRecord(String orderId) {
        redisTemplate.opsForZSet().incrementScore(SECKILL_DISPATCH_RECOVERY_KEY, orderId, 1);
    }

    private void sendDueTimeoutMessages() {
        String claimToken = UUID.randomUUID().toString();
        if (taskMapper.claimTimeoutPending(claimToken) < 1) {
            return;
        }
        List<Long> orderIds = taskMapper.findTimeoutClaimed(claimToken);
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        for (Long orderId : orderIds) {
            try {
                SendResult result = rocketMQTemplate.syncSend(
                        RocketMQConstants.ORDER_TIMEOUT_DESTINATION,
                        String.valueOf(orderId));
                requireSendAccepted(result);
                if (taskMapper.markTimeoutSent(orderId, claimToken) != 1) {
                    log.warn("订单超时消息已发送，但任务认领已变化，可能会重复投递，订单ID={}", orderId);
                }
            } catch (Exception e) {
                try {
                    taskMapper.recordTimeoutAttempt(orderId, claimToken);
                } catch (Exception retryError) {
                    e.addSuppressed(retryError);
                }
                log.error("发送订单超时延迟消息失败，将持久化重试，订单ID={}", orderId, e);
            }
        }
    }

    private void requireSendAccepted(SendResult result) {
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("RocketMQ 未确认消息发送成功，status="
                    + (result == null ? "null" : result.getSendStatus()));
        }
    }
}
