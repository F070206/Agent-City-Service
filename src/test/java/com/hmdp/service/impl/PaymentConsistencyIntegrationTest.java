package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.OrderStockSyncMapper;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** 使用临时独立数据库；不启动应用，也不访问现有业务表或 MQ。 */
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

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(VoucherOrderMapper.class);
        configuration.addMapper(SeckillVoucherMapper.class);
        configuration.addMapper(OrderStockSyncMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        tasks = session.getMapper(OrderStockSyncMapper.class);
        SeckillVoucherServiceImpl stock = new SeckillVoucherServiceImpl();
        ReflectionTestUtils.setField(stock, "baseMapper", session.getMapper(SeckillVoucherMapper.class));
        target = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(target, "baseMapper", session.getMapper(VoucherOrderMapper.class));
        ReflectionTestUtils.setField(target, "seckillVoucherService", stock);
        redis = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(target, "stringRedisTemplate", redis);
        transactions = new DataSourceTransactionManager(dataSource);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(interceptor);
        orders = (IVoucherOrderService) proxy.getProxy();
        payments = new PaymentServiceImpl(orders, redis);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM tb_order_stock_sync");
        jdbc.update("DELETE FROM tb_voucher_order");
        jdbc.update("DELETE FROM tb_seckill_voucher");
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) "
                + "VALUES (10, 0, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY))");
        jdbc.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id, status) VALUES (1, 7, 10, 1)");
        ReflectionTestUtils.setField(target, "orderStockSyncMapper", tasks);
        clearInvocations(redis);
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
        assertEquals(1, tasks.recordFailure(1L));
        assertTrue(tasks.findPending().isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM tb_order_stock_sync WHERE order_id = 1", Integer.class));
        assertEquals(1, tasks.markCompleted(1L));
        assertEquals(0, tasks.recordFailure(1L));
        assertTrue(tasks.findPending().isEmpty());
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 10", Integer.class);
    }

    private int taskCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_order_stock_sync", Integer.class);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
