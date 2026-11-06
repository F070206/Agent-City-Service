package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.OrderStockSync;
import com.hmdp.component.OrderStockSyncWorker;
import com.hmdp.component.OrderTimeoutRecoveryWorker;
import com.hmdp.component.SeckillStockRecovery;
import com.hmdp.mapper.OrderStockSyncMapper;
import com.hmdp.mapper.OrderTimeoutRetryMapper;
import com.hmdp.entity.OrderTimeoutRetry;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillVoucherLookup;
import com.hmdp.dto.UserDTO;
import com.hmdp.mapper.SeckillOrderDispatchMapper;
import com.hmdp.mapper.SeckillOrderGuardMapper;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.OrderTimeoutListener;
import com.hmdp.mq.SeckillVoucherListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.SnowflakeIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** 使用临时独立数据库；仅显式设置 ROCKETMQ_TEST_NAMESRV 时访问测试 MQ。 */
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_MYSQL_HOST", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentConsistencyIntegrationTest {

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String database;
    private VoucherOrderServiceImpl target;
    private IVoucherOrderService orders;
    private PaymentServiceImpl payments;
    private OrderStockSyncMapper tasks;
    private OrderTimeoutRetryMapper timeoutRetries;
    private SqlSessionFactory sqlSessionFactory;
    private VoucherOrderMapper orderMapper;
    private SeckillOrderDispatchMapper dispatch;
    private SeckillOrderGuardMapper orderGuard;
    private StringRedisTemplate redis;
    private DataSourceTransactionManager transactions;

    @BeforeAll
    void createIsolatedDatabase() throws Exception {
        String server = "jdbc:mysql://" + System.getenv("PAYMENT_TEST_MYSQL_HOST") + ":"
                + System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PORT", "3306") + "/";
        String user = System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PASSWORD", "");
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        admin = new JdbcTemplate(new DriverManagerDataSource(server + options, user, password));
        database = "payment_consistency_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(server + database + options, user, password);
        jdbc = new JdbcTemplate(dataSource);
        String ddl = new ClassPathResource("db/hmdp.sql").getContentAsString(StandardCharsets.UTF_8);
        for (String table : new String[]{"tb_voucher_order", "tb_seckill_voucher"}) {
            int start = ddl.indexOf("CREATE TABLE `" + table + "`");
            // 原始建表脚本的零日期默认值不兼容 MySQL 严格模式；测试始终显式填写活动日期。
            jdbc.execute(ddl.substring(start, ddl.indexOf(';', start))
                    .replace("DEFAULT '0000-00-00 00:00:00'", "DEFAULT CURRENT_TIMESTAMP"));
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/order-stock-sync.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/order-timeout-retry.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/seckill-order-dispatch.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/seckill-order-guard.sql")).execute(dataSource);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(VoucherOrderMapper.class);
        configuration.addMapper(SeckillVoucherMapper.class);
        configuration.addMapper(OrderStockSyncMapper.class);
        configuration.addMapper(OrderTimeoutRetryMapper.class);
        configuration.addMapper(SeckillOrderDispatchMapper.class);
        configuration.addMapper(SeckillOrderGuardMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        sqlSessionFactory = factory.getObject();
        SqlSessionTemplate session = new SqlSessionTemplate(sqlSessionFactory);
        tasks = session.getMapper(OrderStockSyncMapper.class);
        timeoutRetries = session.getMapper(OrderTimeoutRetryMapper.class);
        orderMapper = session.getMapper(VoucherOrderMapper.class);
        dispatch = session.getMapper(SeckillOrderDispatchMapper.class);
        orderGuard = session.getMapper(SeckillOrderGuardMapper.class);
        SeckillVoucherServiceImpl stock = new SeckillVoucherServiceImpl();
        ReflectionTestUtils.setField(stock, "baseMapper", session.getMapper(SeckillVoucherMapper.class));
        target = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(target, "baseMapper", orderMapper);
        ReflectionTestUtils.setField(target, "seckillVoucherService", stock);
        ReflectionTestUtils.setField(target, "seckillOrderDispatchMapper", dispatch);
        ReflectionTestUtils.setField(target, "seckillOrderGuardMapper", orderGuard);
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForZSet()).thenReturn(mock(ZSetOperations.class));
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        ReflectionTestUtils.setField(target, "stringRedisTemplate", redis);
        transactions = new DataSourceTransactionManager(dataSource);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(interceptor);
        orders = (IVoucherOrderService) proxy.getProxy();
        payments = new PaymentServiceImpl(orders, redis, dispatch);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM tb_seckill_order_guard");
        jdbc.update("DELETE FROM tb_seckill_order_dispatch");
        jdbc.update("DELETE FROM tb_order_stock_sync");
        jdbc.update("DELETE FROM tb_order_timeout_retry");
        jdbc.update("DELETE FROM tb_voucher_order");
        jdbc.update("DELETE FROM tb_seckill_voucher");
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) "
                + "VALUES (10, 0, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY))");
        jdbc.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id, status) VALUES (1, 7, 10, 1)");
        jdbc.update("INSERT INTO tb_seckill_order_dispatch "
                + "(order_id, user_id, payload, timeout_retry_time) "
                + "VALUES (1, 7, '{}', DATE_ADD(NOW(), INTERVAL 15 MINUTE))");
        assertEquals(1, orderGuard.ensureReservationRow(7L, 10L));
        assertEquals(1, orderGuard.reserve(7L, 10L, 1L));
        ReflectionTestUtils.setField(target, "orderStockSyncMapper", tasks);
        clearInvocations(redis);
    }

    @Test
    void restartedTimeoutWorkerClosesExpiredOrderWithoutMq() {
        jdbc.update("UPDATE tb_voucher_order SET create_time = DATE_SUB(NOW(), INTERVAL 16 MINUTE) "
                + "WHERE id = 1");
        new OrderTimeoutRecoveryWorker(orderMapper, orders, timeoutRetries, recoveryLockClient()).closeExpiredOrders();
        assertEquals(VoucherOrder.STATUS_CANCELLED, orders.getById(1L).getStatus());
        assertEquals(1, stock());
        assertEquals(1, taskCount());

        new OrderTimeoutRecoveryWorker(orderMapper, orders, timeoutRetries, recoveryLockClient()).closeExpiredOrders();
        assertEquals(1, stock());
        assertEquals(1, taskCount());
    }

    @Test
    void expiredSeckillOrderCannotBePaidBeforeTimeoutWorkerRuns() {
        jdbc.update("UPDATE tb_voucher_order SET create_time = DATE_SUB(NOW(), INTERVAL 16 MINUTE) WHERE id = 1");
        jdbc.update("UPDATE tb_seckill_order_dispatch SET timeout_retry_time = NULL "
                + "WHERE order_id = 1");

        assertFalse(payments.simulatePaymentCallback(1L).getSuccess());
        assertEquals(VoucherOrder.STATUS_UNPAID, orders.getById(1L).getStatus());
    }

    @Test
    void expiredSeckillOrderCannotStartPaymentBeforeTimeoutWorkerRuns() {
        jdbc.update("UPDATE tb_voucher_order SET create_time = DATE_SUB(NOW(), INTERVAL 16 MINUTE) WHERE id = 1");
        jdbc.update("UPDATE tb_seckill_order_dispatch SET timeout_retry_time = NULL WHERE order_id = 1");

        assertFalse(payments.createPayment(1L, VoucherOrder.PAY_TYPE_WECHAT).getSuccess());
        // pay_type is NOT NULL DEFAULT 1; rejection must leave that persisted default untouched.
        assertEquals(1, jdbc.queryForObject("SELECT pay_type FROM tb_voucher_order WHERE id = 1", Integer.class));
        assertEquals(VoucherOrder.STATUS_UNPAID, orders.getById(1L).getStatus());
    }

    @Test
    void legacySeckillDispatchWithoutDeadlineAllowsPaymentBeforeFifteenMinutes() {
        jdbc.update("UPDATE tb_voucher_order SET create_time = DATE_SUB(NOW(), INTERVAL 10 MINUTE) WHERE id = 1");
        jdbc.update("UPDATE tb_seckill_order_dispatch SET timeout_retry_time = NULL WHERE order_id = 1");

        assertTrue(payments.simulatePaymentCallback(1L).getSuccess());
        assertEquals(VoucherOrder.STATUS_PAID, orders.getById(1L).getStatus());
    }

    @Test
    void ordinaryVoucherOrderDoesNotInheritSeckillPaymentDeadline() {
        jdbc.update("DELETE FROM tb_seckill_order_dispatch WHERE order_id = 1");
        jdbc.update("UPDATE tb_voucher_order SET create_time = DATE_SUB(NOW(), INTERVAL 16 MINUTE) WHERE id = 1");

        assertTrue(payments.simulatePaymentCallback(1L).getSuccess());
        assertEquals(VoucherOrder.STATUS_PAID, orders.getById(1L).getStatus());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_REDIS_HOST", matches = ".+")
    void missingRedisStockAndReservationKeysAreRebuiltFromCommittedMySqlState() {
        long voucherId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        long userId = ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE);
        long orderId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) "
                + "VALUES (?, 4, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY))", voucherId);
        jdbc.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id, status) VALUES (?, ?, ?, 1)",
                orderId, userId, voucherId);
        orderGuard.ensureReservationRow(userId, voucherId);
        assertEquals(1, orderGuard.reserve(userId, voucherId, orderId));
        // The shared fixture seeds an unrelated pending dispatch; drain it to isolate this voucher's recovery.
        jdbc.update("UPDATE tb_seckill_order_dispatch SET completed = 1 WHERE order_id = 1");

        LettuceConnectionFactory connection = new LettuceConnectionFactory(
                System.getenv("PAYMENT_TEST_REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("PAYMENT_TEST_REDIS_PORT", "6379")));
        connection.getStandaloneConfiguration().setPassword(
                System.getenv().getOrDefault("PAYMENT_TEST_REDIS_PASSWORD", ""));
        connection.getStandaloneConfiguration().setDatabase(Integer.parseInt(
                System.getenv().getOrDefault("PAYMENT_TEST_REDIS_DATABASE", "0")));
        connection.afterPropertiesSet();
        StringRedisTemplate liveRedis = new StringRedisTemplate(connection);
        String stockKey = "seckill:stock:" + voucherId;
        String orderKey = "seckill:order:" + voucherId;
        String reservationKey = "seckill:order:reservation:" + voucherId;
        List<String> keys = List.of(stockKey, orderKey, reservationKey);
        try {
            for (String key : keys) {
                assertFalse(liveRedis.hasKey(key), "Test Redis key is not isolated: " + key);
            }
            SeckillVoucherLookup activity = new SeckillVoucherLookup();
            activity.setVoucherId(voucherId);
            activity.setStock(4);

            SeckillStockRecovery recovery = new SeckillStockRecovery(liveRedis, dispatch, orderGuard, tasks);
            assertTrue(recovery.recoverMissingStock(activity));

            assertEquals("4", liveRedis.opsForValue().get(stockKey));
            assertTrue(Boolean.TRUE.equals(liveRedis.opsForSet().isMember(orderKey, Long.toString(userId))));
            assertEquals(Long.toString(orderId), liveRedis.<String, String>opsForHash()
                    .get(reservationKey, Long.toString(userId)));
        } finally {
            liveRedis.delete(keys);
            connection.destroy();
            jdbc.update("DELETE FROM tb_seckill_order_guard WHERE voucher_id = ?", voucherId);
            jdbc.update("DELETE FROM tb_voucher_order WHERE id = ?", orderId);
            jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", voucherId);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_REDIS_HOST", matches = ".+")
    void committedCloseTaskRestoresRedisReservationOnce() {
        long voucherId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        long orderId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        long userId = 7L;
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) "
                + "VALUES (?, 0, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY))", voucherId);
        jdbc.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id, status) VALUES (?, ?, ?, 1)",
                orderId, userId, voucherId);
        reserve(userId, voucherId, orderId);
        LettuceConnectionFactory connection = new LettuceConnectionFactory(
                System.getenv("PAYMENT_TEST_REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("PAYMENT_TEST_REDIS_PORT", "6379")));
        connection.getStandaloneConfiguration().setPassword(
                System.getenv().getOrDefault("PAYMENT_TEST_REDIS_PASSWORD", ""));
        connection.getStandaloneConfiguration().setDatabase(Integer.parseInt(
                System.getenv().getOrDefault("PAYMENT_TEST_REDIS_DATABASE", "0")));
        connection.afterPropertiesSet();
        StringRedisTemplate liveRedis = new StringRedisTemplate(connection);
        List<String> keys = List.of("seckill:stock:" + voucherId, "seckill:order:" + voucherId,
                "seckill:stock:released:" + orderId, "seckill:order:pending:" + orderId,
                "seckill:order:reservation:" + voucherId);
        boolean ownsKeys = false;
        try {
            for (String key : keys) {
                assertFalse(liveRedis.hasKey(key), "Test Redis is not isolated: " + key);
            }
            ownsKeys = true;
            liveRedis.opsForValue().set(keys.get(0), "0");
            liveRedis.opsForSet().add(keys.get(1), String.valueOf(userId));
            liveRedis.opsForValue().set(keys.get(3), String.valueOf(userId));
            liveRedis.opsForHash().put(keys.get(4), String.valueOf(userId), String.valueOf(orderId));

            orders.closeTimeoutOrder(orderId);
            OrderStockSyncWorker worker = new OrderStockSyncWorker(tasks, liveRedis);
            worker.syncPending();
            worker.syncPending();

            assertEquals("1", liveRedis.opsForValue().get(keys.get(0)));
            assertFalse(liveRedis.opsForSet().isMember(keys.get(1), String.valueOf(userId)));
            assertFalse(liveRedis.opsForHash().hasKey(keys.get(4), String.valueOf(userId)));
            assertTrue(liveRedis.hasKey(keys.get(2)));
            assertFalse(liveRedis.hasKey(keys.get(3)));
            assertEquals(VoucherOrder.STATUS_CANCELLED, orders.getById(orderId).getStatus());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, voucherId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT completed FROM tb_order_stock_sync WHERE order_id = ?", Integer.class, orderId));
        } finally {
            if (ownsKeys) {
                liveRedis.delete(keys);
            }
            connection.destroy();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_REDIS_HOST", matches = ".+")
    void acceptedSeckillOrderCanRecoverFromMySqlAfterItsRedisCopyIsLost() throws Exception {
        long voucherId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        long userId = ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE);
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) "
                + "VALUES (?, 1, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY))", voucherId);

        LettuceConnectionFactory connection = new LettuceConnectionFactory(
                System.getenv("PAYMENT_TEST_REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("PAYMENT_TEST_REDIS_PORT", "6379")));
        connection.getStandaloneConfiguration().setPassword(
                System.getenv().getOrDefault("PAYMENT_TEST_REDIS_PASSWORD", ""));
        connection.getStandaloneConfiguration().setDatabase(Integer.parseInt(
                System.getenv().getOrDefault("PAYMENT_TEST_REDIS_DATABASE", "0")));
        connection.afterPropertiesSet();
        StringRedisTemplate liveRedis = new StringRedisTemplate(connection);
        String stockKey = "seckill:stock:" + voucherId;
        String activityKey = "seckill:activity:" + voucherId;
        String orderSetKey = "seckill:order:" + voucherId;
        String reservationKey = "seckill:order:reservation:" + voucherId;
        List<String> keys = new java.util.ArrayList<>(List.of(stockKey, activityKey, orderSetKey, reservationKey));
        Long acceptedOrderId = null;
        boolean keysAreOwned = false;
        Object oldObjectMapper = ReflectionTestUtils.getField(target, "objectMapper");
        Object oldSnowflakeIdWorker = ReflectionTestUtils.getField(target, "snowflakeIdWorker");
        Object oldRedisTemplate = ReflectionTestUtils.getField(target, "stringRedisTemplate");
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
        try {
            assertFalse(liveRedis.hasKey(stockKey));
            assertFalse(liveRedis.hasKey(activityKey));
            assertFalse(liveRedis.hasKey(orderSetKey));
            assertFalse(liveRedis.hasKey(reservationKey));
            keysAreOwned = true;

            long now = System.currentTimeMillis();
            liveRedis.opsForValue().set(stockKey, "1");
            liveRedis.<String, String>opsForHash().putAll(activityKey, java.util.Map.of(
                    "beginTime", Long.toString(now - 60_000),
                    "endTime", Long.toString(now + 600_000),
                    "voucherStatus", "1",
                    "voucherType", "1",
                    "version", "0"));
            ObjectMapper json = new ObjectMapper().findAndRegisterModules();
            ReflectionTestUtils.setField(target, "objectMapper", json);
            ReflectionTestUtils.setField(target, "snowflakeIdWorker", new SnowflakeIdWorker(29, 2));
            ReflectionTestUtils.setField(target, "stringRedisTemplate", liveRedis);

            Result result = target.seckillVoucher(voucherId);
            assertTrue(result.getSuccess());
            acceptedOrderId = (Long) result.getData();
            keys.add("seckill:order:pending:" + acceptedOrderId);
            keys.add("seckill:dispatch:payload:" + acceptedOrderId);

            assertEquals(0, dispatch.findCompletionStatus(acceptedOrderId));
            String durablePayload = jdbc.queryForObject(
                    "SELECT payload FROM tb_seckill_order_dispatch WHERE order_id = ?", String.class, acceptedOrderId);
            assertEquals(acceptedOrderId, json.readValue(durablePayload, VoucherOrder.class).getId());

            // Simulate loss of the Redis recovery copy after the endpoint has already returned success.
            liveRedis.delete(keys);
            liveRedis.opsForZSet().remove("seckill:dispatch:recovery", Long.toString(acceptedOrderId));
            assertEquals(0, dispatch.findCompletionStatus(acceptedOrderId));

            orders.createVoucherOrder(json.readValue(durablePayload, VoucherOrder.class));
            assertEquals(VoucherOrder.STATUS_UNPAID, orders.getById(acceptedOrderId).getStatus());
            assertEquals(0, jdbc.queryForObject(
                    "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, voucherId));
            assertEquals(1, dispatch.findCompletionStatus(acceptedOrderId));
        } finally {
            if (keysAreOwned) {
                liveRedis.delete(keys);
                if (acceptedOrderId != null) {
                    liveRedis.opsForZSet().remove("seckill:dispatch:recovery", Long.toString(acceptedOrderId));
                }
            }
            connection.destroy();
            jdbc.update("DELETE FROM tb_seckill_order_guard WHERE voucher_id = ?", voucherId);
            if (acceptedOrderId != null) {
                jdbc.update("DELETE FROM tb_seckill_order_dispatch WHERE order_id = ?", acceptedOrderId);
            }
            jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", voucherId);
            jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", voucherId);
            ReflectionTestUtils.setField(target, "objectMapper", oldObjectMapper);
            ReflectionTestUtils.setField(target, "snowflakeIdWorker", oldSnowflakeIdWorker);
            ReflectionTestUtils.setField(target, "stringRedisTemplate", oldRedisTemplate);
            UserHolder.removeUser();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ROCKETMQ_TEST_NAMESRV", matches = ".+")
    void delayedMessageClosesOrderAndPersistsStockSyncTask() throws Exception {
        sendDuplicateDelayedClose(1L);
        assertEquals(VoucherOrder.STATUS_CANCELLED, orders.getById(1L).getStatus());
        assertEquals(1, stock());
        assertEquals(1, taskCount());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ROCKETMQ_TEST_NAMESRV", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_REDIS_HOST", matches = ".+")
    void duplicateDelayedMessagesEventuallyRestoreRedisOnce() throws Exception {
        long voucherId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        long orderId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) "
                + "VALUES (?, 0, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY))", voucherId);
        jdbc.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id, status) VALUES (?, 7, ?, 1)",
                orderId, voucherId);
        reserve(7L, voucherId, orderId);
        LettuceConnectionFactory connection = new LettuceConnectionFactory(
                System.getenv("PAYMENT_TEST_REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("PAYMENT_TEST_REDIS_PORT", "6379")));
        connection.getStandaloneConfiguration().setPassword(
                System.getenv().getOrDefault("PAYMENT_TEST_REDIS_PASSWORD", ""));
        connection.getStandaloneConfiguration().setDatabase(Integer.parseInt(
                System.getenv().getOrDefault("PAYMENT_TEST_REDIS_DATABASE", "0")));
        connection.afterPropertiesSet();
        StringRedisTemplate liveRedis = new StringRedisTemplate(connection);
        List<String> keys = List.of("seckill:stock:" + voucherId, "seckill:order:" + voucherId,
                "seckill:stock:released:" + orderId, "seckill:order:pending:" + orderId,
                "seckill:order:reservation:" + voucherId);
        boolean ownsKeys = false;
        try {
            for (String key : keys) {
                assertFalse(liveRedis.hasKey(key), "Test Redis is not isolated: " + key);
            }
            ownsKeys = true;
            liveRedis.opsForValue().set(keys.get(0), "0");
            liveRedis.opsForSet().add(keys.get(1), "7");
            liveRedis.opsForValue().set(keys.get(3), "7");
            liveRedis.opsForHash().put(keys.get(4), "7", String.valueOf(orderId));

            sendDuplicateDelayedClose(orderId);
            assertEquals(VoucherOrder.STATUS_CANCELLED, orders.getById(orderId).getStatus());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, voucherId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tb_order_stock_sync WHERE order_id = ?", Integer.class, orderId));
            OrderStockSyncWorker worker = new OrderStockSyncWorker(tasks, liveRedis);
            worker.syncPending();
            worker.syncPending();

            assertEquals("1", liveRedis.opsForValue().get(keys.get(0)));
            assertFalse(liveRedis.opsForSet().isMember(keys.get(1), "7"));
            assertFalse(liveRedis.opsForHash().hasKey(keys.get(4), "7"));
            assertTrue(liveRedis.hasKey(keys.get(2)));
            assertFalse(liveRedis.hasKey(keys.get(3)));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT completed FROM tb_order_stock_sync WHERE order_id = ?", Integer.class, orderId));
        } finally {
            if (ownsKeys) {
                liveRedis.delete(keys);
            }
            connection.destroy();
        }
    }

    private void sendDuplicateDelayedClose(long orderId) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String topic = "cityai_it_" + suffix;
        String nameServer = System.getenv("ROCKETMQ_TEST_NAMESRV");
        OrderTimeoutListener listener = new OrderTimeoutListener(orders);
        CountDownLatch received = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
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
                        listener.onMessage(new String(message.getBody(), StandardCharsets.UTF_8));
                        received.countDown();
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                } catch (Throwable ex) {
                    failure.set(ex);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            });
            consumer.start();
            Message delayed = new Message(topic, "close",
                    String.valueOf(orderId).getBytes(StandardCharsets.UTF_8));
            delayed.setDelayTimeLevel(1);
            producer.send(delayed);
            producer.send(delayed);

            assertTrue(received.await(20, TimeUnit.SECONDS));
            assertNull(failure.get());
        } finally {
            consumer.shutdown();
            producer.shutdown();
        }
    }

    @Test
    void dispatchTaskPersistsUntilOrderIsCompleted() throws Exception {
        // Remove the default order-1 fixture so backlog assertions isolate this recovered task.
        jdbc.update("DELETE FROM tb_seckill_order_dispatch");
        String payload = "{\"id\":\"901\",\"content\":\"重投内容\"}";
        com.hmdp.entity.SeckillOrderDispatch initialTask = new com.hmdp.entity.SeckillOrderDispatch();
        initialTask.setOrderId(901L);
        initialTask.setUserId(7L);
        initialTask.setPayload(payload);
        initialTask.setTimeoutRetryTime(LocalDateTime.now().plusMinutes(15));
        assertEquals(1, dispatch.insertRecoveredTasks(List.of(initialTask)));
        var backlog = dispatch.getBacklogSnapshot(1);
        assertEquals(1, backlog.getOrderPendingCount());
        assertEquals(0, backlog.getOrderRepeatedFailureCount());
        assertEquals(0, backlog.getTimeoutPendingCount());
        assertEquals(0, jdbc.queryForObject(
                "SELECT timeout_sent FROM tb_seckill_order_dispatch WHERE order_id = 901", Integer.class));
        jdbc.update("UPDATE tb_seckill_order_dispatch SET timeout_retry_time = DATE_SUB(NOW(), INTERVAL 1 SECOND) "
                + "WHERE order_id = 901");
        backlog = dispatch.getBacklogSnapshot(1);
        assertEquals(1, backlog.getTimeoutPendingCount());
        assertEquals(0, backlog.getTimeoutRepeatedFailureCount());
        assertTrue(backlog.getTimeoutOldestPendingAgeSeconds() >= 1);
        assertEquals(1, dispatch.claimTimeoutPending("timeout-claim"));
        assertEquals(List.of(901L), dispatch.findTimeoutClaimed("timeout-claim"));
        assertEquals(1, dispatch.recordTimeoutAttempt(901L, "timeout-claim"));
        assertTrue(dispatch.findTimeoutClaimed("timeout-claim").isEmpty());
        assertEquals(0, dispatch.getBacklogSnapshot(1).getTimeoutPendingCount());
        jdbc.update("UPDATE tb_seckill_order_dispatch SET timeout_retry_time = DATE_SUB(NOW(), INTERVAL 1 SECOND) "
                + "WHERE order_id = 901");
        backlog = dispatch.getBacklogSnapshot(1);
        assertEquals(1, backlog.getTimeoutPendingCount());
        assertEquals(1, backlog.getTimeoutRepeatedFailureCount());
        assertEquals(1, dispatch.claimTimeoutPending("timeout-claim-2"));
        assertEquals(1, dispatch.markTimeoutSent(901L, "timeout-claim-2"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT timeout_sent FROM tb_seckill_order_dispatch WHERE order_id = 901", Integer.class));
        assertEquals(7L, dispatch.findPendingUserId(901L));
        assertTrue(dispatch.hasPending());
        jdbc.update("UPDATE tb_seckill_order_dispatch SET next_retry_time = DATE_SUB(NOW(), INTERVAL 1 SECOND) "
                + "WHERE order_id = 901");
        ExecutorService claimers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        int claimA;
        int claimB;
        try {
            Future<Integer> first = claimers.submit(() -> {
                start.await();
                return dispatch.claimPending("claim-a");
            });
            Future<Integer> second = claimers.submit(() -> {
                start.await();
                return dispatch.claimPending("claim-b");
            });
            start.countDown();
            claimA = first.get(5, TimeUnit.SECONDS);
            claimB = second.get(5, TimeUnit.SECONDS);
        } finally {
            claimers.shutdownNow();
        }
        assertEquals(1, claimA + claimB);
        String owner = claimA == 1 ? "claim-a" : "claim-b";
        String other = claimA == 1 ? "claim-b" : "claim-a";
        assertEquals(1, dispatch.findClaimed(owner).size());
        assertTrue(dispatch.findClaimed(other).isEmpty());
        assertEquals(1, dispatch.recordAttempt(901L, owner));
        assertEquals(1, dispatch.getBacklogSnapshot(1).getOrderRepeatedFailureCount());
        assertEquals(0, dispatch.recordAttempt(901L, other));
        assertTrue(dispatch.findClaimed(owner).isEmpty());
        assertTrue(dispatch.findClaimed(other).isEmpty());
        jdbc.update("UPDATE tb_seckill_order_dispatch SET next_retry_time = DATE_SUB(NOW(), INTERVAL 1 SECOND) "
                + "WHERE order_id = 901");
        assertEquals(1, dispatch.claimPending("claim-b"));
        assertEquals(1, dispatch.findClaimed("claim-b").size());
        assertEquals(1, dispatch.markCompleted(901L));
        assertNull(dispatch.findPendingUserId(901L));
        assertFalse(dispatch.hasPending());

        com.hmdp.entity.SeckillOrderDispatch recovered = new com.hmdp.entity.SeckillOrderDispatch();
        recovered.setOrderId(902L);
        recovered.setUserId(7L);
        recovered.setPayload("{\"id\":\"902\"}");
        recovered.setTimeoutRetryTime(LocalDateTime.now().plusMinutes(15));
        assertEquals(1, dispatch.insertRecoveredTasks(List.of(recovered)));
        assertEquals(902L, dispatch.findCompletionStatuses(List.of(902L)).get(0).getOrderId());
        assertEquals(0, dispatch.findCompletionStatuses(List.of(902L)).get(0).getCompleted());
        assertEquals(1, dispatch.markCompleted(902L));
    }

    @Test
    void timeoutMigrationMarksExistingRowsAsAlreadyScheduled() throws Exception {
        String migrationDatabase = "dispatch_timeout_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + migrationDatabase);
        try {
            String server = "jdbc:mysql://" + System.getenv("PAYMENT_TEST_MYSQL_HOST") + ":"
                    + System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PORT", "3306") + "/";
            String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
            DriverManagerDataSource migrationDataSource = new DriverManagerDataSource(
                    server + migrationDatabase + options,
                    System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_USER", "root"),
                    System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PASSWORD", ""));
            JdbcTemplate migrationJdbc = new JdbcTemplate(migrationDataSource);
            migrationJdbc.execute("CREATE TABLE tb_seckill_order_dispatch ("
                    + "order_id BIGINT NOT NULL, user_id BIGINT UNSIGNED NOT NULL, payload TEXT NOT NULL, "
                    + "completed TINYINT NOT NULL DEFAULT 0, attempts INT NOT NULL DEFAULT 0, "
                    + "next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "claim_token VARCHAR(36) NULL, claim_until TIMESTAMP NULL, "
                    + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (order_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            migrationJdbc.update("INSERT INTO tb_seckill_order_dispatch(order_id, user_id, payload) VALUES (1, 7, 'x')");

            new ResourceDatabasePopulator(new ClassPathResource("db/seckill-order-dispatch-timeout.sql"))
                    .execute(migrationDataSource);

            assertEquals(1, migrationJdbc.queryForObject(
                    "SELECT timeout_sent FROM tb_seckill_order_dispatch WHERE order_id = 1", Integer.class));
            assertEquals(5, migrationJdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                            + "AND table_name = 'tb_seckill_order_dispatch' AND column_name IN "
                            + "('timeout_sent', 'timeout_attempts', 'timeout_retry_time', "
                            + "'timeout_claim_token', 'timeout_claim_until')", Integer.class));
        } finally {
            admin.execute("DROP DATABASE " + migrationDatabase);
        }
    }

    @Test
    void leaseMigrationUpgradesAnExistingDispatchTable() throws Exception {
        String migrationDatabase = "dispatch_lease_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + migrationDatabase);
        try {
            String server = "jdbc:mysql://" + System.getenv("PAYMENT_TEST_MYSQL_HOST") + ":"
                    + System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PORT", "3306") + "/";
            String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
            DriverManagerDataSource migrationDataSource = new DriverManagerDataSource(
                    server + migrationDatabase + options,
                    System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_USER", "root"),
                    System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PASSWORD", ""));
            JdbcTemplate migrationJdbc = new JdbcTemplate(migrationDataSource);
            migrationJdbc.execute("CREATE TABLE tb_seckill_order_dispatch ("
                    + "order_id BIGINT NOT NULL, user_id BIGINT UNSIGNED NOT NULL, payload TEXT NOT NULL, "
                    + "completed TINYINT NOT NULL DEFAULT 0, attempts INT NOT NULL DEFAULT 0, "
                    + "next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (order_id), KEY idx_dispatch_retry (completed, next_retry_time, order_id)) "
                    + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            new ResourceDatabasePopulator(new ClassPathResource("db/seckill-order-dispatch-lease.sql"))
                    .execute(migrationDataSource);

            assertEquals(2, migrationJdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                            + "AND table_name = 'tb_seckill_order_dispatch' "
                            + "AND column_name IN ('claim_token', 'claim_until')", Integer.class));
            assertEquals(2, migrationJdbc.queryForObject(
                    "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                            + "WHERE table_schema = DATABASE() AND table_name = 'tb_seckill_order_dispatch' "
                            + "AND index_name IN ('idx_dispatch_claim', 'idx_dispatch_lease')", Integer.class));
        } finally {
            if (migrationDatabase.matches("dispatch_lease_test_[a-f0-9]{32}")) {
                admin.execute("DROP DATABASE " + migrationDatabase);
            }
        }
    }

    @Test
    void timeoutRecoveryIndexMigrationReplacesStatusOnlyIndex() throws Exception {
        String migrationDatabase = "timeout_recovery_index_test_"
                + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + migrationDatabase);
        try {
            String server = "jdbc:mysql://" + System.getenv("PAYMENT_TEST_MYSQL_HOST") + ":"
                    + System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PORT", "3306") + "/";
            String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
            DriverManagerDataSource migrationDataSource = new DriverManagerDataSource(
                    server + migrationDatabase + options,
                    System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_USER", "root"),
                    System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PASSWORD", ""));
            JdbcTemplate migrationJdbc = new JdbcTemplate(migrationDataSource);
            migrationJdbc.execute("CREATE TABLE tb_voucher_order ("
                    + "id BIGINT NOT NULL PRIMARY KEY, status TINYINT NOT NULL, "
                    + "create_time TIMESTAMP NOT NULL, KEY idx_voucher_order_status (status)) ENGINE=InnoDB");

            new ResourceDatabasePopulator(new ClassPathResource("db/order-timeout-recovery.sql"))
                    .execute(migrationDataSource);

            assertEquals("status,create_time", migrationJdbc.queryForObject(
                    "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) "
                            + "FROM information_schema.statistics WHERE table_schema = DATABASE() "
                            + "AND table_name = 'tb_voucher_order' "
                            + "AND index_name = 'idx_voucher_order_timeout_recovery'", String.class));
            assertEquals(0, migrationJdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                            + "AND table_name = 'tb_voucher_order' "
                            + "AND index_name = 'idx_voucher_order_status'", Integer.class));
        } finally {
            if (migrationDatabase.matches("timeout_recovery_index_test_[a-f0-9]{32}")) {
                admin.execute("DROP DATABASE " + migrationDatabase);
            }
        }
    }

    @Test
    void timeoutRetryTasksAreLeasedAndFencedAcrossMappers() throws Exception {
        OrderTimeoutRetryMapper first = timeoutRetries;
        org.apache.ibatis.session.SqlSession otherSession = sqlSessionFactory.openSession(true);
        OrderTimeoutRetryMapper second = otherSession.getMapper(OrderTimeoutRetryMapper.class);
        long orderId = 900_001L;
        jdbc.update("DELETE FROM tb_order_timeout_retry WHERE order_id = ?", orderId);
        first.scheduleRetry(orderId);
        assertEquals(0, first.claimPending("before-backoff"));
        jdbc.update("UPDATE tb_order_timeout_retry SET next_retry_time = CURRENT_TIMESTAMP "
                + "WHERE order_id = ?", orderId);
        java.util.concurrent.atomic.AtomicInteger claimed = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> claims = List.of(
                    pool.submit(() -> claimed.addAndGet(first.claimPending("timeout-a"))),
                    pool.submit(() -> claimed.addAndGet(second.claimPending("timeout-b"))));
            for (Future<?> claim : claims) claim.get(10, TimeUnit.SECONDS);
            assertEquals(1, claimed.get());
            String owner = first.findClaimed("timeout-a").isEmpty() ? "timeout-b" : "timeout-a";
            String stale = owner.equals("timeout-a") ? "timeout-b" : "timeout-a";
            assertEquals(0, first.retryLater(orderId, stale));
            assertEquals(1, first.retryLater(orderId, owner));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT attempts FROM tb_order_timeout_retry WHERE order_id = ?", Integer.class, orderId));
            jdbc.update("DELETE FROM tb_order_timeout_retry");
            first.scheduleRetry(orderId);
            jdbc.update("UPDATE tb_order_timeout_retry SET next_retry_time = CURRENT_TIMESTAMP "
                    + "WHERE order_id = ?", orderId);
            assertTrue(first.claimPending("active-lease-check") >= 1);
            assertTrue(first.scheduleRetry(orderId) > 0);
            second.claimPending("must-not-steal-active-lease");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tb_order_timeout_retry "
                            + "WHERE order_id = ? AND claim_token = 'active-lease-check'", Integer.class, orderId));
            assertEquals(1, first.retryLater(orderId, "active-lease-check"));
            assertEquals(0, first.claimPending("timeout-next"));
            jdbc.update("UPDATE tb_order_timeout_retry SET next_retry_time = CURRENT_TIMESTAMP "
                    + "WHERE order_id = ?", orderId);
            assertTrue(second.claimPending("timeout-next") >= 1);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_order_timeout_retry "
                    + "WHERE order_id = ? AND claim_token = 'timeout-next'", Integer.class, orderId));
            assertEquals(1, second.deleteClaimed(orderId, "timeout-next"));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tb_order_timeout_retry WHERE order_id = ?", Integer.class, orderId));
        } finally {
            pool.shutdownNow();
            otherSession.close();
            jdbc.update("DELETE FROM tb_order_timeout_retry WHERE order_id = ?", orderId);
        }
    }

    @Test
    void timeoutRetrySchemaMigrationIsIdempotent() throws Exception {
        String migrationDatabase = "timeout_retry_migration_"
                + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + migrationDatabase);
        try {
            String server = "jdbc:mysql://" + System.getenv("PAYMENT_TEST_MYSQL_HOST") + ":"
                    + System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PORT", "3306") + "/";
            String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
            DriverManagerDataSource migrationDataSource = new DriverManagerDataSource(
                    server + migrationDatabase + options,
                    System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_USER", "root"),
                    System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PASSWORD", ""));
            JdbcTemplate migrationJdbc = new JdbcTemplate(migrationDataSource);
            ResourceDatabasePopulator migration = new ResourceDatabasePopulator(
                    new ClassPathResource("db/order-timeout-retry.sql"));
            migration.execute(migrationDataSource);
            migration.execute(migrationDataSource);
            assertEquals("order_id,attempts,next_retry_time,claim_token,claim_until,create_time,update_time",
                    migrationJdbc.queryForObject("SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position) "
                            + "FROM information_schema.columns WHERE table_schema = DATABASE() "
                            + "AND table_name = 'tb_order_timeout_retry'", String.class));
        } finally {
            if (migrationDatabase.matches("timeout_retry_migration_[a-f0-9]{32}")) {
                admin.execute("DROP DATABASE " + migrationDatabase);
            }
        }
    }

    @AfterAll
    void dropOnlyOwnedDatabase() {
        if (admin != null && database != null && database.matches("payment_consistency_test_[a-f0-9]{32}")) {
            admin.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void duplicateCloseRestoresStockAndCreatesTaskOnce() {
        orders.closeTimeoutOrder(1L);
        orders.closeTimeoutOrder(1L);
        assertEquals(VoucherOrder.STATUS_CANCELLED, orders.getById(1L).getStatus());
        assertEquals(1, stock());
        assertEquals(1, taskCount());
        assertEquals(7L, tasks.findPending().get(0).getUserId());
        assertFalse(payments.simulatePaymentCallback(1L).getSuccess());
        verifyNoInteractions(redis);
    }

    @Test
    void differentOrderIdsForTheSameUserAndVoucherCreateOnlyOneSeckillOrder() {
        jdbc.update("UPDATE tb_seckill_voucher SET stock = 2 WHERE voucher_id = 10");
        VoucherOrder first = seckillOrder(200L, 8L, 10L);
        VoucherOrder duplicate = seckillOrder(201L, 8L, 10L);

        orders.createVoucherOrder(first);
        orders.createVoucherOrder(duplicate);

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE user_id = 8 AND voucher_id = 10 AND status <> 4",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 10", Integer.class));
        assertEquals(200L, jdbc.queryForObject(
                "SELECT active_order_id FROM tb_seckill_order_guard WHERE user_id = 8 AND voucher_id = 10",
                Long.class));
    }

    @Test
    void concurrentDifferentOrderIdsCannotReserveTheSameUserVoucherPair() throws Exception {
        jdbc.update("UPDATE tb_seckill_voucher SET stock = 2 WHERE voucher_id = 10");
        ExecutorService consumers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = consumers.submit(() -> {
                await(start);
                orders.createVoucherOrder(seckillOrder(210L, 9L, 10L));
            });
            Future<?> second = consumers.submit(() -> {
                await(start);
                orders.createVoucherOrder(seckillOrder(211L, 9L, 10L));
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tb_voucher_order WHERE user_id = 9 AND voucher_id = 10 AND status <> 4",
                    Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 10", Integer.class));
        } finally {
            consumers.shutdownNow();
        }
    }

    @Test
    void cancelledSeckillOrderReleasesReservationForRepurchase() {
        orders.closeTimeoutOrder(1L);
        orders.createVoucherOrder(seckillOrder(220L, 7L, 10L));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE user_id = 7 AND voucher_id = 10 AND status <> 4",
                Integer.class));
        assertEquals(220L, jdbc.queryForObject(
                "SELECT active_order_id FROM tb_seckill_order_guard WHERE user_id = 7 AND voucher_id = 10",
                Long.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 10", Integer.class));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ROCKETMQ_TEST_NAMESRV", matches = ".+")
    void realRocketMqDuplicateUserVoucherMessagesCreateOnlyOneOrder() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String topic = "cityai_guard_it_" + suffix;
        String nameServer = System.getenv("ROCKETMQ_TEST_NAMESRV");
        long voucherId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        long userId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) "
                + "VALUES (?, 2, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY))", voucherId);
        VoucherOrder first = seckillOrder(230L, userId, voucherId);
        VoucherOrder duplicate = seckillOrder(231L, userId, voucherId);
        SeckillVoucherListener listener = new SeckillVoucherListener(orders, new ObjectMapper());
        CountDownLatch received = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        DefaultMQProducer producer = new DefaultMQProducer("cityai-guard-producer-" + suffix);
        producer.setNamesrvAddr(nameServer);
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("cityai-guard-consumer-" + suffix);
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        try {
            producer.start();
            producer.send(new Message(topic, "warmup", "warmup".getBytes(StandardCharsets.UTF_8)));
            consumer.subscribe(topic, "create");
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                try {
                    for (var message : messages) {
                        listener.onMessage(new String(message.getBody(), StandardCharsets.UTF_8));
                        received.countDown();
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                } catch (Throwable ex) {
                    failure.set(ex);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            });
            consumer.start();
            ObjectMapper mapper = new ObjectMapper();
            producer.send(new Message(topic, "create", mapper.writeValueAsBytes(first)));
            producer.send(new Message(topic, "create", mapper.writeValueAsBytes(duplicate)));

            assertTrue(received.await(20, TimeUnit.SECONDS), "Duplicate order messages were not both consumed");
            assertNull(failure.get());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tb_voucher_order WHERE user_id = ? AND voucher_id = ? AND status <> 4",
                    Integer.class, userId, voucherId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, voucherId));
        } finally {
            consumer.shutdown();
            producer.shutdown();
        }
    }

    @Test
    void guardMigrationBackfillsActiveOrdersAndRejectsDuplicateHistory() {
        jdbc.execute("DROP TABLE tb_seckill_order_guard");
        jdbc.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id, status) VALUES (230, 13, 10, 1)");
        jdbc.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id, status) VALUES (231, 13, 10, 1)");
        jdbc.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id, status) VALUES (232, 14, 10, 4)");
        ClassPathResource migration = new ClassPathResource("db/seckill-order-guard.sql");

        assertThrows(ScriptStatementFailedException.class,
                () -> new ResourceDatabasePopulator(migration).execute(jdbc.getDataSource()));

        jdbc.update("DELETE FROM tb_voucher_order WHERE id IN (230, 231, 232)");
        new ResourceDatabasePopulator(migration).execute(jdbc.getDataSource());

        assertEquals(1L, jdbc.queryForObject(
                "SELECT active_order_id FROM tb_seckill_order_guard WHERE user_id = 7 AND voucher_id = 10",
                Long.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_seckill_order_guard WHERE user_id = 14 AND voucher_id = 10",
                Integer.class));
    }

    @Test
    void paidOrderCannotBeClosedOrHaveStockRestored() {
        assertTrue(payments.simulatePaymentCallback(1L).getSuccess());
        orders.closeTimeoutOrder(1L);
        assertEquals(VoucherOrder.STATUS_PAID, orders.getById(1L).getStatus());
        assertEquals(0, stock());
        assertEquals(0, taskCount());
    }

    @Test
    void failedTaskInsertRollsBackOrderAndDatabaseStock() {
        OrderStockSyncMapper failing = mock(OrderStockSyncMapper.class);
        when(failing.insertTask(anyLong(), anyLong(), anyLong())).thenThrow(new IllegalStateException("disk full"));
        ReflectionTestUtils.setField(target, "orderStockSyncMapper", failing);
        assertThrows(IllegalStateException.class, () -> orders.closeTimeoutOrder(1L));
        assertEquals(VoucherOrder.STATUS_UNPAID, orders.getById(1L).getStatus());
        assertEquals(0, stock());
        assertEquals(0, taskCount());
        verifyNoInteractions(redis);
    }

    @Test
    void failedStockRestoreRollsBackOrder() {
        jdbc.update("DELETE FROM tb_seckill_voucher");
        assertThrows(IllegalStateException.class, () -> orders.closeTimeoutOrder(1L));
        assertEquals(VoucherOrder.STATUS_UNPAID, orders.getById(1L).getStatus());
        assertEquals(0, taskCount());
    }

    @Test
    void uncommittedTaskCannotBeSeenByWorkerAndRollbackLeavesNothing() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                orders.closeTimeoutOrder(1L);
                try {
                    assertEquals(0, worker.submit(() -> tasks.findPending().size()).get(5, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                status.setRollbackOnly();
            });
            assertEquals(VoucherOrder.STATUS_UNPAID, orders.getById(1L).getStatus());
            assertEquals(0, stock());
            assertEquals(0, taskCount());
            verifyNoInteractions(redis);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void paymentCreationCallbackAndTimeoutRacePreserveOneTerminalState() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int i = 0; i < 20; i++) {
                long id = 100L + i;
                jdbc.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id, status) VALUES (?, ?, 10, 1)", id, id);
                reserve(id, 10L, id);
                CyclicBarrier start = new CyclicBarrier(3);
                Future<?> callback = executor.submit(() -> {
                    await(start);
                    payments.simulatePaymentCallback(id);
                });
                Future<?> close = executor.submit(() -> {
                    await(start);
                    orders.closeTimeoutOrder(id);
                });
                Future<?> create = executor.submit(() -> {
                    await(start);
                    payments.createPayment(id, VoucherOrder.PAY_TYPE_WECHAT);
                });
                callback.get(10, TimeUnit.SECONDS);
                close.get(10, TimeUnit.SECONDS);
                create.get(10, TimeUnit.SECONDS);
                VoucherOrder order = orders.getById(id);
                assertTrue(VoucherOrder.STATUS_PAID.equals(order.getStatus())
                        || VoucherOrder.STATUS_CANCELLED.equals(order.getStatus()));
                assertEquals(VoucherOrder.STATUS_PAID.equals(order.getStatus()), order.getPayTime() != null);
            }
            int cancelled = jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE status = 4", Integer.class);
            assertEquals(cancelled, stock());
            assertEquals(cancelled, taskCount());
            verifyNoInteractions(redis);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void missingOrderRemainsRetryable() {
        assertThrows(IllegalStateException.class, () -> orders.closeTimeoutOrder(999L));
        assertEquals(0, taskCount());
    }

    @Test
    void failedTaskIsDelayedAndLateFailureCannotReopenCompletedTask() {
        orders.closeTimeoutOrder(1L);
        assertEquals(1, tasks.claimPending("first-claim"));
        assertEquals(1, tasks.recordFailure(1L, "first-claim"));
        assertTrue(tasks.findPending().isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM tb_order_stock_sync WHERE order_id = 1", Integer.class));
        jdbc.update("UPDATE tb_order_stock_sync SET next_retry_time = CURRENT_TIMESTAMP WHERE order_id = 1");
        assertEquals(1, tasks.claimPending("second-claim"));
        assertEquals(1, tasks.markCompleted(1L, "second-claim"));
        assertEquals(0, tasks.recordFailure(1L, "first-claim"));
        assertTrue(tasks.findPending().isEmpty());
    }

    @Test
    void stockSyncTasksHaveSingleConcurrentOwnerAndCanBeReclaimedAfterLeaseExpiry() throws Exception {
        orders.closeTimeoutOrder(1L);
        assertEquals(1, tasks.insertTask(2L, 10L, 8L));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<Long>> firstClaim = executor.submit(() -> {
                start.await();
                tasks.claimPending("claim-a");
                return tasks.findClaimed("claim-a").stream().map(OrderStockSync::getOrderId).toList();
            });
            Future<List<Long>> secondClaim = executor.submit(() -> {
                start.await();
                tasks.claimPending("claim-b");
                return tasks.findClaimed("claim-b").stream().map(OrderStockSync::getOrderId).toList();
            });
            start.countDown();
            List<Long> first = firstClaim.get(5, TimeUnit.SECONDS);
            List<Long> second = secondClaim.get(5, TimeUnit.SECONDS);
            assertTrue(first.isEmpty() || second.isEmpty());
            List<Long> owned = first.isEmpty() ? second : first;
            assertEquals(2, owned.size());

            Long reclaimedId = owned.get(0);
            String oldToken = first.isEmpty() ? "claim-b" : "claim-a";
            jdbc.update("UPDATE tb_order_stock_sync SET claim_until = DATE_SUB(CURRENT_TIMESTAMP, "
                    + "INTERVAL 1 SECOND) WHERE order_id = ?", reclaimedId);
            assertEquals(1, tasks.claimPending("takeover"));
            assertEquals(List.of(reclaimedId), tasks.findClaimed("takeover").stream()
                    .map(OrderStockSync::getOrderId).toList());
            assertEquals(0, tasks.markCompleted(reclaimedId, oldToken));
            assertEquals(1, tasks.markCompleted(reclaimedId, "takeover"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stockSyncLeaseMigrationUpgradesExistingTaskTable() throws Exception {
        String migrationDatabase = "stock_sync_lease_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + migrationDatabase);
        try {
            String server = "jdbc:mysql://" + System.getenv("PAYMENT_TEST_MYSQL_HOST") + ":"
                    + System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PORT", "3306") + "/";
            String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
            DriverManagerDataSource migrationDataSource = new DriverManagerDataSource(
                    server + migrationDatabase + options,
                    System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_USER", "root"),
                    System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PASSWORD", ""));
            JdbcTemplate migrationJdbc = new JdbcTemplate(migrationDataSource);
            migrationJdbc.execute("CREATE TABLE tb_order_stock_sync ("
                    + "order_id BIGINT NOT NULL, voucher_id BIGINT UNSIGNED NOT NULL, "
                    + "user_id BIGINT UNSIGNED NOT NULL, completed TINYINT NOT NULL DEFAULT 0, "
                    + "attempts INT NOT NULL DEFAULT 0, next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (order_id), KEY idx_pending_retry (completed, next_retry_time, order_id)) "
                    + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            new ResourceDatabasePopulator(new ClassPathResource("db/order-stock-sync-lease.sql"))
                    .execute(migrationDataSource);

            assertEquals(2, migrationJdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                            + "AND table_name = 'tb_order_stock_sync' "
                            + "AND column_name IN ('claim_token', 'claim_until')", Integer.class));
            assertEquals("completed,next_retry_time,claim_until,order_id", migrationJdbc.queryForObject(
                    "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) "
                            + "FROM information_schema.statistics WHERE table_schema = DATABASE() "
                            + "AND table_name = 'tb_order_stock_sync' AND index_name = 'idx_pending_retry'",
                    String.class));
        } finally {
            if (migrationDatabase.matches("stock_sync_lease_test_[a-f0-9]{32}")) {
                admin.execute("DROP DATABASE " + migrationDatabase);
            }
        }
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 10", Integer.class);
    }

    private void reserve(Long userId, Long voucherId, Long orderId) {
        assertEquals(1, orderGuard.ensureReservationRow(userId, voucherId));
        assertEquals(1, orderGuard.reserve(userId, voucherId, orderId));
    }

    private VoucherOrder seckillOrder(Long orderId, Long userId, Long voucherId) {
        return new VoucherOrder().setId(orderId).setUserId(userId).setVoucherId(voucherId)
                .setStatus(VoucherOrder.STATUS_UNPAID);
    }

    private int taskCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_order_stock_sync", Integer.class);
    }

    private RedissonClient recoveryLockClient() {
        RedissonClient redisClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisClient.getLock("lock:order:timeout-recovery")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        return redisClient;
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent consumer start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
