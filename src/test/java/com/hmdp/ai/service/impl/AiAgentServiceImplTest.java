package com.hmdp.ai.service.impl;

import com.hmdp.ai.agent.loop.AgentLoopExecutor;
import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.entity.AiConversation;
import com.hmdp.ai.entity.AiMessage;
import com.hmdp.ai.memory.ConversationMemoryService;
import com.hmdp.ai.mapper.AiTaskMapper;
import com.hmdp.ai.entity.AiTask;
import com.hmdp.ai.rag.indexer.AiVectorIndexService;
import com.hmdp.dto.CreateChatMessageRequest;
import com.hmdp.dto.CreateChatMessageResponse;
import com.hmdp.dto.UserDTO;
import com.hmdp.event.ChatMessageCreatedEvent;
import com.hmdp.service.ChatSseService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.hmdp.ai.dto.AgentRecommendRequest;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class AiAgentServiceImplTest {

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void createChatMessageShouldPersistUserMessageAndPublishEvent() {
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AiAgentServiceImpl service = new AiAgentServiceImpl(
                conversationMemoryService,
                mock(AgentLoopExecutor.class),
                mock(AiVectorIndexService.class),
                new AiAgentProperties(),
                publisher,
                mock(ChatSseService.class)
        );
        AiTaskMapper tasks = mock(AiTaskMapper.class);
        when(tasks.insertTask(org.mockito.ArgumentMatchers.any(AiTask.class))).thenReturn(1);
        ReflectionTestUtils.setField(service, "aiTaskMapper", tasks);

        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);

        AiConversation conversation = new AiConversation().setId(100L).setUserId(1L);
        AiMessage message = new AiMessage().setId(200L).setConversationId(100L).setContent("你好");
        when(conversationMemoryService.getOrCreateConversation(1L, null, "你好", "MULTI_AGENT"))
                .thenReturn(conversation);
        when(conversationMemoryService.appendUserMessage(100L, "你好")).thenReturn(message);

        CreateChatMessageRequest request = new CreateChatMessageRequest();
        request.setContent("你好");
        request.setX(120.1);
        request.setY(30.2);

        CreateChatMessageResponse response = service.createChatMessage(request);

        assertEquals(100L, response.getConversationId());
        assertEquals(200L, response.getChatMessageId());
        verify(conversationMemoryService).appendUserMessage(100L, "你好");
        ArgumentCaptor<AiTask> taskCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(tasks).insertTask(taskCaptor.capture());
        assertEquals(200L, taskCaptor.getValue().getMessageId());
        assertEquals(120.1, taskCaptor.getValue().getX());

        ArgumentCaptor<ChatMessageCreatedEvent> captor = ArgumentCaptor.forClass(ChatMessageCreatedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        ChatMessageCreatedEvent event = captor.getValue();
        assertEquals(1L, event.getUserId());
        assertEquals(100L, event.getConversationId());
        assertEquals(200L, event.getChatMessageId());
        assertEquals("你好", event.getContent());
        assertEquals(120.1, event.getX());
        assertEquals(30.2, event.getY());
    }

    @Test
    void createChatMessageRejectsPartialCoordinatesBeforePersistence() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AiAgentServiceImpl service = new AiAgentServiceImpl(memory,
                mock(AgentLoopExecutor.class), mock(AiVectorIndexService.class),
                new AiAgentProperties(), publisher, mock(ChatSseService.class));
        ReflectionTestUtils.setField(service, "aiTaskMapper", mock(AiTaskMapper.class));
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
        CreateChatMessageRequest request = new CreateChatMessageRequest();
        request.setContent("附近店铺");
        request.setX(121.0);

        assertThrows(IllegalArgumentException.class, () -> service.createChatMessage(request));

        verifyNoInteractions(memory, publisher);
    }

    @Test
    void recommendRejectsOutOfRangeCoordinatesBeforeAgentExecution() {
        AgentLoopExecutor agent = mock(AgentLoopExecutor.class);
        AiAgentServiceImpl service = new AiAgentServiceImpl(mock(ConversationMemoryService.class), agent,
                mock(AiVectorIndexService.class), new AiAgentProperties(),
                mock(ApplicationEventPublisher.class), mock(ChatSseService.class));
        AgentRecommendRequest request = new AgentRecommendRequest();
        request.setQuery("附近餐厅");
        request.setX(181.0);
        request.setY(31.0);

        assertThrows(IllegalArgumentException.class, () -> service.recommend(request));

        verifyNoInteractions(agent);
    }

    @Test
    void createChatMessageRejectsPromptTooLongBeforePersistence() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AiAgentServiceImpl service = new AiAgentServiceImpl(memory,
                mock(AgentLoopExecutor.class), mock(AiVectorIndexService.class),
                new AiAgentProperties(), publisher, mock(ChatSseService.class));
        ReflectionTestUtils.setField(service, "aiTaskMapper", mock(AiTaskMapper.class));
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
        CreateChatMessageRequest request = new CreateChatMessageRequest();
        request.setContent("字".repeat(8_001));

        assertThrows(IllegalArgumentException.class, () -> service.createChatMessage(request));

        verifyNoInteractions(memory, publisher);
    }

    @Test
    void recommendRejectsPromptTooLongBeforeAgentExecution() {
        AgentLoopExecutor agent = mock(AgentLoopExecutor.class);
        AiAgentServiceImpl service = new AiAgentServiceImpl(mock(ConversationMemoryService.class), agent,
                mock(AiVectorIndexService.class), new AiAgentProperties(),
                mock(ApplicationEventPublisher.class), mock(ChatSseService.class));
        AgentRecommendRequest request = new AgentRecommendRequest();
        request.setQuery("字".repeat(8_001));

        assertThrows(IllegalArgumentException.class, () -> service.recommend(request));

        verifyNoInteractions(agent);
    }
}
