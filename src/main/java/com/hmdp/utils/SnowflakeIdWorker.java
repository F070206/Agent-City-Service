package com.hmdp.utils;

import com.baomidou.mybatisplus.core.toolkit.Sequence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 复用 MyBatis-Plus 雪花算法，在本地生成订单 ID，不依赖 Redis 计数器。
 * 结构为：1 位符号位 + 41 位毫秒时间戳 + 5 位数据中心 + 5 位机器 + 12 位序列号。
 * 每个运行实例必须使用不同的 (datacenterId, workerId) 组合，并共享本实例的单例生成器。
 */
@Component
public class SnowflakeIdWorker {

    private final Sequence sequence;

    public SnowflakeIdWorker(
            @Value("${id.snowflake.worker-id:0}") long workerId,
            @Value("${id.snowflake.datacenter-id:0}") long datacenterId) {
        // Sequence 校验两个编号均在 0~31 内，并处理并发、序列号溢出和运行期间的时钟回拨。
        this.sequence = new Sequence(workerId, datacenterId);
    }

    public long nextId() {
        return sequence.nextId();
    }
}
