package com.multimodalAgent.agent.streaming.integration;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetProductionConfigurationTest {

    private final StreamingAgentExecutionConfiguration configuration =
            new StreamingAgentExecutionConfiguration();

    @Test
    void unsetProductionPropertiesCreateUnlimitedBudget() {
        ExecutionBudget budget = configuration.agentExecutionBudget(
                new multimodalAgentProperties()
        );

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

        ExecutionBudget budget = configuration.agentExecutionBudget(properties);

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
}
