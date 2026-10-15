package com.multimodalAgent.agent.config;

import com.multimodalAgent.agent.service.knowledge.EmbeddingClient;
import com.multimodalAgent.agent.service.knowledge.OllamaEmbeddingClient;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
/**
 * RAG 本地向量化客户端配置。
 *
 * <p>业务层保留 {@link EmbeddingClient} 边界，底层使用 Spring AI 的
 * {@link OllamaEmbeddingModel} 调用本地 Ollama。</p>
 */
public class EmbeddingConfig {

    @Bean
    public EmbeddingModel ollamaEmbeddingModel(
            multimodalAgentProperties properties,
            ObservationRegistry observationRegistry
    ) {
        multimodalAgentProperties.Embedding embedding = properties.getEmbedding();
        OllamaApi api = KnowledgeRetrievalConfig.ollamaApi(
                embedding.getBaseUrl(), embedding.getTimeout()
        );
        OllamaOptions options = OllamaOptions.builder()
                .model(embedding.getModel())
                .truncate(true)
                .build();
        return OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .defaultOptions(options)
                .observationRegistry(observationRegistry)
                .build();
    }

    @Bean
    public EmbeddingClient embeddingClient(
            EmbeddingModel ollamaEmbeddingModel,
            multimodalAgentProperties properties
    ) {
        return new OllamaEmbeddingClient(
                ollamaEmbeddingModel, properties.getEmbedding().getModel()
        );
    }
}
