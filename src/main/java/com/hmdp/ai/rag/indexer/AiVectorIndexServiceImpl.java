package com.hmdp.ai.rag.indexer;

import com.hmdp.ai.config.AiDependencyGuard;
import com.hmdp.ai.dto.IndexRebuildResponse;
import com.hmdp.ai.rag.AiMetadataConstants;
import com.hmdp.ai.rag.retriever.AiRagRetriever;
import com.hmdp.client.ContentCatalog;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 向量索引构建器。
 * 负责把平台知识、店铺画像和探店笔记三类数据写入不同的向量集合。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiVectorIndexServiceImpl implements AiVectorIndexService {

    private static final int INDEX_BATCH_SIZE = 200;
    private static final String INDEX_REBUILD_LOCK = "lock:ai:vector-index";

    @Qualifier("knowledgeVectorStore")
    private final VectorStore knowledgeVectorStore;

    @Qualifier("shopProfileVectorStore")
    private final VectorStore shopProfileVectorStore;

    @Qualifier("blogReviewVectorStore")
    private final VectorStore blogReviewVectorStore;

    private final ContentCatalog contentCatalog;

    private final AiRagRetriever aiRagRetriever;

    @Autowired
    private RedissonClient redissonClient;

    private final TokenTextSplitter tokenTextSplitter = new TokenTextSplitter();

    @Override
    public IndexRebuildResponse rebuildAll() {
        return withIndexLock(this::rebuildAllLocked);
    }

    private IndexRebuildResponse rebuildAllLocked() {
        IndexRebuildResponse response = new IndexRebuildResponse();
        response.setScope("all");
        response.setKnowledgeCount(rebuildKnowledge());
        response.setShopProfileCount(rebuildAllShopProfiles());
        response.setBlogReviewCount(rebuildAllBlogReviews());
        response.setSuccess(true);
        response.setMessage("AI 全量索引重建完成");
        return response;
    }

    @Override
    public IndexRebuildResponse rebuildShop(Long shopId) {
        return withIndexLock(() -> rebuildShopLocked(shopId));
    }

    private IndexRebuildResponse rebuildShopLocked(Long shopId) {
        IndexRebuildResponse response = new IndexRebuildResponse();
        response.setScope("shop:" + shopId);
        response.setKnowledgeCount(0);
        response.setShopProfileCount(rebuildSingleShopProfile(shopId));
        response.setBlogReviewCount(rebuildShopBlogReviews(shopId));
        response.setSuccess(true);
        response.setMessage("店铺索引重建完成");
        return response;
    }

    @Override
    public int rebuildKnowledge() {
        return withIndexLock(this::rebuildKnowledgeLocked);
    }

    private int rebuildKnowledgeLocked() {
        Filter.Expression filter = new FilterExpressionBuilder()
                .eq(AiMetadataConstants.DOC_TYPE, AiMetadataConstants.DOC_TYPE_KNOWLEDGE).build();
        boolean replacementStarted = false;
        try {
            List<Document> documents = loadKnowledgeDocuments();
            replacementStarted = true;
            indexVectorStore(() -> knowledgeVectorStore.delete(filter));
            if (!documents.isEmpty()) {
                indexVectorStore(() -> knowledgeVectorStore.add(documents));
            }
            return documents.size();
        }
        catch (Exception e) {
            log.error("Failed to rebuild knowledge vector store", e);
            if (replacementStarted) {
                clearPartialIndex(knowledgeVectorStore, filter, e);
            }
            throw new IllegalStateException("重建知识库索引失败", e);
        }
    }

    @Override
    public void ensureInitialized() {
        withIndexLock(() -> {
            ensureInitializedLocked();
            return null;
        });
    }

    private void ensureInitializedLocked() {
        // 启动时只做“缺啥补啥”，避免每次启动都全量重建索引。
        if (!aiRagRetriever.hasKnowledgeDocuments()) {
            rebuildKnowledge();
        }
        if (!aiRagRetriever.hasShopProfileDocuments()) {
            rebuildAllShopProfiles();
        }
        if (!aiRagRetriever.hasBlogReviewDocuments()) {
            rebuildAllBlogReviews();
        }
    }

    private <T> T withIndexLock(java.util.function.Supplier<T> operation) {
        RLock lock = redissonClient.getLock(INDEX_REBUILD_LOCK);
        boolean acquired;
        try {
            acquired = lock.tryLock();
        } catch (RuntimeException lockFailure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 索引锁服务暂不可用", lockFailure);
        }
        if (!acquired) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "AI 索引重建正在进行，请稍后重试");
        }
        try {
            return operation.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private int rebuildAllShopProfiles() {
        Filter.Expression filter = new FilterExpressionBuilder()
                .eq(AiMetadataConstants.DOC_TYPE, AiMetadataConstants.DOC_TYPE_SHOP_PROFILE).build();
        Path stagedDocuments = null;
        boolean replacementStarted = false;
        try {
            stagedDocuments = Files.createTempFile("cityai-shop-profile-index-", ".bin");
            int count = stageAllShopProfileDocuments(stagedDocuments);
            replacementStarted = true;
            indexVectorStore(() -> shopProfileVectorStore.delete(filter));
            writeStagedDocuments(stagedDocuments, shopProfileVectorStore);
            return count;
        } catch (RuntimeException e) {
            if (replacementStarted) {
                clearPartialIndex(shopProfileVectorStore, filter, e);
            }
            throw e;
        } catch (IOException | ClassNotFoundException e) {
            if (replacementStarted) {
                clearPartialIndex(shopProfileVectorStore, filter, e);
            }
            throw new IllegalStateException("店铺画像索引暂存或写入失败", e);
        } finally {
            deleteStagedDocuments(stagedDocuments);
        }
    }

    private int rebuildSingleShopProfile(Long shopId) {
        Filter.Expression filter = new FilterExpressionBuilder().eq(AiMetadataConstants.SHOP_ID, shopId).build();
        // Read the source before deleting the current profile so a Content service failure preserves old data.
        Shop shop = contentCatalog.shop(shopId);
        try {
            indexVectorStore(() -> shopProfileVectorStore.delete(filter));
            if (shop == null) {
                return 0;
            }
            List<Document> documents = List.of(toShopProfileDocument(shop));
            indexVectorStore(() -> shopProfileVectorStore.add(documents));
            return 1;
        } catch (RuntimeException e) {
            clearPartialIndex(shopProfileVectorStore, filter, e);
            throw e;
        }
    }

    private int rebuildAllBlogReviews() {
        Filter.Expression filter = new FilterExpressionBuilder()
                .eq(AiMetadataConstants.DOC_TYPE, AiMetadataConstants.DOC_TYPE_BLOG_REVIEW).build();
        Path stagedDocuments = null;
        boolean replacementStarted = false;
        try {
            stagedDocuments = Files.createTempFile("cityai-blog-review-index-", ".bin");
            int count = stageAllBlogReviewDocuments(stagedDocuments);
            replacementStarted = true;
            indexVectorStore(() -> blogReviewVectorStore.delete(filter));
            writeStagedDocuments(stagedDocuments, blogReviewVectorStore);
            return count;
        } catch (RuntimeException e) {
            if (replacementStarted) {
                clearPartialIndex(blogReviewVectorStore, filter, e);
            }
            throw e;
        } catch (IOException | ClassNotFoundException e) {
            if (replacementStarted) {
                clearPartialIndex(blogReviewVectorStore, filter, e);
            }
            throw new IllegalStateException("评论索引暂存或写入失败", e);
        } finally {
            deleteStagedDocuments(stagedDocuments);
        }
    }

    private int stageAllShopProfileDocuments(Path stagedDocuments) throws IOException {
        int count = 0;
        long afterId = 0;
        try (ObjectOutputStream output = new ObjectOutputStream(
                new BufferedOutputStream(Files.newOutputStream(stagedDocuments)))) {
            List<Shop> shops;
            do {
                shops = contentCatalog.shopsAfter(afterId, INDEX_BATCH_SIZE);
                for (Shop shop : shops) {
                    Document document = toShopProfileDocument(shop);
                    output.writeObject(new StagedDocument(document.getId(), document.getText(),
                            new HashMap<>(document.getMetadata())));
                    count++;
                }
                if (!shops.isEmpty()) {
                    afterId = shops.get(shops.size() - 1).getId();
                }
            } while (shops.size() == INDEX_BATCH_SIZE);
        }
        return count;
    }

    private int stageAllBlogReviewDocuments(Path stagedDocuments) throws IOException {
        int count = 0;
        long afterId = 0;
        try (ObjectOutputStream output = new ObjectOutputStream(
                new BufferedOutputStream(Files.newOutputStream(stagedDocuments)))) {
            List<Blog> blogs;
            do {
                blogs = contentCatalog.blogsAfter(afterId, INDEX_BATCH_SIZE);
                for (Document document : buildBlogDocuments(blogs)) {
                    output.writeObject(new StagedDocument(document.getId(), document.getText(),
                            new HashMap<>(document.getMetadata())));
                    count++;
                }
                if (!blogs.isEmpty()) {
                    afterId = blogs.get(blogs.size() - 1).getId();
                }
            } while (blogs.size() == INDEX_BATCH_SIZE);
        }
        return count;
    }

    private int rebuildShopBlogReviews(Long shopId) {
        Filter.Expression filter = new FilterExpressionBuilder().eq(AiMetadataConstants.SHOP_ID, shopId).build();
        Path stagedDocuments = null;
        boolean replacementStarted = false;
        try {
            stagedDocuments = Files.createTempFile("cityai-shop-blog-index-", ".bin");
            int count = stageShopBlogDocuments(shopId, stagedDocuments);
            // Complete the bounded-memory content scan before removing the currently searchable documents.
            replacementStarted = true;
            indexVectorStore(() -> blogReviewVectorStore.delete(filter));
            writeStagedShopBlogDocuments(stagedDocuments);
            return count;
        } catch (RuntimeException e) {
            if (replacementStarted) {
                clearPartialIndex(blogReviewVectorStore, filter, e);
            }
            throw e;
        } catch (IOException | ClassNotFoundException e) {
            if (replacementStarted) {
                clearPartialIndex(blogReviewVectorStore, filter, e);
            }
            throw new IllegalStateException("店铺评论索引暂存或写入失败", e);
        } finally {
            if (stagedDocuments != null) {
                try {
                    Files.deleteIfExists(stagedDocuments);
                } catch (IOException e) {
                    log.warn("无法删除店铺评论索引暂存文件: {}", stagedDocuments, e);
                }
            }
        }
    }

    private int stageShopBlogDocuments(Long shopId, Path stagedDocuments) throws IOException {
        int count = 0;
        long afterId = 0;
        try (ObjectOutputStream output = new ObjectOutputStream(
                new BufferedOutputStream(Files.newOutputStream(stagedDocuments)))) {
            List<Blog> blogs;
            do {
                blogs = contentCatalog.blogsForShopAfter(shopId, afterId, INDEX_BATCH_SIZE);
                for (Document document : buildBlogDocuments(blogs)) {
                    output.writeObject(new StagedDocument(document.getId(), document.getText(),
                            new HashMap<>(document.getMetadata())));
                    count++;
                }
                if (!blogs.isEmpty()) {
                    afterId = blogs.get(blogs.size() - 1).getId();
                }
            } while (blogs.size() == INDEX_BATCH_SIZE);
        }
        return count;
    }

    private void writeStagedShopBlogDocuments(Path stagedDocuments)
            throws IOException, ClassNotFoundException {
        writeStagedDocuments(stagedDocuments, blogReviewVectorStore);
    }

    private void writeStagedDocuments(Path stagedDocuments, VectorStore vectorStore)
            throws IOException, ClassNotFoundException {
        try (ObjectInputStream input = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(stagedDocuments)))) {
            List<Document> batch = new ArrayList<>(INDEX_BATCH_SIZE);
            while (true) {
                StagedDocument staged;
                try {
                    staged = (StagedDocument) input.readObject();
                } catch (EOFException endOfFile) {
                    break;
                }
                batch.add(new Document(staged.id(), staged.text(), staged.metadata()));
                if (batch.size() == INDEX_BATCH_SIZE) {
                    addBatch(vectorStore, batch);
                    batch = new ArrayList<>(INDEX_BATCH_SIZE);
                }
            }
            if (!batch.isEmpty()) {
                addBatch(vectorStore, batch);
            }
        }
    }

    private void addBatch(VectorStore vectorStore, List<Document> documents) {
        indexVectorStore(() -> vectorStore.add(documents));
    }

    private void addBlogBatch(List<Document> documents) {
        addBatch(blogReviewVectorStore, documents);
    }

    private void deleteStagedDocuments(Path stagedDocuments) {
        if (stagedDocuments == null) {
            return;
        }
        try {
            Files.deleteIfExists(stagedDocuments);
        } catch (IOException e) {
            log.warn("无法删除向量索引暂存文件: {}", stagedDocuments, e);
        }
    }

    private void clearPartialIndex(VectorStore vectorStore, Filter.Expression filter, Exception failure) {
        try {
            indexVectorStore(() -> vectorStore.delete(filter));
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            log.error("Failed to clear partially rebuilt vector index", cleanupFailure);
        }
    }

    private void indexVectorStore(Runnable operation) {
        AiDependencyGuard.callUnchecked("ai:rag:index", () -> {
            operation.run();
            return null;
        });
    }

    private List<Document> loadKnowledgeDocuments() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:knowledge/*.txt");
        List<Document> indexedDocuments = new ArrayList<>();
        for (Resource resource : resources) {
            // 平台规则文档会先切块再入库，避免单条文档过长导致召回粒度太粗。
            TextReader reader = new TextReader(resource);
            List<Document> splitDocuments = tokenTextSplitter.apply(reader.get());
            for (int i = 0; i < splitDocuments.size(); i++) {
                Document document = splitDocuments.get(i);
                Map<String, Object> metadata = new HashMap<>(document.getMetadata());
                String sourceId = resource.getFilename() + "#" + i;
                metadata.put(AiMetadataConstants.DOC_TYPE, AiMetadataConstants.DOC_TYPE_KNOWLEDGE);
                metadata.put(AiMetadataConstants.SOURCE_ID, sourceId);
                metadata.put(AiMetadataConstants.SHOP_ID, 0L);
                metadata.put(AiMetadataConstants.TYPE_ID, 0L);
                metadata.put(AiMetadataConstants.AREA, "");
                metadata.put(AiMetadataConstants.USER_ID, 0L);
                indexedDocuments.add(new Document(sourceId, document.getText(), metadata));
            }
        }
        return indexedDocuments;
    }

    private Document toShopProfileDocument(Shop shop) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(AiMetadataConstants.DOC_TYPE, AiMetadataConstants.DOC_TYPE_SHOP_PROFILE);
        metadata.put(AiMetadataConstants.SOURCE_ID, shop.getId());
        metadata.put(AiMetadataConstants.SHOP_ID, shop.getId());
        metadata.put(AiMetadataConstants.TYPE_ID, shop.getTypeId());
        metadata.put(AiMetadataConstants.AREA, defaultString(shop.getArea()));
        metadata.put(AiMetadataConstants.USER_ID, 0L);
        metadata.put(AiMetadataConstants.SHOP_NAME, shop.getName());
        metadata.put(AiMetadataConstants.ADDRESS, shop.getAddress());
        metadata.put(AiMetadataConstants.AVG_PRICE, shop.getAvgPrice());
        metadata.put(AiMetadataConstants.RATING, shop.getScore() == null ? null : shop.getScore() / 10.0);
        metadata.put(AiMetadataConstants.COMMENTS, shop.getComments());
        metadata.put(AiMetadataConstants.SOLD, shop.getSold());
        metadata.put(AiMetadataConstants.OPEN_HOURS, defaultString(shop.getOpenHours()));
        metadata.put(AiMetadataConstants.X, shop.getX());
        metadata.put(AiMetadataConstants.Y, shop.getY());

        // 店铺画像文本尽量保留结构化字段，方便向量召回和结果解释共用一份数据。
        String text = """
                店铺名：%s
                分类ID：%s
                商圈：%s
                地址：%s
                均价：%s元
                评分：%s分
                评论数：%s
                销量：%s
                营业时间：%s
                """.formatted(
                shop.getName(),
                shop.getTypeId(),
                defaultString(shop.getArea()),
                shop.getAddress(),
                shop.getAvgPrice(),
                shop.getScore() == null ? "未知" : shop.getScore() / 10.0,
                shop.getComments(),
                shop.getSold(),
                defaultString(shop.getOpenHours())
        );
        return new Document(String.valueOf(shop.getId()), text, metadata);
    }

    private List<Document> buildBlogDocuments(List<Blog> blogs) {
        if (blogs == null || blogs.isEmpty()) {
            return List.of();
        }
        // 评论向量不只存正文，还补上标题和店铺名，降低纯正文短评的语义损失。
        List<Long> shopIds = blogs.stream().map(Blog::getShopId).filter(id -> id != null).distinct().toList();
        Map<Long, Shop> shopMap = contentCatalog.shopsByIds(shopIds).stream()
                .collect(Collectors.toMap(Shop::getId, shop -> shop));
        List<Document> documents = new ArrayList<>(blogs.size());
        for (Blog blog : blogs) {
            Shop shop = shopMap.get(blog.getShopId());
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(AiMetadataConstants.DOC_TYPE, AiMetadataConstants.DOC_TYPE_BLOG_REVIEW);
            metadata.put(AiMetadataConstants.SOURCE_ID, blog.getId());
            metadata.put(AiMetadataConstants.SHOP_ID, blog.getShopId());
            metadata.put(AiMetadataConstants.TYPE_ID, shop == null ? 0L : shop.getTypeId());
            metadata.put(AiMetadataConstants.AREA, shop == null ? "" : defaultString(shop.getArea()));
            metadata.put(AiMetadataConstants.USER_ID, blog.getUserId());
            metadata.put(AiMetadataConstants.TITLE, blog.getTitle());

            String text = """
                    标题：%s
                    店铺：%s
                    正文：%s
                    """.formatted(
                    defaultString(blog.getTitle()),
                    shop == null ? "未知店铺" : shop.getName(),
                    defaultString(blog.getContent())
            );
            documents.add(new Document(String.valueOf(blog.getId()), text, metadata));
        }
        return documents;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private record StagedDocument(String id, String text, Map<String, Object> metadata)
            implements Serializable {
        private static final long serialVersionUID = 1L;
    }
}
