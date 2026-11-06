package com.hmdp.ai.rag.indexer;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.hmdp.ai.rag.retriever.AiRagRetriever;
import com.hmdp.client.ContentCatalog;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;

class AiVectorIndexPagingTest {
    @AfterEach
    void clearSentinelRules() {
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    void shopIndexReadsAndWritesInBoundedBatches() {
        ContentCatalog catalog = mock(ContentCatalog.class);
        AiRagRetriever retriever = mock(AiRagRetriever.class);
        VectorStore knowledge = mock(VectorStore.class);
        VectorStore shops = mock(VectorStore.class);
        VectorStore blogs = mock(VectorStore.class);
        when(retriever.hasKnowledgeDocuments()).thenReturn(true);
        when(retriever.hasBlogReviewDocuments()).thenReturn(true);
        List<Shop> first = new ArrayList<>();
        for (long id = 1; id <= 200; id++) {
            first.add(shop(id));
        }
        when(catalog.shopsAfter(0, 200)).thenReturn(first);
        when(catalog.shopsAfter(200, 200)).thenReturn(List.of(shop(201L)));

        newIndexService(knowledge, shops, blogs, catalog, retriever).ensureInitialized();

        verify(catalog).shopsAfter(0, 200);
        verify(catalog).shopsAfter(200, 200);
        verify(shops, times(2)).add(anyList());
    }

    @Test
    void blogIndexLoadsOnlyShopsReferencedByEachBatch() {
        ContentCatalog catalog = mock(ContentCatalog.class);
        AiRagRetriever retriever = mock(AiRagRetriever.class);
        VectorStore knowledge = mock(VectorStore.class);
        VectorStore shops = mock(VectorStore.class);
        VectorStore blogs = mock(VectorStore.class);
        when(retriever.hasKnowledgeDocuments()).thenReturn(true);
        when(retriever.hasShopProfileDocuments()).thenReturn(true);
        List<Blog> first = new ArrayList<>();
        for (long id = 1; id <= 200; id++) {
            first.add(new Blog().setId(id).setShopId(10L).setUserId(7L).setTitle("标题"));
        }
        when(catalog.blogsAfter(0, 200)).thenReturn(first);
        when(catalog.blogsAfter(200, 200)).thenReturn(List.of(
                new Blog().setId(201L).setShopId(10L).setUserId(7L).setTitle("标题")));
        when(catalog.shopsByIds(List.of(10L))).thenReturn(List.of(shop(10L)));

        newIndexService(knowledge, shops, blogs, catalog, retriever).ensureInitialized();

        verify(catalog).blogsAfter(0, 200);
        verify(catalog).blogsAfter(200, 200);
        verify(catalog, times(2)).shopsByIds(List.of(10L));
        verify(blogs, times(2)).add(anyList());
    }

    @Test
    void singleShopBlogIndexReadsAndWritesInBoundedBatches() {
        ContentCatalog catalog = mock(ContentCatalog.class);
        AiRagRetriever retriever = mock(AiRagRetriever.class);
        VectorStore knowledge = mock(VectorStore.class);
        VectorStore shops = mock(VectorStore.class);
        VectorStore blogs = mock(VectorStore.class);
        when(catalog.shop(10L)).thenReturn(shop(10L));
        List<Blog> first = new ArrayList<>();
        for (long id = 1; id <= 200; id++) {
            first.add(new Blog().setId(id).setShopId(10L).setUserId(7L).setTitle("标题"));
        }
        when(catalog.blogsForShopAfter(10L, 0, 200)).thenReturn(first);
        when(catalog.blogsForShopAfter(10L, 200, 200)).thenReturn(List.of(
                new Blog().setId(201L).setShopId(10L).setUserId(7L).setTitle("标题")));
        when(catalog.shopsByIds(List.of(10L))).thenReturn(List.of(shop(10L)));

        var response = newIndexService(knowledge, shops, blogs, catalog, retriever).rebuildShop(10L);

        assertEquals(201, response.getBlogReviewCount());
        verify(catalog).blogsForShopAfter(10L, 0, 200);
        verify(catalog).blogsForShopAfter(10L, 200, 200);
        verify(catalog, times(2)).shopsByIds(List.of(10L));
        verify(blogs, times(2)).add(anyList());
        verify(catalog, never()).blogsForShop(eq(10L), any());
    }

    @Test
    void shopSourceFailurePreservesExistingProfileIndex() {
        ContentCatalog catalog = mock(ContentCatalog.class);
        AiRagRetriever retriever = mock(AiRagRetriever.class);
        VectorStore knowledge = mock(VectorStore.class);
        VectorStore shops = mock(VectorStore.class);
        VectorStore blogs = mock(VectorStore.class);
        when(catalog.shop(10L)).thenThrow(new IllegalStateException("content service unavailable"));
        AiVectorIndexServiceImpl indexService = newIndexService(knowledge, shops, blogs, catalog, retriever);

        assertThrows(IllegalStateException.class, () -> indexService.rebuildShop(10L));

        verify(shops, never()).delete(any(Filter.Expression.class));
        verify(shops, never()).add(anyList());
        verify(blogs, never()).delete(any(Filter.Expression.class));
    }

    @Test
    void laterReviewPageFailurePreservesExistingReviewIndex() {
        ContentCatalog catalog = mock(ContentCatalog.class);
        AiRagRetriever retriever = mock(AiRagRetriever.class);
        VectorStore knowledge = mock(VectorStore.class);
        VectorStore shops = mock(VectorStore.class);
        VectorStore blogs = mock(VectorStore.class);
        when(catalog.shop(10L)).thenReturn(shop(10L));
        List<Blog> firstPage = new ArrayList<>();
        for (long id = 1; id <= 200; id++) {
            firstPage.add(new Blog().setId(id).setShopId(10L).setUserId(7L).setTitle("标题"));
        }
        when(catalog.blogsForShopAfter(10L, 0L, 200)).thenReturn(firstPage);
        when(catalog.blogsForShopAfter(10L, 200L, 200))
                .thenThrow(new IllegalStateException("content page unavailable"));
        when(catalog.shopsByIds(List.of(10L))).thenReturn(List.of(shop(10L)));
        AiVectorIndexServiceImpl indexService = newIndexService(knowledge, shops, blogs, catalog, retriever);

        assertThrows(IllegalStateException.class, () -> indexService.rebuildShop(10L));

        verify(blogs, never()).delete(any(Filter.Expression.class));
        verify(blogs, never()).add(anyList());
    }

    @Test
    void laterShopPageFailurePreservesExistingProfileIndex() {
        ContentCatalog catalog = mock(ContentCatalog.class);
        AiRagRetriever retriever = mock(AiRagRetriever.class);
        VectorStore knowledge = mock(VectorStore.class);
        VectorStore shops = mock(VectorStore.class);
        VectorStore blogs = mock(VectorStore.class);
        when(retriever.hasKnowledgeDocuments()).thenReturn(true);
        when(retriever.hasShopProfileDocuments()).thenReturn(false);
        when(retriever.hasBlogReviewDocuments()).thenReturn(true);
        List<Shop> firstPage = new ArrayList<>();
        for (long id = 1; id <= 200; id++) {
            firstPage.add(shop(id));
        }
        when(catalog.shopsAfter(0L, 200)).thenReturn(firstPage);
        when(catalog.shopsAfter(200L, 200))
                .thenThrow(new IllegalStateException("content page unavailable"));
        AiVectorIndexServiceImpl indexService = newIndexService(knowledge, shops, blogs, catalog, retriever);

        assertThrows(IllegalStateException.class, indexService::ensureInitialized);

        verify(shops, never()).delete(any(Filter.Expression.class));
        verify(shops, never()).add(anyList());
    }

    @Test
    void laterBlogPageFailurePreservesExistingReviewIndex() {
        ContentCatalog catalog = mock(ContentCatalog.class);
        AiRagRetriever retriever = mock(AiRagRetriever.class);
        VectorStore knowledge = mock(VectorStore.class);
        VectorStore shops = mock(VectorStore.class);
        VectorStore blogs = mock(VectorStore.class);
        when(retriever.hasKnowledgeDocuments()).thenReturn(true);
        when(retriever.hasShopProfileDocuments()).thenReturn(true);
        when(retriever.hasBlogReviewDocuments()).thenReturn(false);
        List<Blog> firstPage = new ArrayList<>();
        for (long id = 1; id <= 200; id++) {
            firstPage.add(new Blog().setId(id).setShopId(10L).setUserId(7L).setTitle("标题"));
        }
        when(catalog.blogsAfter(0L, 200)).thenReturn(firstPage);
        when(catalog.blogsAfter(200L, 200))
                .thenThrow(new IllegalStateException("content page unavailable"));
        when(catalog.shopsByIds(List.of(10L))).thenReturn(List.of(shop(10L)));
        AiVectorIndexServiceImpl indexService = newIndexService(knowledge, shops, blogs, catalog, retriever);

        assertThrows(IllegalStateException.class, indexService::ensureInitialized);

        verify(blogs, never()).delete(any(Filter.Expression.class));
        verify(blogs, never()).add(anyList());
    }

    @Test
    void partialShopIndexIsClearedSoNextInitializationRetries() {
        ContentCatalog catalog = mock(ContentCatalog.class);
        AiRagRetriever retriever = mock(AiRagRetriever.class);
        VectorStore knowledge = mock(VectorStore.class);
        VectorStore shops = mock(VectorStore.class);
        VectorStore blogs = mock(VectorStore.class);
        List<Document> indexedShops = new ArrayList<>();
        doAnswer(invocation -> {
            indexedShops.addAll(invocation.getArgument(0));
            return null;
        }).when(shops).add(anyList());
        doAnswer(invocation -> {
            indexedShops.clear();
            return null;
        }).when(shops).delete(any(Filter.Expression.class));
        when(retriever.hasKnowledgeDocuments()).thenReturn(true);
        when(retriever.hasShopProfileDocuments()).thenAnswer(invocation -> !indexedShops.isEmpty());
        when(retriever.hasBlogReviewDocuments()).thenReturn(true);
        List<Shop> first = new ArrayList<>();
        for (long id = 1; id <= 200; id++) {
            first.add(shop(id));
        }
        when(catalog.shopsAfter(0, 200)).thenReturn(first, List.of());
        when(catalog.shopsAfter(200, 200)).thenThrow(new IllegalStateException("content page unavailable"));
        AiVectorIndexServiceImpl indexService = newIndexService(knowledge, shops, blogs, catalog, retriever);

        assertThrows(IllegalStateException.class, indexService::ensureInitialized);
        assertTrue(indexedShops.isEmpty());

        indexService.ensureInitialized();

        verify(catalog, times(2)).shopsAfter(0, 200);
        verify(shops, times(1)).delete(any(Filter.Expression.class));
        assertTrue(indexedShops.isEmpty());
    }

    @Test
    void indexFailuresOpenCircuitBeforeFurtherVectorStoreWrites() {
        DegradeRuleManager.loadRules(List.of(new DegradeRule("ai:rag:index")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5)
                .setMinRequestAmount(1)
                .setStatIntervalMs(1000)
                .setTimeWindow(1)));
        ContentCatalog catalog = mock(ContentCatalog.class);
        AiRagRetriever retriever = mock(AiRagRetriever.class);
        VectorStore knowledge = mock(VectorStore.class);
        VectorStore shops = mock(VectorStore.class);
        VectorStore blogs = mock(VectorStore.class);
        doThrow(new IllegalStateException("Milvus unavailable"))
                .when(shops).delete(any(Filter.Expression.class));
        AiVectorIndexServiceImpl indexService = newIndexService(knowledge, shops, blogs, catalog, retriever);

        assertThrows(IllegalStateException.class, () -> indexService.rebuildShop(10L));
        assertThrows(IllegalStateException.class, () -> indexService.rebuildShop(10L));

        verify(shops, times(1)).delete(any(Filter.Expression.class));
        verify(shops, never()).add(anyList());
    }

    @Test
    void concurrentIndexRebuildIsRejectedBeforeTouchingSourcesOrVectorStores() {
        ContentCatalog catalog = mock(ContentCatalog.class);
        AiRagRetriever retriever = mock(AiRagRetriever.class);
        VectorStore knowledge = mock(VectorStore.class);
        VectorStore shops = mock(VectorStore.class);
        VectorStore blogs = mock(VectorStore.class);
        AiVectorIndexServiceImpl indexService = newIndexService(
                knowledge, shops, blogs, catalog, retriever, false);

        ResponseStatusException busy = assertThrows(ResponseStatusException.class, indexService::rebuildAll);
        assertEquals(HttpStatus.CONFLICT, busy.getStatusCode());

        verifyNoInteractions(catalog, retriever, knowledge, shops, blogs);
    }

    private AiVectorIndexServiceImpl newIndexService(VectorStore knowledge, VectorStore shops,
            VectorStore blogs, ContentCatalog catalog, AiRagRetriever retriever) {
        return newIndexService(knowledge, shops, blogs, catalog, retriever, true);
    }

    private AiVectorIndexServiceImpl newIndexService(VectorStore knowledge, VectorStore shops,
            VectorStore blogs, ContentCatalog catalog, AiRagRetriever retriever, boolean lockAvailable) {
        AiVectorIndexServiceImpl indexService = new AiVectorIndexServiceImpl(
                knowledge, shops, blogs, catalog, retriever);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock("lock:ai:vector-index")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(lockAvailable);
        when(lock.isHeldByCurrentThread()).thenReturn(lockAvailable);
        ReflectionTestUtils.setField(indexService, "redissonClient", redisson);
        return indexService;
    }

    private Shop shop(long id) {
        return new Shop().setId(id).setTypeId(1L).setName("店铺").setAddress("地址")
                .setAvgPrice(50L).setScore(45).setComments(1).setSold(1)
                .setX(121.0).setY(31.0);
    }
}


