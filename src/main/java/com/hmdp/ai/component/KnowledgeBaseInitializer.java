package com.hmdp.ai.component;

import com.hmdp.ai.config.AiAgentProperties;
import com.hmdp.ai.rag.indexer.AiVectorIndexService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseInitializer {

    private final AiVectorIndexService aiVectorIndexService;
    private final AiAgentProperties aiAgentProperties;
    private final RedissonClient redissonClient;

    private static final String INDEX_BOOTSTRAP_LOCK = "lock:ai:vector-index";

    @PostConstruct
    public void init() {
        if (!aiAgentProperties.getBootstrap().isEnabled()) {
            log.info(">>>>>> [AI-AGENT] vector index bootstrap disabled by config");
            return;
        }
        log.info(">>>>>> [AI-AGENT] checking vector indexes...");
        try {
            if (aiAgentProperties.getRag().isVectorStoreEnabled()) {
                initializeSharedVectorStore();
            } else {
                aiVectorIndexService.ensureInitialized();
            }
            log.info(">>>>>> [AI-AGENT] vector index bootstrap finished");
        } catch (Exception e) {
            if (aiAgentProperties.getBootstrap().isFailFast()) {
                throw e;
            }
            log.warn(">>>>>> [AI-AGENT] vector index bootstrap skipped: {}", e.getMessage(), e);
        }
    }

    private void initializeSharedVectorStore() {
        RLock lock = redissonClient.getLock(INDEX_BOOTSTRAP_LOCK);
        lock.lock();
        try {
            // Recheck after acquiring the shared lock; another AI instance may have initialized Milvus.
            aiVectorIndexService.ensureInitialized();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
