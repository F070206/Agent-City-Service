package com.hmdp.ai.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.CollectionSchema;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.KeyValuePair;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.HasCollectionParam;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommonConfigurationTest {

    @Test
    void disabledBootstrapUsesLocalVectorStoresWithoutRequestingMilvus() {
        CommonConfiguration configuration = new CommonConfiguration();
        @SuppressWarnings("unchecked")
        ObjectProvider<MilvusServiceClient> clientProvider = mock(ObjectProvider.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        MilvusVectorStoreProperties storeProperties = new MilvusVectorStoreProperties();
        AiAgentProperties aiAgentProperties = new AiAgentProperties();
        aiAgentProperties.getBootstrap().setEnabled(false);
        aiAgentProperties.getRag().setVectorStoreEnabled(false);

        assertLocal(configuration.knowledgeVectorStore(clientProvider, embeddingModel, storeProperties, aiAgentProperties));
        assertLocal(configuration.shopProfileVectorStore(clientProvider, embeddingModel, storeProperties, aiAgentProperties));
        assertLocal(configuration.blogReviewVectorStore(clientProvider, embeddingModel, storeProperties, aiAgentProperties));
        verifyNoInteractions(clientProvider);
    }

    @Test
    void disabledBootstrapUsesExistingMilvusCollectionWithoutInitializingSchema() {
        CommonConfiguration configuration = new CommonConfiguration();
        MilvusServiceClient client = clientWithDimension(1024);
        @SuppressWarnings("unchecked")
        ObjectProvider<MilvusServiceClient> clientProvider = mock(ObjectProvider.class);
        when(clientProvider.getObject()).thenReturn(client);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        MilvusVectorStoreProperties storeProperties = new MilvusVectorStoreProperties();
        storeProperties.setEmbeddingDimension(1024);
        storeProperties.setInitializeSchema(true);
        AiAgentProperties aiAgentProperties = new AiAgentProperties();
        aiAgentProperties.getBootstrap().setEnabled(false);

        VectorStore vectorStore = configuration.knowledgeVectorStore(
                clientProvider, embeddingModel, storeProperties, aiAgentProperties);

        assertInstanceOf(MilvusVectorStore.class, vectorStore);
        verify(client, never()).dropCollection(any(DropCollectionParam.class));
        verify(client, never()).createCollection(any(CreateCollectionParam.class));
    }

    @Test
    void disabledBootstrapDoesNotCreateAMissingCollection() {
        CommonConfiguration configuration = new CommonConfiguration();
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any(HasCollectionParam.class))).thenReturn(R.success(false));
        @SuppressWarnings("unchecked")
        ObjectProvider<MilvusServiceClient> clientProvider = mock(ObjectProvider.class);
        when(clientProvider.getObject()).thenReturn(client);
        AiAgentProperties aiAgentProperties = new AiAgentProperties();
        aiAgentProperties.getBootstrap().setEnabled(false);

        VectorStore vectorStore = configuration.knowledgeVectorStore(clientProvider, mock(EmbeddingModel.class),
                new MilvusVectorStoreProperties(), aiAgentProperties);

        assertLocal(vectorStore);
        verify(client).hasCollection(any(HasCollectionParam.class));
        verify(client, never()).dropCollection(any(DropCollectionParam.class));
        verify(client, never()).createCollection(any(CreateCollectionParam.class));
    }

    @Test
    void unavailableMilvusFallsBackUnlessFailFastIsEnabled() {
        CommonConfiguration configuration = new CommonConfiguration();
        @SuppressWarnings("unchecked")
        ObjectProvider<MilvusServiceClient> clientProvider = mock(ObjectProvider.class);
        when(clientProvider.getObject()).thenThrow(new IllegalStateException("Milvus unavailable"));
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        MilvusVectorStoreProperties storeProperties = new MilvusVectorStoreProperties();
        AiAgentProperties aiAgentProperties = new AiAgentProperties();

        assertLocal(configuration.knowledgeVectorStore(clientProvider, embeddingModel, storeProperties, aiAgentProperties));

        aiAgentProperties.getBootstrap().setFailFast(true);
        assertThrows(IllegalStateException.class,
                () -> configuration.knowledgeVectorStore(clientProvider, embeddingModel, storeProperties, aiAgentProperties));
    }

    @Test
    void dimensionMismatchDoesNotDropCollectionByDefault() {
        CommonConfiguration configuration = new CommonConfiguration();
        MilvusServiceClient client = clientWithDimension(768);

        assertThrows(IllegalStateException.class, () -> configuration.resetCollectionIfDimensionMismatch(
                client, "default", "knowledge_vector", 1024, true, false));

        verify(client, never()).dropCollection(any(DropCollectionParam.class));
    }

    @Test
    void dimensionMismatchDropsCollectionOnlyWithExplicitResetAuthorization() {
        CommonConfiguration configuration = new CommonConfiguration();
        MilvusServiceClient client = clientWithDimension(768);
        when(client.dropCollection(any(DropCollectionParam.class))).thenReturn(R.success());

        configuration.resetCollectionIfDimensionMismatch(client,
                "default", "knowledge_vector", 1024, true, true);

        verify(client).dropCollection(any(DropCollectionParam.class));
    }

    @Test
    void destructiveResetStillRequiresSchemaInitializationEnabled() {
        CommonConfiguration configuration = new CommonConfiguration();
        MilvusServiceClient client = clientWithDimension(768);

        assertThrows(IllegalStateException.class, () -> configuration.resetCollectionIfDimensionMismatch(
                client, "default", "knowledge_vector", 1024, false, true));

        verify(client, never()).dropCollection(any(DropCollectionParam.class));
    }

    private MilvusServiceClient clientWithDimension(int dimension) {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any(HasCollectionParam.class))).thenReturn(R.success(true));
        FieldSchema embeddingField = FieldSchema.newBuilder()
                .setName(org.springframework.ai.vectorstore.milvus.MilvusVectorStore.EMBEDDING_FIELD_NAME)
                .addTypeParams(KeyValuePair.newBuilder().setKey("dim").setValue(Integer.toString(dimension)))
                .build();
        DescribeCollectionResponse response = DescribeCollectionResponse.newBuilder()
                .setSchema(CollectionSchema.newBuilder().addFields(embeddingField))
                .build();
        when(client.describeCollection(any(DescribeCollectionParam.class))).thenReturn(R.success(response));
        return client;
    }

    private static void assertLocal(VectorStore vectorStore) {
        assertInstanceOf(SimpleVectorStore.class, vectorStore);
    }
}
