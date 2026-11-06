package com.hmdp.ai.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.ListDatabasesResponse;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.CreateDatabaseParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.HasCollectionParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusServiceClientProperties;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import okhttp3.OkHttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * AI 基础设施配置。
 * 这里主要负责两件事：模型调用超时控制，以及 Milvus 向量集合的初始化与兼容处理。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({
        AiAgentProperties.class,
        MilvusServiceClientProperties.class,
        MilvusVectorStoreProperties.class
})
public class CommonConfiguration {

    @Bean
    public RestClientCustomizer aiRestClientCustomizer(AiAgentProperties properties) {
        return builder -> {
            // AI 调用一旦卡住会直接拖慢 AgentLoop，这里统一收口网络超时配置。
            OkHttpClient okHttpClient = new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(properties.getModel().getConnectTimeoutSeconds()))
                    .readTimeout(Duration.ofSeconds(properties.getModel().getReadTimeoutSeconds()))
                    .writeTimeout(Duration.ofSeconds(properties.getModel().getWriteTimeoutSeconds()))
                    .callTimeout(Duration.ofSeconds(properties.getModel().getCallTimeoutSeconds()))
                    .retryOnConnectionFailure(true)
                    .build();

            OkHttp3ClientHttpRequestFactory requestFactory = new OkHttp3ClientHttpRequestFactory(okHttpClient);
            requestFactory.setConnectTimeout(Duration.ofSeconds(properties.getModel().getConnectTimeoutSeconds()));
            requestFactory.setReadTimeout(Duration.ofSeconds(properties.getModel().getReadTimeoutSeconds()));
            requestFactory.setWriteTimeout(Duration.ofSeconds(properties.getModel().getWriteTimeoutSeconds()));
            builder.requestFactory(requestFactory);
        };
    }

    @Bean
    @Lazy
    public MilvusServiceClient milvusServiceClient(MilvusServiceClientProperties clientProperties,
                                                   MilvusVectorStoreProperties vectorStoreProperties,
                                                   AiAgentProperties aiAgentProperties) {
        String databaseName = StringUtils.hasText(vectorStoreProperties.getDatabaseName())
                ? vectorStoreProperties.getDatabaseName()
                : MilvusVectorStore.DEFAULT_DATABASE_NAME;

        // 如果业务配置了非默认数据库，先用管理连接确保数据库存在，再切换到目标库。
        if (aiAgentProperties.getBootstrap().isEnabled()
                && !MilvusVectorStore.DEFAULT_DATABASE_NAME.equals(databaseName)) {
            MilvusServiceClient adminClient = new MilvusServiceClient(baseConnectParamBuilder(clientProperties).build());
            ensureDatabaseExists(adminClient, databaseName);
            closeQuietly(adminClient);
        }

        return new MilvusServiceClient(baseConnectParamBuilder(clientProperties)
                .withDatabaseName(databaseName)
                .build());
    }

    @Bean("knowledgeVectorStore")
    public VectorStore knowledgeVectorStore(ObjectProvider<MilvusServiceClient> milvusClientProvider,
                                            EmbeddingModel embeddingModel,
                                            MilvusVectorStoreProperties properties,
                                            AiAgentProperties aiAgentProperties) {
        return buildVectorStore(milvusClientProvider, embeddingModel, properties,
                aiAgentProperties.getRag().getCollections().getKnowledge(), aiAgentProperties);
    }

    @Bean("shopProfileVectorStore")
    public VectorStore shopProfileVectorStore(ObjectProvider<MilvusServiceClient> milvusClientProvider,
                                              EmbeddingModel embeddingModel,
                                              MilvusVectorStoreProperties properties,
                                              AiAgentProperties aiAgentProperties) {
        return buildVectorStore(milvusClientProvider, embeddingModel, properties,
                aiAgentProperties.getRag().getCollections().getShopProfile(), aiAgentProperties);
    }

    @Bean("blogReviewVectorStore")
    public VectorStore blogReviewVectorStore(ObjectProvider<MilvusServiceClient> milvusClientProvider,
                                             EmbeddingModel embeddingModel,
                                             MilvusVectorStoreProperties properties,
                                             AiAgentProperties aiAgentProperties) {
        return buildVectorStore(milvusClientProvider, embeddingModel, properties,
                aiAgentProperties.getRag().getCollections().getBlogReview(), aiAgentProperties);
    }

    private VectorStore buildVectorStore(ObjectProvider<MilvusServiceClient> milvusClientProvider,
                                         EmbeddingModel embeddingModel,
                                         MilvusVectorStoreProperties properties,
                                         String collectionName,
                                         AiAgentProperties aiAgentProperties) {
        if (!aiAgentProperties.getRag().isVectorStoreEnabled()) {
            return inMemoryVectorStore(embeddingModel, collectionName, "Milvus vector store disabled");
        }

        boolean bootstrapEnabled = aiAgentProperties.getBootstrap().isEnabled();
        String databaseName = properties.getDatabaseName();
        int embeddingDimension = properties.getEmbeddingDimension() > 0 ? properties.getEmbeddingDimension() : 1024;
        try {
            MilvusServiceClient milvusServiceClient = milvusClientProvider.getObject();
            if (!bootstrapEnabled) {
                requireExistingCollection(milvusServiceClient, databaseName, collectionName);
            }
            // 向量维度变更时，旧 collection 继续复用会直接报错，这里启动时先做一次防御性检查。
            resetCollectionIfDimensionMismatch(milvusServiceClient, databaseName, collectionName,
                    embeddingDimension, bootstrapEnabled && properties.isInitializeSchema(),
                    bootstrapEnabled && aiAgentProperties.getRag().isAllowDestructiveCollectionReset());
            return MilvusVectorStore.builder(milvusServiceClient, embeddingModel)
                    .databaseName(databaseName)
                    .collectionName(collectionName)
                    .embeddingDimension(embeddingDimension)
                    .initializeSchema(bootstrapEnabled && properties.isInitializeSchema())
                    .batchingStrategy(fixedSizeBatchingStrategy(10))
                    .build();
        } catch (RuntimeException e) {
            if (aiAgentProperties.getBootstrap().isFailFast()) {
                throw e;
            }
            log.warn("Milvus collection {} is unavailable; using a process-local vector store", collectionName, e);
            return inMemoryVectorStore(embeddingModel, collectionName, "Milvus unavailable");
        }
    }

    private void requireExistingCollection(MilvusServiceClient client, String databaseName,
                                           String collectionName) {
        Boolean hasCollection = client.hasCollection(HasCollectionParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(collectionName)
                .build()).getData();
        if (!Boolean.TRUE.equals(hasCollection)) {
            throw new IllegalStateException("Milvus collection " + collectionName
                    + " 不存在，当前 bootstrap 已关闭，不能自动创建索引");
        }
    }

    private VectorStore inMemoryVectorStore(EmbeddingModel embeddingModel, String collectionName, String reason) {
        log.warn("Using process-local vector store for {} because {}", collectionName, reason);
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    private BatchingStrategy fixedSizeBatchingStrategy(int batchSize) {
        return documents -> {
            // 控制单批 embedding 的体积，避免一次塞太多文档把远程模型压超时。
            List<List<Document>> batches = new ArrayList<>();
            for (int i = 0; i < documents.size(); i += batchSize) {
                batches.add(documents.subList(i, Math.min(i + batchSize, documents.size())));
            }
            return batches;
        };
    }

    void resetCollectionIfDimensionMismatch(MilvusServiceClient client,
                                            String databaseName,
                                            String collectionName,
                                            int expectedDimension,
                                            boolean initializeSchema,
                                            boolean allowDestructiveReset) {
        try {
            Boolean hasCollection = client.hasCollection(HasCollectionParam.newBuilder()
                    .withDatabaseName(databaseName)
                    .withCollectionName(collectionName)
                    .build()).getData();
            if (!Boolean.TRUE.equals(hasCollection)) {
                return;
            }

            R<io.milvus.grpc.DescribeCollectionResponse> response = client.describeCollection(DescribeCollectionParam.newBuilder()
                    .withDatabaseName(databaseName)
                    .withCollectionName(collectionName)
                    .build());
            if (response.getData() == null || response.getData().getSchema() == null) {
                return;
            }

            Integer actualDimension = response.getData().getSchema().getFieldsList().stream()
                    .filter(field -> MilvusVectorStore.EMBEDDING_FIELD_NAME.equals(field.getName()))
                    .findFirst()
                    .map(this::extractVectorDimension)
                    .orElse(null);

            if (actualDimension == null || actualDimension == expectedDimension) {
                return;
            }

            if (!initializeSchema || !allowDestructiveReset) {
                throw new IllegalStateException("Milvus collection " + collectionName + " 的向量维度为 "
                        + actualDimension + "，但当前配置需要 " + expectedDimension
                        + "。自动删除旧 collection 默认关闭；备份并准备重建索引后，需同时开启 "
                        + "initialize-schema 和 ai.agent.rag.allow-destructive-collection-reset。");
            }

            // 删除已有数据需要 initialize-schema 与专用破坏性开关同时明确授权。
            log.warn("Milvus collection {} dimension mismatch: actual={}, expected={}. Dropping and recreating it.",
                    collectionName, actualDimension, expectedDimension);
            client.dropCollection(DropCollectionParam.newBuilder()
                    .withDatabaseName(databaseName)
                    .withCollectionName(collectionName)
                    .build());
        }
        catch (Exception e) {
            throw new IllegalStateException("检查 Milvus collection 维度失败: " + collectionName, e);
        }
    }

    private Integer extractVectorDimension(FieldSchema fieldSchema) {
        for (KeyValuePair typeParam : fieldSchema.getTypeParamsList()) {
            if ("dim".equals(typeParam.getKey())) {
                return Integer.valueOf(typeParam.getValue());
            }
        }
        return null;
    }

    private ConnectParam.Builder baseConnectParamBuilder(MilvusServiceClientProperties properties) {
        ConnectParam.Builder builder = ConnectParam.newBuilder();
        if (StringUtils.hasText(properties.getUri())) {
            builder.withUri(properties.getUri());
        }
        else {
            builder.withHost(properties.getHost()).withPort(properties.getPort());
        }
        if (StringUtils.hasText(properties.getToken())) {
            builder.withToken(properties.getToken());
        }
        if (StringUtils.hasText(properties.getUsername()) && StringUtils.hasText(properties.getPassword())) {
            builder.withAuthorization(properties.getUsername(), properties.getPassword());
        }
        if (properties.getConnectTimeoutMs() > 0) {
            builder.withConnectTimeout(properties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        if (properties.getKeepAliveTimeMs() > 0) {
            builder.withKeepAliveTime(properties.getKeepAliveTimeMs(), TimeUnit.MILLISECONDS);
        }
        if (properties.getKeepAliveTimeoutMs() > 0) {
            builder.withKeepAliveTimeout(properties.getKeepAliveTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        if (properties.getRpcDeadlineMs() > 0) {
            builder.withRpcDeadline(properties.getRpcDeadlineMs(), TimeUnit.MILLISECONDS);
        }
        if (properties.getIdleTimeoutMs() > 0) {
            builder.withIdleTimeout(properties.getIdleTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        builder.withSecure(properties.isSecure());
        return builder;
    }

    private void ensureDatabaseExists(MilvusServiceClient client, String databaseName) {
        try {
            R<ListDatabasesResponse> response = client.listDatabases();
            if (response.getData() != null && response.getData().getDbNamesList().contains(databaseName)) {
                return;
            }
            client.createDatabase(CreateDatabaseParam.newBuilder().withDatabaseName(databaseName).build());
            log.info("Created Milvus database: {}", databaseName);
        }
        catch (Exception e) {
            log.warn("Failed to create Milvus database {}, assuming it already exists: {}", databaseName, e.getMessage());
        }
    }

    private void closeQuietly(MilvusServiceClient client) {
        try {
            client.close(0L);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
