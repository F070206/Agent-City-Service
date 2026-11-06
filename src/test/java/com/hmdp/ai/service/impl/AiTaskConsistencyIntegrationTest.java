package com.hmdp.ai.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.ai.entity.AiMessage;
import com.hmdp.ai.entity.AiConversation;
import com.hmdp.ai.entity.AiTask;
import com.hmdp.ai.mapper.AiTaskMapper;
import com.hmdp.ai.memory.ConversationMemoryService;
import com.hmdp.ai.agent.loop.AgentLoopExecutor;
import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.rag.indexer.AiVectorIndexService;
import com.hmdp.ai.service.AiAgentService;
import com.hmdp.dto.CreateChatMessageRequest;
import com.hmdp.dto.UserDTO;
import com.hmdp.component.ChatMessageCreatedEventListener;
import com.hmdp.component.ConversationExecutionCoordinator;
import com.hmdp.ai.agent.prompt.AgentLoopPromptProvider;
import com.hmdp.ai.service.impl.AiTaskLeaseKeeper;
import com.hmdp.ai.service.impl.AiTaskRecoveryWorker;
import com.hmdp.service.ChatSseService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Creates and removes only a uniquely named test database. */
@EnabledIfEnvironmentVariable(named = "AI_TASK_TEST_MYSQL_HOST", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiTaskConsistencyIntegrationTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String database;
    private AiTaskMapper tasks;
    private AiTaskMapper competingTasks;
    private AiTaskCompletionService completion;

    @BeforeAll
    void setUp() throws Exception {
        String host = System.getenv("AI_TASK_TEST_MYSQL_HOST");
        String port = System.getenv().getOrDefault("AI_TASK_TEST_MYSQL_PORT", "3306");
        String user = System.getenv().getOrDefault("AI_TASK_TEST_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("AI_TASK_TEST_MYSQL_PASSWORD", "");
        String server = "jdbc:mysql://" + host + ":" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        admin = new JdbcTemplate(new DriverManagerDataSource(server + options, user, password));
        database = "ai_task_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(server + database + options, user, password);
        jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/ai-agent.sql")).execute(dataSource);

        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(AiTaskMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(config);
        tasks = new SqlSessionTemplate(factory.getObject()).getMapper(AiTaskMapper.class);
        competingTasks = new SqlSessionTemplate(factory.getObject()).getMapper(AiTaskMapper.class);

        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.appendAssistantMessage(anyLong(), anyString())).thenAnswer(invocation -> {
            Long conversationId = invocation.getArgument(0);
            String reply = invocation.getArgument(1);
            jdbc.update("INSERT INTO tb_ai_message (conversation_id, role, content) VALUES (?, 'assistant', ?)",
                    conversationId, reply);
            return new AiMessage().setConversationId(conversationId).setContent(reply);
        });
        doAnswer(invocation -> {
            Long conversationId = invocation.getArgument(0);
            String scene = invocation.getArgument(1);
            String prompt = invocation.getArgument(2);
            jdbc.update("UPDATE tb_ai_conversation SET title = ?, scene = ? WHERE id = ?",
                    prompt, scene, conversationId);
            return null;
        }).when(memory).updateConversation(anyLong(), any(), anyString());
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(new AiTaskCompletionService(memory, tasks));
        proxy.addAdvice(interceptor);
        completion = (AiTaskCompletionService) proxy.getProxy();
    }

    @AfterAll
    void cleanUp() {
        if (admin != null && database != null && database.matches("ai_task_test_[a-f0-9]{32}")) {
            admin.execute("DROP DATABASE " + database);
        }
    }

    @BeforeEach
    void resetTaskTables() {
        jdbc.update("DELETE FROM tb_ai_message");
        jdbc.update("DELETE FROM tb_ai_task");
    }

    @Test
    void activeWorkerRenewsLeaseWithoutAllowingAnotherClaim() {
        AiTask task = new AiTask();
        task.setMessageId(404L);
        task.setConversationId(4041L);
        task.setUserId(301L);
        task.setContent("附近餐厅");
        assertEquals(1, tasks.insertTask(task));
        assertEquals(1, tasks.claim(404L, "active"));
        jdbc.update("UPDATE tb_ai_task SET lease_until = DATE_ADD(NOW(), INTERVAL 1 SECOND) WHERE message_id = 404");

        assertEquals(0, tasks.renewLease(404L, "other"));
        assertEquals(1, tasks.renewLease(404L, "active"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_ai_task "
                + "WHERE message_id = 404 AND lease_until > DATE_ADD(NOW(), INTERVAL 4 MINUTE)", Integer.class));
        assertEquals(0, tasks.claim(404L, "other"));
    }

    @Test
    void laterConversationTaskWaitsUntilEarlierMessageCompletes() {
        AiTask earlier = new AiTask();
        earlier.setMessageId(707L);
        earlier.setConversationId(7071L);
        earlier.setUserId(301L);
        earlier.setContent("第一条消息");
        AiTask later = new AiTask();
        later.setMessageId(708L);
        later.setConversationId(7071L);
        later.setUserId(301L);
        later.setContent("第二条消息");
        assertEquals(1, tasks.insertTask(earlier));
        assertEquals(1, tasks.insertTask(later));

        assertEquals(0, tasks.claim(708L, "later-first"));
        assertEquals(1, tasks.claim(707L, "earlier-first"));
        assertEquals(0, tasks.claim(708L, "later-again"));
        assertEquals(1, tasks.complete(707L, "earlier-first"));

        AiTask next = tasks.findNextPending(7071L);
        assertNotNull(next);
        assertEquals(708L, next.getMessageId());
        assertEquals(1, tasks.claim(708L, "later-after-earlier"));
    }

    @Test
    void concurrentInstancesCanClaimTheSameTaskOnlyOnce() throws Exception {
        AiTask task = new AiTask();
        task.setMessageId(505L);
        task.setConversationId(5051L);
        task.setUserId(301L);
        task.setContent("附近餐厅");
        assertEquals(1, tasks.insertTask(task));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return tasks.claim(505L, "worker-one");
            });
            Future<Integer> second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return competingTasks.claim(505L, "worker-two");
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT attempts FROM tb_ai_task WHERE message_id = 505", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tb_ai_task WHERE message_id = 505 AND claim_token IN ('worker-one', 'worker-two')",
                    Integer.class));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void recoveryWorkerReclaimsExpiredLeaseAndCompletesWithoutModelCall() {
        AiTask task = new AiTask();
        task.setMessageId(606L);
        task.setConversationId(6061L);
        task.setUserId(301L);
        task.setContent("恢复任务");
        assertEquals(1, tasks.insertTask(task));
        assertEquals(1, tasks.claim(606L, "exited-worker"));
        jdbc.update("UPDATE tb_ai_task SET lease_until = DATE_SUB(NOW(), INTERVAL 1 SECOND) WHERE message_id = 606");

        ConversationMemoryService workerMemory = mock(ConversationMemoryService.class);
        when(workerMemory.loadRecentMessagesBefore(eq(6061L), eq(606L), anyInt()))
                .thenThrow(new IllegalStateException("stop before model call"));
        AiTaskLeaseKeeper leaseKeeper = mock(AiTaskLeaseKeeper.class);
        when(leaseKeeper.start(eq(606L), anyString())).thenReturn(mock(AiTaskLease.class));
        AgentLoopExecutor agent = mock(AgentLoopExecutor.class);
        ChatMessageCreatedEventListener listener = new ChatMessageCreatedEventListener(
                agent, workerMemory, new AgentLoopPromptProvider(),
                new AiAgentProperties(), mock(ChatSseService.class),
                new ConversationExecutionCoordinator(), mock(ObjectProvider.class));
        ReflectionTestUtils.setField(listener, "aiAgentTaskExecutor", (Executor) Runnable::run);
        ReflectionTestUtils.setField(listener, "aiTaskMapper", tasks);
        ReflectionTestUtils.setField(listener, "completionService", completion);
        ReflectionTestUtils.setField(listener, "leaseKeeper", leaseKeeper);

        new AiTaskRecoveryWorker(tasks, listener).recover();

        assertEquals(2, jdbc.queryForObject(
                "SELECT status FROM tb_ai_task WHERE message_id = 606", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT attempts FROM tb_ai_task WHERE message_id = 606", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_ai_task WHERE message_id = 606 AND claim_token = 'exited-worker'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_ai_message WHERE conversation_id = 6061 AND role = 'assistant'",
                Integer.class));
        verifyNoInteractions(agent);
    }

    @Test
    void staleWorkerCannotCompleteAfterLeaseWasReclaimed() {
        AiTask task = new AiTask();
        task.setMessageId(303L);
        task.setConversationId(3031L);
        task.setUserId(301L);
        task.setContent("附近餐厅");
        assertEquals(1, tasks.insertTask(task));
        assertEquals(1, tasks.claim(303L, "first"));
        jdbc.update("UPDATE tb_ai_task SET lease_until = DATE_SUB(NOW(), INTERVAL 1 SECOND) WHERE message_id = 303");
        assertEquals(1, tasks.claim(303L, "second"));
        assertEquals(0, tasks.renewLease(303L, "first"));
        assertThrows(IllegalStateException.class,
                () -> completion.complete(303L, "first", 3031L, "旧执行结果", null, "旧输入"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_ai_message WHERE role = 'assistant' AND content = '旧执行结果'", Integer.class));
    }

    @Test
    void taskInsertFailureRollsBackUserMessage() {
        jdbc.update("INSERT INTO tb_ai_task (message_id, conversation_id, user_id, content) "
                + "VALUES (202, 201, 301, 'existing')");
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.getOrCreateConversation(eq(301L), any(), anyString(), anyString()))
                .thenReturn(new AiConversation().setId(201L).setUserId(301L));
        when(memory.appendUserMessage(201L, "附近餐厅")).thenAnswer(invocation -> {
            jdbc.update("INSERT INTO tb_ai_message (id, conversation_id, role, content) "
                    + "VALUES (202, 201, 'user', '附近餐厅')");
            return new AiMessage().setId(202L).setConversationId(201L).setContent("附近餐厅");
        });
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AiAgentServiceImpl target = new AiAgentServiceImpl(memory, mock(AgentLoopExecutor.class),
                mock(AiVectorIndexService.class), new AiAgentProperties(), publisher,
                mock(ChatSseService.class));
        ReflectionTestUtils.setField(target, "aiTaskMapper", tasks);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(jdbc.getDataSource()));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(interceptor);
        AiAgentService service = (AiAgentService) proxy.getProxy();
        UserDTO user = new UserDTO();
        user.setId(301L);
        UserHolder.saveUser(user);
        try {
            CreateChatMessageRequest request = new CreateChatMessageRequest();
            request.setConversationId(201L);
            request.setContent("附近餐厅");
            assertThrows(RuntimeException.class, () -> service.createChatMessage(request));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tb_ai_message WHERE id = 202", Integer.class));
            verifyNoInteractions(publisher);
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void expiredClaimCanRecoverAndCompletionIsTransactional() {
        AiTask task = new AiTask();
        task.setMessageId(101L);
        task.setConversationId(1011L);
        task.setUserId(301L);
        task.setContent("附近餐厅");
        task.setX(120.1);
        task.setY(30.2);
        assertEquals(1, tasks.insertTask(task));
        assertTrue(tasks.findRecoverable().isEmpty());
        jdbc.update("UPDATE tb_ai_task SET created_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE message_id = 101");
        assertEquals(1, tasks.findRecoverable().size());
        assertEquals(120.1, tasks.findRecoverable().get(0).getX());
        assertEquals(1, tasks.claim(101L, "first"));
        assertEquals(0, tasks.claim(101L, "duplicate"));
        assertTrue(tasks.findRecoverable().isEmpty());
        jdbc.update("UPDATE tb_ai_task SET lease_until = DATE_SUB(NOW(), INTERVAL 1 SECOND) WHERE message_id = 101");
        assertEquals(1, tasks.findRecoverable().size());
        assertEquals(1, tasks.claim(101L, "second"));
        completion.complete(101L, "second", 1011L, "回答", null, "输入");
        assertEquals(2, jdbc.queryForObject("SELECT status FROM tb_ai_task WHERE message_id = 101", Integer.class));
        assertEquals(1, assistantCount());
        assertThrows(IllegalStateException.class,
                () -> completion.complete(101L, "second", 1011L, "重复回答", null, "输入"));
        assertEquals(1, assistantCount());
    }

    @Test
    void conversationSummaryFailureRollsBackAssistantAndTaskCompletion() {
        jdbc.update("INSERT INTO tb_ai_conversation (id, user_id, title, scene, status) "
                + "VALUES (9091, 301, '旧标题', 'general', 1)");
        AiTask task = new AiTask();
        task.setMessageId(909L);
        task.setConversationId(9091L);
        task.setUserId(301L);
        task.setContent("新问题");
        assertEquals(1, tasks.insertTask(task));
        assertEquals(1, tasks.claim(909L, "summary-failure"));
        ConversationMemoryService failingMemory = mock(ConversationMemoryService.class);
        when(failingMemory.appendAssistantMessage(9091L, "最终回答")).thenAnswer(invocation -> {
            jdbc.update("INSERT INTO tb_ai_message (conversation_id, role, content) "
                    + "VALUES (9091, 'assistant', '最终回答')");
            return new AiMessage().setConversationId(9091L).setContent("最终回答");
        });
        doAnswer(invocation -> {
            jdbc.update("UPDATE tb_ai_conversation SET title = '新问题', scene = 'shop' WHERE id = 9091");
            throw new IllegalStateException("summary unavailable");
        }).when(failingMemory).updateConversation(eq(9091L), eq("shop"), eq("新问题"));
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(jdbc.getDataSource()));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(new AiTaskCompletionService(failingMemory, tasks));
        proxy.addAdvice(interceptor);
        AiTaskCompletionService transactionalCompletion = (AiTaskCompletionService) proxy.getProxy();

        assertThrows(IllegalStateException.class, () -> transactionalCompletion.complete(
                909L, "summary-failure", 9091L, "最终回答", "shop", "新问题"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_ai_message WHERE conversation_id = 9091 AND role = 'assistant'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM tb_ai_task WHERE message_id = 909", Integer.class));
        assertEquals("旧标题", jdbc.queryForObject(
                "SELECT title FROM tb_ai_conversation WHERE id = 9091", String.class));
    }

    @Test
    void blockedConversationTasksDoNotCrowdOutRecoverableWorkFromOtherConversations() {
        AiTask active = new AiTask();
        active.setMessageId(1000L);
        active.setConversationId(5001L);
        active.setUserId(301L);
        active.setContent("active conversation request");
        assertEquals(1, tasks.insertTask(active));
        assertEquals(1, tasks.claim(1000L, "active-claim"));

        for (long messageId = 1001L; messageId <= 1100L; messageId++) {
            AiTask blocked = new AiTask();
            blocked.setMessageId(messageId);
            blocked.setConversationId(5001L);
            blocked.setUserId(301L);
            blocked.setContent("blocked request " + messageId);
            assertEquals(1, tasks.insertTask(blocked));
        }
        jdbc.update("UPDATE tb_ai_task SET created_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE) "
                + "WHERE conversation_id = 5001 AND status = 0");

        AiTask otherConversation = new AiTask();
        otherConversation.setMessageId(2000L);
        otherConversation.setConversationId(5002L);
        otherConversation.setUserId(302L);
        otherConversation.setContent("independent request");
        assertEquals(1, tasks.insertTask(otherConversation));
        jdbc.update("UPDATE tb_ai_task SET created_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE) "
                + "WHERE message_id = 2000");

        List<AiTask> recoverable = tasks.findRecoverable();

        assertEquals(1, recoverable.size());
        assertEquals(2000L, recoverable.get(0).getMessageId());
    }

    @Test
    void recoveryCandidatesFromPendingAndExpiredLeaseBranchesRemainOrdered() {
        AiTask pending = new AiTask();
        pending.setMessageId(505L);
        pending.setConversationId(7001L);
        pending.setUserId(301L);
        pending.setContent("aged pending request");
        assertEquals(1, tasks.insertTask(pending));
        jdbc.update("UPDATE tb_ai_task SET created_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE) "
                + "WHERE message_id = 505");

        AiTask expired = new AiTask();
        expired.setMessageId(404L);
        expired.setConversationId(7002L);
        expired.setUserId(302L);
        expired.setContent("expired lease request");
        assertEquals(1, tasks.insertTask(expired));
        assertEquals(1, tasks.claim(404L, "expired-claim"));
        jdbc.update("UPDATE tb_ai_task SET lease_until = DATE_SUB(NOW(), INTERVAL 1 SECOND) "
                + "WHERE message_id = 404");

        AiTask recent = new AiTask();
        recent.setMessageId(606L);
        recent.setConversationId(7003L);
        recent.setUserId(303L);
        recent.setContent("recent pending request");
        assertEquals(1, tasks.insertTask(recent));

        List<Long> messageIds = tasks.findRecoverable().stream().map(AiTask::getMessageId).toList();

        assertEquals(List.of(404L, 505L), messageIds);
    }

    @Test
    void pendingRecoveryIndexMigrationAddsIndexToExistingTaskTable() {
        jdbc.execute("ALTER TABLE tb_ai_task DROP INDEX idx_ai_task_pending_recovery");
        new ResourceDatabasePopulator(new ClassPathResource("db/ai-task-pending-recovery-index.sql"))
                .execute(jdbc.getDataSource());

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'tb_ai_task' "
                + "AND index_name = 'idx_ai_task_pending_recovery'", Integer.class));
    }

    private int assistantCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_ai_message WHERE role = 'assistant'", Integer.class);
    }
}
