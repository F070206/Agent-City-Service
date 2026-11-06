package com.hmdp.ai.rag.retriever;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.dto.KnowledgeHitDTO;
import com.hmdp.ai.rag.AiMetadataConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;

class AiRagRetrieverTest {

    @AfterEach
    void clearSentinelRules() {
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    void shouldPreferKnowledgeFallbackWhenVectorHitIsIrrelevant() {
        VectorStore knowledgeVectorStore = mock(VectorStore.class);
        when(knowledgeVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("tech-1", "技术方案：缓存穿透解决方法是缓存空对象或布隆过滤器。", Map.of(
                        AiMetadataConstants.SOURCE_ID, "tech-1"
                ))
        ));

        AiRagRetriever retriever = newRetriever(knowledgeVectorStore);

        List<KnowledgeHitDTO> hits = retriever.searchKnowledge("超级会员有什么福利吗");

        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).getContent().contains("超级会员"));
        assertTrue(hits.get(0).getContent().contains("4 张 5 元无门槛优惠券"));
        assertFalse(hits.stream().anyMatch(hit -> "tech-1".equals(hit.getSourceId())));
    }

    @Test
    void rejectsOversizedKnowledgeQueryBeforeVectorOrFallbackWork() {
        VectorStore knowledgeVectorStore = mock(VectorStore.class);
        AiRagRetriever retriever = newRetriever(knowledgeVectorStore);

        assertThrows(IllegalArgumentException.class,
                () -> retriever.searchKnowledge("关键词".repeat(4_001)));

        verifyNoInteractions(knowledgeVectorStore);
    }

    @Test
    void shouldFallbackToKnowledgeFileWhenVectorStoreReturnsNothing() {
        VectorStore knowledgeVectorStore = mock(VectorStore.class);
        when(knowledgeVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        AiRagRetriever retriever = newRetriever(knowledgeVectorStore);

        List<KnowledgeHitDTO> hits = retriever.searchKnowledge("超级会员有什么福利吗");

        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).getContent().contains("积分倍增"));
    }

    @Test
    void keywordFallbackMatchesEnglishAcronymsWithoutCaseSensitivity() {
        VectorStore knowledgeVectorStore = mock(VectorStore.class);
        when(knowledgeVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        List<KnowledgeHitDTO> hits = newRetriever(knowledgeVectorStore).searchKnowledge("svip");

        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).getContent().contains("SVIP"));
    }

    @Test
    void keywordFallbackLoadsAndParsesClasspathCorpusOnlyOnce() throws Exception {
        VectorStore knowledgeVectorStore = mock(VectorStore.class);
        when(knowledgeVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        AiRagRetriever retriever = newRetriever(knowledgeVectorStore);
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        Resource resource = mock(Resource.class);
        AtomicInteger scans = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        ByteArrayInputStream input = spy(new ByteArrayInputStream(
                "超级会员可领取福利。\n\n福利包括优惠券。".getBytes(StandardCharsets.UTF_8)));
        when(resolver.getResources("classpath:knowledge/*.txt")).thenAnswer(invocation -> {
            scans.incrementAndGet();
            return new Resource[] {resource};
        });
        when(resource.getFilename()).thenReturn("test-rules.txt");
        when(resource.getInputStream()).thenAnswer(invocation -> {
            reads.incrementAndGet();
            return input;
        });
        ReflectionTestUtils.setField(retriever, "resourceResolver", resolver);

        List<KnowledgeHitDTO> first = retriever.searchKnowledge("超级会员有什么福利吗");
        List<KnowledgeHitDTO> second = retriever.searchKnowledge("超级会员有什么福利吗");

        assertEquals(first.size(), second.size());
        assertFalse(first.isEmpty());
        assertEquals(1, scans.get());
        assertEquals(1, reads.get());
        verify(input).close();
    }

    @Test
    void concurrentFirstQueriesShareOneFallbackCorpusLoad() throws Exception {
        VectorStore knowledgeVectorStore = mock(VectorStore.class);
        when(knowledgeVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        AiRagRetriever retriever = newRetriever(knowledgeVectorStore);
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        Resource resource = mock(Resource.class);
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch allowRead = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger scans = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        when(resolver.getResources("classpath:knowledge/*.txt")).thenAnswer(invocation -> {
            scans.incrementAndGet();
            return new Resource[] {resource};
        });
        when(resource.getFilename()).thenReturn("test-rules.txt");
        when(resource.getInputStream()).thenAnswer(invocation -> {
            reads.incrementAndGet();
            readStarted.countDown();
            if (!allowRead.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to read fallback corpus");
            }
            return new ByteArrayInputStream("超级会员可领取福利。".getBytes(StandardCharsets.UTF_8));
        });
        ReflectionTestUtils.setField(retriever, "resourceResolver", resolver);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<KnowledgeHitDTO>> first = executor.submit(
                    () -> retriever.searchKnowledge("超级会员有什么福利吗"));
            assertTrue(readStarted.await(5, TimeUnit.SECONDS));
            Future<List<KnowledgeHitDTO>> second = executor.submit(() -> {
                secondStarted.countDown();
                return retriever.searchKnowledge("超级会员有什么福利吗");
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertFalse(second.isDone());
            allowRead.countDown();

            assertFalse(first.get(5, TimeUnit.SECONDS).isEmpty());
            assertFalse(second.get(5, TimeUnit.SECONDS).isEmpty());
            assertEquals(1, scans.get());
            assertEquals(1, reads.get());
        } finally {
            allowRead.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldFallbackToKnowledgeFileWhenVectorStoreFails() {
        VectorStore knowledgeVectorStore = mock(VectorStore.class);
        when(knowledgeVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("Milvus unavailable"));

        List<KnowledgeHitDTO> hits = newRetriever(knowledgeVectorStore)
                .searchKnowledge("超级会员有什么福利吗");

        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).getContent().contains("超级会员"));
    }

    @Test
    void filteredSearchDoesNotReturnUnfilteredLocalDocuments() {
        VectorStore knowledgeVectorStore = mock(VectorStore.class);
        when(knowledgeVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        List<KnowledgeHitDTO> hits = newRetriever(knowledgeVectorStore).searchKnowledge(
                "超级会员有什么福利吗",
                new FilterExpressionBuilder().eq(AiMetadataConstants.SOURCE_ID, "unknown").build());

        assertTrue(hits.isEmpty());
    }

    @Test
    void vectorFailureWithoutRelevantLocalKnowledgeStillSurfaces() {
        VectorStore knowledgeVectorStore = mock(VectorStore.class);
        when(knowledgeVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("Milvus unavailable"));

        assertThrows(IllegalStateException.class,
                () -> newRetriever(knowledgeVectorStore).searchKnowledge("%%%"));
    }

    @Test
    void vectorSearchFailuresOpenCircuitWhileKnowledgeFallbackRemainsAvailable() {
        String resource = "ai:rag:search";
        DegradeRuleManager.loadRules(List.of(new DegradeRule(resource)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5)
                .setMinRequestAmount(1)
                .setStatIntervalMs(1000)
                .setTimeWindow(1)));
        VectorStore knowledgeVectorStore = mock(VectorStore.class);
        when(knowledgeVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("Milvus unavailable"));
        AiRagRetriever retriever = newRetriever(knowledgeVectorStore);

        assertFalse(retriever.searchKnowledge("超级会员有什么福利吗").isEmpty());
        assertFalse(retriever.searchKnowledge("超级会员有什么福利吗").isEmpty());

        verify(knowledgeVectorStore, times(1)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void emptyQueryUsesDefaultVectorQueryWithoutKeywordFallback() {
        VectorStore knowledgeVectorStore = mock(VectorStore.class);
        when(knowledgeVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        assertTrue(newRetriever(knowledgeVectorStore).searchKnowledge(null).isEmpty());
    }

    @Test
    void shouldKeepVectorHitsWhenFallbackHasNoMatch() {
        VectorStore knowledgeVectorStore = mock(VectorStore.class);
        when(knowledgeVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("intro-1", "同城生活服务平台是一个类似于大众点评的消费评价平台。", Map.of(
                        AiMetadataConstants.SOURCE_ID, "intro-1"
                ))
        ));

        AiRagRetriever retriever = newRetriever(knowledgeVectorStore);

        List<KnowledgeHitDTO> hits = retriever.searchKnowledge("这个应用主要做什么");

        assertEquals(1, hits.size());
        assertEquals("intro-1", hits.get(0).getSourceId());
        assertTrue(hits.get(0).getContent().contains("消费评价平台"));
    }

    @Test
    void indexExistenceChecksDoNotApplySemanticThresholds() {
        VectorStore knowledgeVectorStore = mock(VectorStore.class);
        VectorStore shopProfileVectorStore = mock(VectorStore.class);
        VectorStore blogReviewVectorStore = mock(VectorStore.class);
        AiAgentProperties properties = new AiAgentProperties();
        AiRagRetriever retriever = new AiRagRetriever(
                properties, knowledgeVectorStore, shopProfileVectorStore, blogReviewVectorStore);
        when(knowledgeVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(shopProfileVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(blogReviewVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        retriever.hasKnowledgeDocuments();
        retriever.hasShopProfileDocuments();
        retriever.hasBlogReviewDocuments();

        var knowledgeRequest = forClass(SearchRequest.class);
        var shopRequest = forClass(SearchRequest.class);
        var blogRequest = forClass(SearchRequest.class);
        verify(knowledgeVectorStore).similaritySearch(knowledgeRequest.capture());
        verify(shopProfileVectorStore).similaritySearch(shopRequest.capture());
        verify(blogReviewVectorStore).similaritySearch(blogRequest.capture());
        assertEquals(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL,
                knowledgeRequest.getValue().getSimilarityThreshold());
        assertEquals(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL,
                shopRequest.getValue().getSimilarityThreshold());
        assertEquals(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL,
                blogRequest.getValue().getSimilarityThreshold());
    }

    @Test
    void vectorToolCapsTopKAtConfiguredMaximum() {
        VectorStore knowledge = mock(VectorStore.class);
        VectorStore shops = mock(VectorStore.class);
        VectorStore blogs = mock(VectorStore.class);
        when(shops.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        AiRagRetriever retriever = new AiRagRetriever(new AiAgentProperties(), knowledge, shops, blogs);

        retriever.searchShopProfiles("附近咖啡", Integer.MAX_VALUE);

        var request = forClass(SearchRequest.class);
        verify(shops).similaritySearch(request.capture());
        assertEquals(20, request.getValue().getTopK());
    }

    private AiRagRetriever newRetriever(VectorStore knowledgeVectorStore) {
        AiAgentProperties properties = new AiAgentProperties();
        VectorStore shopProfileVectorStore = mock(VectorStore.class);
        VectorStore blogReviewVectorStore = mock(VectorStore.class);
        return new AiRagRetriever(properties, knowledgeVectorStore, shopProfileVectorStore, blogReviewVectorStore);
    }
}
