package com.multimodalAgent.agent.streaming.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.SpringAiModelToolDefinitionProjector;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingAgentModelAdapter;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingClient;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingOptions;
import com.multimodalAgent.agent.adapter.model.springai.streaming.SpringWebClientOpenAiCompatibleStreamingClient;
import com.multimodalAgent.agent.adapter.model.springai.streaming.StreamingModelInvocationScope;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.integration.CoordinatedAgentExecutionCoordinator;
import com.multimodalAgent.agent.coordination.integration.ExecutionCoordinationBoundaryMiddleware;
import com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdogFactory;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.integration.ExecutionPersistenceComposition;
import com.multimodalAgent.agent.persistence.integration.PersistentAgentExecutionCoordinator;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.event.AgentEventPublisher;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddleware;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.service.knowledge.KnowledgeService;
import com.multimodalAgent.agent.streaming.ExecutionStreamHub;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;
import com.multimodalAgent.agent.streaming.bridge.AgentEventStreamBridge;
import com.multimodalAgent.agent.tool.builtin.KnowledgeSearchTool;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Opt-in production composition of P8.2, P8.3, P6, and optional P7. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "multimodal-agent.runtime",
        name = "enabled",
        havingValue = "true"
)
public class StreamingAgentExecutionConfiguration {

    @Bean
    public StreamingModelInvocationScope agentStreamingModelInvocationScope() {
        return new StreamingModelInvocationScope();
    }

    @Bean
    public OpenAiCompatibleStreamingClient agentStreamingClient(
            multimodalAgentProperties properties,
            ObjectMapper objectMapper
    ) {
        String provider = properties.getAi().getProvider().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "ollama" -> new SpringWebClientOpenAiCompatibleStreamingClient(
                    properties.getAi().getOllama().getBaseUrl(),
                    null,
                    objectMapper
            );
            case "openai" -> new SpringWebClientOpenAiCompatibleStreamingClient(
                    properties.getAi().getOpenai().getBaseUrl(),
                    properties.getAi().getOpenai().getApiKey(),
                    objectMapper
            );
            default -> throw new IllegalStateException(
                    "Agent Runtime streaming requires AI_PROVIDER=ollama or openai"
            );
        };
    }

    @Bean
    public AgentModel agentStreamingModel(
            OpenAiCompatibleStreamingClient client,
            multimodalAgentProperties properties,
            ObjectMapper objectMapper,
            StreamingModelInvocationScope invocationScope
    ) {
        String provider = properties.getAi().getProvider().toLowerCase(Locale.ROOT);
        String modelName = "ollama".equals(provider)
                ? properties.getAi().getOllama().getModel()
                : properties.getAi().getOpenai().getModel();
        return new OpenAiCompatibleStreamingAgentModelAdapter(
                client,
                new OpenAiCompatibleStreamingOptions(
                        provider,
                        modelName,
                        properties.getAi().getTemperature(),
                        properties.getAi().getMaxTokens()
                ),
                objectMapper,
                invocationScope
        );
    }

    @Bean
    public StreamingAgentExecutionService streamingAgentExecutionService(
            AgentModel model,
            ObjectMapper objectMapper,
            Validator validator,
            KnowledgeService knowledgeService,
            StreamingModelInvocationScope invocationScope,
            ExecutionPersistenceComposition persistence,
            ExecutionStreamHub hub,
            ExecutionStreamPublisher publisher,
            ObjectProvider<RunLeaseStore> leaseStoreProvider,
            ObjectProvider<RunLeaseWatchdogFactory> watchdogFactoryProvider
    ) {
        ToolExecutor toolExecutor = new ToolExecutor(
                new ToolRegistry(List.of(new KnowledgeSearchTool(knowledgeService))),
                new ToolArgumentResolver(objectMapper, validator),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        AgentEventStreamBridge streamBridge = new AgentEventStreamBridge(publisher);
        AgentEventPublisher combinedPublisher = event -> {
            try {
                persistence.eventPublisher().publish(event);
            } finally {
                streamBridge.publish(event);
            }
        };
        AgentRunner runner = new AgentRunner(
                model,
                toolExecutor,
                new SpringAiModelToolDefinitionProjector(objectMapper),
                combinedPublisher
        );
        List<RuntimeMiddleware> middleware = new ArrayList<>();
        middleware.add(invocationScope);
        RunLeaseStore leaseStore = leaseStoreProvider.getIfAvailable();
        RunLeaseWatchdogFactory watchdogFactory = watchdogFactoryProvider.getIfAvailable();
        if ((leaseStore == null) != (watchdogFactory == null)) {
            throw new IllegalStateException("P7 lease store and watchdog must be configured together");
        }
        if (leaseStore != null) {
            middleware.add(new ExecutionCoordinationBoundaryMiddleware());
        }
        middleware.add(persistence.boundaryMiddleware());
        AgentExecutionCoordinator core = new AgentExecutionCoordinator(
                runner,
                new RuntimeMiddlewareChain(middleware)
        );
        PersistentAgentExecutionCoordinator persistent = persistence.persistentCoordinator(core);
        Function<AgentExecutionRequest, AgentRunResult> execution = leaseStore == null
                ? persistent::execute
                : new CoordinatedAgentExecutionCoordinator(
                        leaseStore,
                        watchdogFactory,
                        persistent
                )::execute;
        return new StreamingAgentExecutionService(execution, hub, publisher);
    }
}
