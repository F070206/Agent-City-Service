package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RedisConfigTest {

    @Test
    void appliesBoundLettucePoolSettingsToConnectionFactory() {
        RedisProperties properties = new RedisProperties();
        RedisProperties.Pool pool = properties.getLettuce().getPool();
        pool.setEnabled(true);
        pool.setMaxActive(10);
        pool.setMaxIdle(10);
        pool.setMinIdle(1);

        RedisConfig config = redisConfig(properties);
        LettuceConnectionFactory factory = config.redisConnectionFactory();

        LettucePoolingClientConfiguration clientConfiguration = assertInstanceOf(
                LettucePoolingClientConfiguration.class, factory.getClientConfiguration());
        assertEquals(10, clientConfiguration.getPoolConfig().getMaxTotal());
        assertEquals(10, clientConfiguration.getPoolConfig().getMaxIdle());
        assertEquals(1, clientConfiguration.getPoolConfig().getMinIdle());
        factory.destroy();
    }

    @Test
    void leavesPoolingDisabledWhenConfigurationDisablesIt() {
        RedisProperties properties = new RedisProperties();
        properties.getLettuce().getPool().setEnabled(false);

        RedisConfig config = redisConfig(properties);
        LettuceConnectionFactory factory = config.redisConnectionFactory();

        assertFalse(factory.getClientConfiguration() instanceof LettucePoolingClientConfiguration);
        factory.destroy();
    }

    private RedisConfig redisConfig(RedisProperties properties) {
        RedisConfig config = new RedisConfig(properties);
        ReflectionTestUtils.setField(config, "host", "localhost");
        ReflectionTestUtils.setField(config, "port", 6379);
        ReflectionTestUtils.setField(config, "database", 0);
        return config;
    }
}
