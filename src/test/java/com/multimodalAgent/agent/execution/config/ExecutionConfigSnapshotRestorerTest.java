package com.multimodalAgent.agent.execution.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.budget.ModelPricing;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionConfigSnapshotRestorerTest {

    @Test
    void restoresTheOriginalCanonicalConfiguration() {
        ObjectMapper mapper = new ObjectMapper();
        ResolvedExecutionConfig original = config();
        ExecutionConfigSnapshot snapshot = new ExecutionConfigSnapshotFactory(mapper)
                .create(original);

        assertEquals(original, new ExecutionConfigSnapshotRestorer(mapper).restore(snapshot));
    }

    @Test
    void rejectsJsonWhoseContentDoesNotMatchTheStoredShaIdentity() {
        ObjectMapper mapper = new ObjectMapper();
        ExecutionConfigSnapshot original = new ExecutionConfigSnapshotFactory(mapper)
                .create(config());
        ExecutionConfigSnapshot tampered = new ExecutionConfigSnapshot(
                original.snapshotId(), original.schemaVersion(), original.configHash(),
                original.configJson().replace("\"maxIterations\":3", "\"maxIterations\":4")
        );

        assertThrows(
                ExecutionConfigSnapshotException.class,
                () -> new ExecutionConfigSnapshotRestorer(mapper).restore(tampered)
        );
    }

    private ResolvedExecutionConfig config() {
        ModelIdentity identity = new ModelIdentity("openai", "historic-model");
        return new ResolvedExecutionConfig(
                1,
                new ResolvedModelConfig(
                        identity, new BigDecimal("0.2"), 512,
                        new ModelTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(5))
                ),
                new ResolvedExecutionConfig.RuntimeConfig(3, List.of("b", "a")),
                ExecutionBudget.builder()
                        .maxModelCalls(4).maxToolCalls(6)
                        .maxCost(new BigDecimal("2.5"))
                        .pricing(new ModelPricing(
                                identity, new BigDecimal("1.25"), new BigDecimal("3.75")
                        ))
                        .build()
        );
    }
}
