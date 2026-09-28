package com.multimodalAgent.agent.recovery.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.multimodalAgent.agent.recovery.BudgetCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointCorruptionException;
import com.multimodalAgent.agent.runtime.model.AgentMessage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Versioned deterministic JSON codec kept outside Runtime Core. */
final class RecoveryCheckpointJsonCodec {

    private final ObjectMapper objectMapper;

    RecoveryCheckpointJsonCodec(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.objectMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    String encode(RecoveryCheckpoint checkpoint) {
        requireSupported(checkpoint.schemaVersion(), checkpoint.checkpointId());
        StatePayload state = new StatePayload(
                checkpoint.schemaVersion(),
                checkpoint.messages(),
                checkpoint.toolsUsed(),
                sorted(checkpoint.seenToolCallIds()),
                BudgetPayload.from(checkpoint.budgetUsage()),
                sorted(checkpoint.approvedToolCallIds())
        );
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new RecoveryCheckpointCorruptionException(
                    "Could not serialize recovery checkpoint " + checkpoint.checkpointId(),
                    exception
            );
        }
    }

    DecodedState decode(String checkpointId, int schemaVersion, String stateJson) {
        requireSupported(schemaVersion, checkpointId);
        if (stateJson == null || stateJson.isBlank()) {
            throw new RecoveryCheckpointCorruptionException(
                    "Recovery checkpoint state JSON is empty: " + checkpointId
            );
        }
        try {
            StatePayload state = objectMapper.readValue(stateJson, StatePayload.class);
            if (state.schemaVersion() != schemaVersion) {
                throw new RecoveryCheckpointCorruptionException(
                        "Recovery checkpoint schema does not match state JSON: " + checkpointId
                );
            }
            BudgetPayload budget = Objects.requireNonNull(
                    state.budgetUsage(),
                    "budgetUsage must not be null"
            );
            return new DecodedState(
                    state.messages(),
                    state.toolsUsed(),
                    new LinkedHashSet<>(state.seenToolCallIds()),
                    budget.toDomain(),
                    new LinkedHashSet<>(state.approvedToolCallIds())
            );
        } catch (RecoveryCheckpointCorruptionException exception) {
            throw exception;
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new RecoveryCheckpointCorruptionException(
                    "Could not deserialize recovery checkpoint " + checkpointId,
                    exception
            );
        }
    }

    private void requireSupported(int schemaVersion, String checkpointId) {
        if (schemaVersion != RecoveryCheckpoint.CURRENT_SCHEMA_VERSION) {
            throw new RecoveryCheckpointCorruptionException(
                    "Unsupported recovery checkpoint schema " + schemaVersion
                            + " for " + checkpointId
            );
        }
    }

    private List<String> sorted(Iterable<String> values) {
        List<String> sorted = new ArrayList<>();
        values.forEach(sorted::add);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    record DecodedState(
            List<AgentMessage> messages,
            List<String> toolsUsed,
            LinkedHashSet<String> seenToolCallIds,
            BudgetCheckpoint budgetUsage,
            LinkedHashSet<String> approvedToolCallIds
    ) {
    }

    private record StatePayload(
            int schemaVersion,
            List<AgentMessage> messages,
            List<String> toolsUsed,
            List<String> seenToolCallIds,
            BudgetPayload budgetUsage,
            List<String> approvedToolCallIds
    ) {
    }

    private record BudgetPayload(
            long modelCalls,
            long toolCalls,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            BigDecimal cost,
            boolean unknownUsageObserved
    ) {

        static BudgetPayload from(BudgetCheckpoint checkpoint) {
            return new BudgetPayload(
                    checkpoint.modelCalls(), checkpoint.toolCalls(), checkpoint.inputTokens(),
                    checkpoint.outputTokens(), checkpoint.totalTokens(),
                    checkpoint.cost().orElse(null), checkpoint.unknownUsageObserved()
            );
        }

        BudgetCheckpoint toDomain() {
            return new BudgetCheckpoint(
                    modelCalls, toolCalls, inputTokens, outputTokens, totalTokens,
                    Optional.ofNullable(cost), unknownUsageObserved
            );
        }
    }
}
