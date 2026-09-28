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
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotFactory;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotStore;
import com.multimodalAgent.agent.execution.config.ResolvedExecutionConfigResolver;
import com.multimodalAgent.agent.execution.config.ResolvedModelConfig;
import com.multimodalAgent.agent.execution.config.SnapshottingAgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.integration.ExecutionPersistenceComposition;
import com.multimodalAgent.agent.persistence.integration.PersistentAgentExecutionCoordinator;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointStore;
import com.multimodalAgent.agent.recovery.integration.RecoveryCheckpointRuntimeMiddleware;
import com.multimodalAgent.agent.recovery.integration.RecoveryCheckpointingAgentExecutionCoordinator;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.budget.ModelPricing;
import com.multimodalAgent.agent.runtime.event.AgentEventPublisher;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddleware;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.gateway.ModelGateway;
import com.multimodalAgent.agent.runtime.model.gateway.GovernedAgentModel;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelInvocationTelemetrySink;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/** Opt-in production composition of P8.2–P8.4, P6, and optional P7. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "multimodal-agent.runtime",
        name = "enabled",
        havingValue = "true"
)
public class StreamingAgentExecutionConfiguration {

    @Bean
    public ResolvedModelConfig agentResolvedModelConfig(multimodalAgentProperties properties) {
        String provider = properties.getAi().getProvider().toLowerCase(Locale.ROOT);
        String modelName = "ollama".equals(provider)
                ? properties.getAi().getOllama().getModel()
                : properties.getAi().getOpenai().getModel();
        return new ResolvedModelConfig(
                new ModelIdentity(provider, modelName),
                BigDecimal.valueOf(properties.getAi().getTemperature()),
                properties.getAi().getMaxTokens(),
                new ModelTimeoutPolicy(
                        properties.getAi().getInvocationTimeout(),
                        properties.getAi().getIdleTimeout()
                )
        );
    }

    @Bean
    public ExecutionBudget agentExecutionBudget(
            multimodalAgentProperties properties,
            ResolvedModelConfig modelConfig
    ) {
        multimodalAgentProperties.Budget configured = properties.getRuntime().getBudget();
        ExecutionBudget.Builder builder = ExecutionBudget.builder();
        if (configured.getMaxModelCalls() != null) {
            builder.maxModelCalls(configured.getMaxModelCalls());
        }
        if (configured.getMaxToolCalls() != null) {
            builder.maxToolCalls(configured.getMaxToolCalls());
        }
        if (configured.getMaxInputTokens() != null) {
            builder.maxInputTokens(configured.getMaxInputTokens());
        }
        if (configured.getMaxOutputTokens() != null) {
            builder.maxOutputTokens(configured.getMaxOutputTokens());
        }
        if (configured.getMaxTotalTokens() != null) {
            builder.maxTotalTokens(configured.getMaxTotalTokens());
        }
        if (configured.getMaxCost() != null) {
            builder.maxCost(configured.getMaxCost());
        }

        multimodalAgentProperties.Pricing pricing = configured.getPricing();
        boolean hasInputPrice = pricing.getInputCostPerMillionTokens() != null;
        boolean hasOutputPrice = pricing.getOutputCostPerMillionTokens() != null;
        if (hasInputPrice != hasOutputPrice) {
            throw new IllegalStateException(
                    "Both input and output model prices must be configured together"
            );
        }
        if (hasInputPrice) {
            builder.pricing(new ModelPricing(
                    modelConfig.identity(),
                    pricing.getInputCostPerMillionTokens(),
                    pricing.getOutputCostPerMillionTokens()
            ));
        }
        return builder.build();
    }

    @Bean
    public ResolvedExecutionConfigResolver agentResolvedExecutionConfigResolver(
            ResolvedModelConfig modelConfig
    ) {
        return new ResolvedExecutionConfigResolver(modelConfig);
    }

    @Bean
    public ExecutionConfigSnapshotFactory agentExecutionConfigSnapshotFactory(
            ObjectMapper objectMapper
    ) {
        return new ExecutionConfigSnapshotFactory(objectMapper);
    }

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
    public ModelGateway agentStreamingModel(
            OpenAiCompatibleStreamingClient client,
            ResolvedModelConfig modelConfig,
            ObjectMapper objectMapper,
            StreamingModelInvocationScope invocationScope,
            ObjectProvider<ModelInvocationTelemetrySink> telemetryProvider
    ) {
        AgentModel adapter = new OpenAiCompatibleStreamingAgentModelAdapter(
                client,
                new OpenAiCompatibleStreamingOptions(
                        modelConfig.identity().provider(),
                        modelConfig.identity().model(),
                        modelConfig.temperature().doubleValue(),
                        modelConfig.maxTokensPerInvocation()
                ),
                objectMapper,
                invocationScope
        );
        return new ModelGateway(
                adapter,
                modelConfig.identity(),
                modelConfig.timeoutPolicy(),
                telemetryProvider.getIfAvailable(() -> ModelInvocationTelemetrySink.NOOP)
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
            LocalExecutionControlRegistry controls,
            ResolvedExecutionConfigResolver configResolver,
            ExecutionConfigSnapshotFactory snapshotFactory,
            ExecutionConfigSnapshotStore snapshotStore,
            RecoveryCheckpointStore recoveryCheckpointStore,
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
        middleware.add(new RecoveryCheckpointRuntimeMiddleware(persistence::assertHealthy));
        middleware.add(persistence.boundaryMiddleware());
        AgentExecutionCoordinator core = new AgentExecutionCoordinator(
                runner,
                new RuntimeMiddlewareChain(middleware)
        );
        PersistentAgentExecutionCoordinator persistent = persistence.persistentCoordinator(core);
        Function<AgentExecutionRequest, AgentRunResult> governedExecution = leaseStore == null
                ? persistent::execute
                : new CoordinatedAgentExecutionCoordinator(
                        leaseStore,
                        watchdogFactory,
                        persistent
                 )::execute;
        Optional<ModelIdentity> recoveryModelIdentity = model instanceof GovernedAgentModel governed
                ? governed.modelIdentity()
                : Optional.empty();
        RecoveryCheckpointingAgentExecutionCoordinator checkpointing =
                new RecoveryCheckpointingAgentExecutionCoordinator(
                        recoveryCheckpointStore,
                        recoveryModelIdentity,
                        governedExecution
                );
        SnapshottingAgentExecutionCoordinator snapshotting =
                new SnapshottingAgentExecutionCoordinator(
                        configResolver,
                        snapshotFactory,
                        snapshotStore,
                        checkpointing::execute
                );
        return new StreamingAgentExecutionService(
                snapshotting::execute,
                hub,
                publisher,
                controls
        );
    }
}
