package com.multimodalAgent.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.service.knowledge.OllamaModelReranker;
import com.multimodalAgent.agent.service.knowledge.PassThroughReranker;
import com.multimodalAgent.agent.service.knowledge.Reranker;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

/** Runtime wiring for parallel hybrid retrieval and optional local model reranking. */
@Configuration
public class KnowledgeRetrievalConfig {

    @Bean("hybridRetrievalExecutor")
    public Executor hybridRetrievalExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("hybrid-retrieval-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "multimodal-agent.knowledge.rerank",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    public Reranker passThroughReranker() {
        return new PassThroughReranker();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "multimodal-agent.knowledge.rerank",
            name = "enabled",
            havingValue = "true"
    )
    public Reranker ollamaModelReranker(
            multimodalAgentProperties properties,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry
    ) {
        multimodalAgentProperties.Rerank rerank = properties.getKnowledge().getRerank();
        if (rerank.getModel().isBlank()) {
            throw new IllegalStateException(
                    "RAG_RERANK_ENABLED=true requires OLLAMA_RERANK_MODEL"
            );
        }
        OllamaApi api = ollamaApi(
                properties.getAi().getOllama().getBaseUrl(), rerank.getTimeout()
        );
        OllamaOptions options = OllamaOptions.builder()
                .model(rerank.getModel())
                .temperature(0.0)
                .numPredict(1024)
                .build();
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(options)
                .observationRegistry(observationRegistry)
                .build();
        return new OllamaModelReranker(model, objectMapper, rerank.getMaxDocumentChars());
    }

    static OllamaApi ollamaApi(String baseUrl, Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
    }
}
