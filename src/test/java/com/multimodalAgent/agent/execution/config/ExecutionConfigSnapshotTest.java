package com.multimodalAgent.agent.execution.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.budget.ModelPricing;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ExecutionConfigSnapshotTest {

    private static final ModelIdentity IDENTITY = new ModelIdentity("ollama", "mindbridge");
    private static final ModelTimeoutPolicy TIMEOUTS = new ModelTimeoutPolicy(
            Duration.ofMinutes(2), Duration.ofSeconds(30)
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutionConfigSnapshotFactory factory =
            new ExecutionConfigSnapshotFactory(objectMapper);

    @Test
    void sameSemanticConfigurationHasTheSameCanonicalIdentity() throws Exception {
        ResolvedExecutionConfig first = config(
                new ResolvedModelConfig(IDENTITY, new BigDecimal("0.3500"), 512, TIMEOUTS),
                4,
                List.of("knowledge_search", "appointment_create"),
                fullBudget(new BigDecimal("0.2500"))
        );
        ResolvedExecutionConfig second = config(
                new ResolvedModelConfig(IDENTITY, new BigDecimal("0.35"), 512, TIMEOUTS),
                4,
                List.of("appointment_create", "knowledge_search", "knowledge_search"),
                fullBudget(new BigDecimal("0.25"))
        );

        ExecutionConfigSnapshot firstSnapshot = factory.create(first);
        ExecutionConfigSnapshot secondSnapshot = factory.create(second);

        assertEquals(firstSnapshot, secondSnapshot);
        JsonNode json = objectMapper.readTree(firstSnapshot.configJson());
        assertEquals(
                List.of("appointment_create", "knowledge_search"),
                objectMapper.convertValue(json.at("/runtime/allowedTools"), List.class)
        );
        assertEquals("0.35", json.at("/model/temperature").asText());
        assertEquals("PT2M", json.at("/model/invocationTimeout").asText());
    }

    @Test
    void everyEffectiveConfigurationDimensionContributesToTheHash() {
        ExecutionBudget budget = fullBudget(new BigDecimal("0.25"));
        ResolvedModelConfig model = new ResolvedModelConfig(
                IDENTITY, new BigDecimal("0.35"), 512, TIMEOUTS
        );
        ExecutionConfigSnapshot baseline = factory.create(config(
                model, 4, List.of("knowledge_search"), budget
        ));

        List<ResolvedExecutionConfig> variants = List.of(
                config(new ResolvedModelConfig(
                        new ModelIdentity("openai", "gpt-4o-mini"),
                        new BigDecimal("0.35"), 512, TIMEOUTS
                ), 4, List.of("knowledge_search"), budget),
                config(new ResolvedModelConfig(
                        new ModelIdentity("ollama", "another-model"),
                        new BigDecimal("0.35"), 512, TIMEOUTS
                ), 4, List.of("knowledge_search"), budget),
                config(new ResolvedModelConfig(
                        IDENTITY, new BigDecimal("0.4"), 512, TIMEOUTS
                ), 4, List.of("knowledge_search"), budget),
                config(new ResolvedModelConfig(
                        IDENTITY, new BigDecimal("0.35"), 1024, TIMEOUTS
                ), 4, List.of("knowledge_search"), budget),
                config(new ResolvedModelConfig(
                        IDENTITY, new BigDecimal("0.35"), 512,
                        new ModelTimeoutPolicy(Duration.ofMinutes(3), Duration.ofSeconds(30))
                ), 4, List.of("knowledge_search"), budget),
                config(new ResolvedModelConfig(
                        IDENTITY, new BigDecimal("0.35"), 512,
                        new ModelTimeoutPolicy(Duration.ofMinutes(2), Duration.ofSeconds(20))
                ), 4, List.of("knowledge_search"), budget),
                config(model, 5, List.of("knowledge_search"), budget),
                config(model, 4, List.of("knowledge_search", "appointment_create"), budget),
                config(model, 4, List.of("knowledge_search"),
                        budget(3, 3, 100, 50, 150, "0.25", IDENTITY, "1.50", "4.50")),
                config(model, 4, List.of("knowledge_search"),
                        budget(2, 4, 100, 50, 150, "0.25", IDENTITY, "1.50", "4.50")),
                config(model, 4, List.of("knowledge_search"),
                        budget(2, 3, 101, 50, 150, "0.25", IDENTITY, "1.50", "4.50")),
                config(model, 4, List.of("knowledge_search"),
                        budget(2, 3, 100, 51, 150, "0.25", IDENTITY, "1.50", "4.50")),
                config(model, 4, List.of("knowledge_search"),
                        budget(2, 3, 100, 50, 151, "0.25", IDENTITY, "1.50", "4.50")),
                config(model, 4, List.of("knowledge_search"),
                        fullBudget(new BigDecimal("0.30"))),
                config(model, 4, List.of("knowledge_search"),
                        budget(2, 3, 100, 50, 150, "0.25", IDENTITY, "2.00", "4.50")),
                config(model, 4, List.of("knowledge_search"),
                        budget(2, 3, 100, 50, 150, "0.25", IDENTITY, "1.50", "5.00")),
                config(model, 4, List.of("knowledge_search"),
                        budget(2, 3, 100, 50, 150, "0.25",
                                new ModelIdentity("ollama", "another-model"),
                                "1.50", "4.50"))
        );

        for (ResolvedExecutionConfig variant : variants) {
            ExecutionConfigSnapshot changed = factory.create(variant);
            assertNotEquals(baseline.configHash(), changed.configHash());
            assertNotEquals(baseline.snapshotId(), changed.snapshotId());
        }
    }

    @Test
    void resolverUsesTheActualRunSpecRatherThanRebuildingRuntimeSettings() {
        ResolvedModelConfig model = new ResolvedModelConfig(
                IDENTITY, new BigDecimal("0.35"), 512, TIMEOUTS
        );
        ExecutionBudget budget = fullBudget(new BigDecimal("0.25"));
        AgentExecutionRequest request = request(7, Set.of("z_tool", "a_tool"), budget);

        ResolvedExecutionConfig resolved =
                new ResolvedExecutionConfigResolver(model).resolve(request);

        assertEquals(model, resolved.model());
        assertEquals(7, resolved.runtime().maxIterations());
        assertEquals(List.of("a_tool", "z_tool"), resolved.runtime().allowedTools());
        assertEquals(budget, resolved.budget());
    }

    private ResolvedExecutionConfig config(
            ResolvedModelConfig model,
            int maxIterations,
            List<String> allowedTools,
            ExecutionBudget budget
    ) {
        return new ResolvedExecutionConfig(
                ResolvedExecutionConfig.CURRENT_SCHEMA_VERSION,
                model,
                new ResolvedExecutionConfig.RuntimeConfig(maxIterations, allowedTools),
                budget
        );
    }

    private ExecutionBudget fullBudget(BigDecimal maxCost) {
        return budget(
                2, 3, 100, 50, 150, maxCost.toPlainString(),
                IDENTITY, "1.50", "4.50"
        );
    }

    private ExecutionBudget budget(
            long maxModelCalls,
            long maxToolCalls,
            long maxInputTokens,
            long maxOutputTokens,
            long maxTotalTokens,
            String maxCost,
            ModelIdentity pricingIdentity,
            String inputPrice,
            String outputPrice
    ) {
        return ExecutionBudget.builder()
                .maxModelCalls(maxModelCalls)
                .maxToolCalls(maxToolCalls)
                .maxInputTokens(maxInputTokens)
                .maxOutputTokens(maxOutputTokens)
                .maxTotalTokens(maxTotalTokens)
                .maxCost(new BigDecimal(maxCost))
                .pricing(new ModelPricing(
                        pricingIdentity, new BigDecimal(inputPrice), new BigDecimal(outputPrice)
                ))
                .build();
    }

    private AgentExecutionRequest request(
            int maxIterations,
            Set<String> allowedTools,
            ExecutionBudget budget
    ) {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        "snapshot-run",
                        "snapshot-session",
                        List.of(AgentMessage.user("test")),
                        maxIterations,
                        allowedTools,
                        Set.of(),
                        budget
                ),
                "snapshot-request",
                9001L
        );
    }
}
