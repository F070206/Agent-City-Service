package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.dto.SeckillVoucherLookup;
import com.hmdp.mapper.SeckillVoucherMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_MYSQL_HOST", matches = ".+")
class SeckillVoucherMapperIntegrationTest {
    private static JdbcTemplate admin;
    private static String database;
    private static SeckillVoucherMapper mapper;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() throws Exception {
        String host = System.getenv("PAYMENT_TEST_MYSQL_HOST");
        String port = System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PORT", "3306");
        String user = System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PASSWORD", "");
        String server = "jdbc:mysql://" + host + ":" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        admin = new JdbcTemplate(new DriverManagerDataSource(server + options, user, password));
        database = "seckill_mapper_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        databaseCreated = true;
        DataSource dataSource = new DriverManagerDataSource(server + database + options, user, password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE tb_voucher (id BIGINT PRIMARY KEY, type TINYINT NOT NULL, status TINYINT NOT NULL)");
        jdbc.execute("CREATE TABLE tb_seckill_voucher (voucher_id BIGINT PRIMARY KEY, stock INT NOT NULL, "
                + "create_time TIMESTAMP NULL, begin_time TIMESTAMP NULL, end_time TIMESTAMP NULL, "
                + "update_time TIMESTAMP NULL, activity_version BIGINT NOT NULL DEFAULT 0)");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SeckillVoucherMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionFactory sessionFactory = factory.getObject();
        mapper = new SqlSessionTemplate(sessionFactory).getMapper(SeckillVoucherMapper.class);
    }

    @AfterAll
    static void cleanUp() {
        if (databaseCreated && admin != null && database != null
                && database.matches("seckill_mapper_test_[a-f0-9]{32}")) {
            admin.execute("DROP DATABASE " + database);
        }
    }

    private static boolean databaseCreated;

    @Test
    void lookupPreservesActivityAndVoucherStateForAdmissionChecks() {
        jdbc.update("INSERT INTO tb_voucher (id, type, status) "
                + "VALUES (101, 1, 1), "
                + "(102, 0, 1), (103, 1, 2)");
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) "
                + "VALUES (101, 5, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY)), "
                + "(102, 5, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY)), "
                + "(103, 5, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY)), "
                + "(104, 5, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY))");

        SeckillVoucherLookup available = mapper.findForSeckill(101L);

        assertNotNull(available);
        assertEquals(101L, available.getVoucherId());
        assertEquals(5, available.getStock());
        assertEquals(0L, available.getVersion());
        assertNotNull(available.getBeginTime());
        assertNotNull(available.getEndTime());
        assertEquals(1, mapper.updateActivity(101L,
                java.time.LocalDateTime.now().plusHours(1), java.time.LocalDateTime.now().plusDays(2)));
        assertEquals(1L, mapper.findForSeckill(101L).getVersion());
        SeckillVoucherLookup normalVoucher = mapper.findForSeckill(102L);
        assertNotNull(normalVoucher);
        assertEquals(0, normalVoucher.getVoucherType());
        assertEquals(1, normalVoucher.getVoucherStatus());

        SeckillVoucherLookup offlineVoucher = mapper.findForSeckill(103L);
        assertNotNull(offlineVoucher);
        assertEquals(1, offlineVoucher.getVoucherType());
        assertEquals(2, offlineVoucher.getVoucherStatus());

        SeckillVoucherLookup orphanActivity = mapper.findForSeckill(104L);
        assertNotNull(orphanActivity);
        assertNull(orphanActivity.getVoucherStatus());
        assertNull(orphanActivity.getVoucherType());
        assertNull(mapper.findForSeckill(105L));
    }
}
