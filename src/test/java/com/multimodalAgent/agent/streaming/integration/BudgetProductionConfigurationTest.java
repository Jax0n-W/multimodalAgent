package com.multimodalAgent.agent.streaming.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingClient;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshot;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotFactory;
import com.multimodalAgent.agent.execution.config.ResolvedExecutionConfig;
import com.multimodalAgent.agent.execution.config.ResolvedExecutionConfigResolver;
import com.multimodalAgent.agent.execution.config.ResolvedModelConfig;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelInvocationTelemetrySink;
import com.multimodalAgent.agent.runtime.model.gateway.ModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetProductionConfigurationTest {

    private final StreamingAgentExecutionConfiguration configuration =
            new StreamingAgentExecutionConfiguration();

    @Test
    void unsetProductionPropertiesCreateUnlimitedBudget() {
        multimodalAgentProperties properties = new multimodalAgentProperties();
        ResolvedModelConfig modelConfig = configuration.agentResolvedModelConfig(properties);
        ExecutionBudget budget = configuration.agentExecutionBudget(properties, modelConfig);

        assertFalse(budget.maxModelCalls().isPresent());
        assertFalse(budget.maxToolCalls().isPresent());
        assertFalse(budget.maxInputTokens().isPresent());
        assertFalse(budget.maxOutputTokens().isPresent());
        assertFalse(budget.maxTotalTokens().isPresent());
        assertTrue(budget.maxCost().isEmpty());
        assertTrue(budget.pricing().isEmpty());
    }

    @Test
    void explicitPropertiesMapToRunBudgetAndConfiguredModelIdentity() {
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getRuntime().getBudget().setMaxModelCalls(2L);
        properties.getRuntime().getBudget().setMaxToolCalls(3L);
        properties.getRuntime().getBudget().setMaxInputTokens(100L);
        properties.getRuntime().getBudget().setMaxOutputTokens(50L);
        properties.getRuntime().getBudget().setMaxTotalTokens(150L);
        properties.getRuntime().getBudget().setMaxCost(new BigDecimal("0.25"));
        properties.getRuntime().getBudget().getPricing()
                .setInputCostPerMillionTokens(new BigDecimal("1.50"));
        properties.getRuntime().getBudget().getPricing()
                .setOutputCostPerMillionTokens(new BigDecimal("4.50"));

        ResolvedModelConfig modelConfig = configuration.agentResolvedModelConfig(properties);
        ExecutionBudget budget = configuration.agentExecutionBudget(properties, modelConfig);

        assertEquals(2L, budget.maxModelCalls().orElseThrow());
        assertEquals(3L, budget.maxToolCalls().orElseThrow());
        assertEquals(100L, budget.maxInputTokens().orElseThrow());
        assertEquals(50L, budget.maxOutputTokens().orElseThrow());
        assertEquals(150L, budget.maxTotalTokens().orElseThrow());
        assertEquals(new BigDecimal("0.25"), budget.maxCost().orElseThrow());
        assertEquals(
                new ModelIdentity(
                        "ollama", properties.getAi().getOllama().getModel()
                ),
                budget.pricing().orElseThrow().identity()
        );
    }

    @Test
    void productionSnapshotAndGatewayShareTheExactResolvedExecutionSettings() {
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getAi().setProvider("openai");
        properties.getAi().getOpenai().setModel("gpt-snapshot-test");
        properties.getAi().getOpenai().setApiKey("SUPER_SECRET_TEST_KEY");
        properties.getAi().setTemperature(0.2);
        properties.getAi().setMaxTokens(777);
        properties.getAi().setInvocationTimeout(Duration.ofSeconds(90));
        properties.getAi().setIdleTimeout(Duration.ofSeconds(15));
        properties.getRuntime().getBudget().setMaxModelCalls(4L);
        properties.getRuntime().getBudget().setMaxToolCalls(6L);
        properties.getRuntime().getBudget().getPricing()
                .setInputCostPerMillionTokens(new BigDecimal("1.25"));
        properties.getRuntime().getBudget().getPricing()
                .setOutputCostPerMillionTokens(new BigDecimal("3.75"));

        ResolvedModelConfig modelConfig = configuration.agentResolvedModelConfig(properties);
        ExecutionBudget budget = configuration.agentExecutionBudget(properties, modelConfig);
        ResolvedExecutionConfigResolver resolver =
                configuration.agentResolvedExecutionConfigResolver(modelConfig);
        AgentExecutionRequest request = new AgentExecutionRequest(
                new AgentRunSpec(
                        "production-snapshot-run",
                        "production-snapshot-session",
                        List.of(AgentMessage.user("test")),
                        9,
                        Set.of("z_tool", "a_tool"),
                        Set.of(),
                        budget
                ),
                "production-snapshot-request",
                9001L
        );
        ResolvedExecutionConfig resolved = resolver.resolve(request);
        ExecutionConfigSnapshot snapshot = new ExecutionConfigSnapshotFactory(
                new ObjectMapper()
        ).create(resolved);

        OpenAiCompatibleStreamingClient client = ignored -> Flux.empty();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        ModelGateway gateway = (ModelGateway) configuration.agentStreamingModel(
                client,
                modelConfig,
                new ObjectMapper(),
                new com.multimodalAgent.agent.adapter.model.springai.streaming.StreamingModelInvocationScope(),
                beanFactory.getBeanProvider(ModelInvocationTelemetrySink.class)
        );

        assertEquals(modelConfig.identity(), gateway.identity());
        assertEquals(modelConfig.timeoutPolicy(), gateway.timeoutPolicy());
        assertEquals(modelConfig, resolved.model());
        assertEquals(budget, resolved.budget());
        assertEquals(9, resolved.runtime().maxIterations());
        assertEquals(List.of("a_tool", "z_tool"), resolved.runtime().allowedTools());
        assertFalse(snapshot.configJson().contains("SUPER_SECRET_TEST_KEY"));
        assertFalse(snapshot.configJson().contains("apiKey"));
        assertFalse(snapshot.configJson().contains("Authorization"));
        assertFalse(snapshot.configJson().contains("credential"));
        assertFalse(snapshot.configJson().contains("password"));
    }
}
