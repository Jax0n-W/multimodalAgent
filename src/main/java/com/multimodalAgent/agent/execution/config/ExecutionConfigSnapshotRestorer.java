package com.multimodalAgent.agent.execution.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.budget.ModelPricing;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Verifies and decodes the original canonical P9.3 snapshot. */
public final class ExecutionConfigSnapshotRestorer {

    private final ObjectMapper objectMapper;
    private final ExecutionConfigSnapshotFactory factory;

    public ExecutionConfigSnapshotRestorer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.factory = new ExecutionConfigSnapshotFactory(objectMapper);
    }

    public ResolvedExecutionConfig restore(ExecutionConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        try {
            JsonNode root = objectMapper.readTree(snapshot.configJson());
            int schema = root.required("schemaVersion").intValue();
            JsonNode model = root.required("model");
            ResolvedModelConfig modelConfig = new ResolvedModelConfig(
                    new ModelIdentity(text(model, "provider"), text(model, "model")),
                    new BigDecimal(text(model, "temperature")),
                    model.required("maxTokensPerInvocation").intValue(),
                    new ModelTimeoutPolicy(
                            Duration.parse(text(model, "invocationTimeout")),
                            Duration.parse(text(model, "idleTimeout"))
                    )
            );
            JsonNode runtime = root.required("runtime");
            List<String> tools = new ArrayList<>();
            runtime.required("allowedTools").forEach(node -> tools.add(node.textValue()));
            ResolvedExecutionConfig config = new ResolvedExecutionConfig(
                    schema,
                    modelConfig,
                    new ResolvedExecutionConfig.RuntimeConfig(
                            runtime.required("maxIterations").intValue(), tools
                    ),
                    budget(root.required("budget"))
            );
            ExecutionConfigSnapshot verified = factory.create(config);
            if (!verified.equals(snapshot)) {
                throw new ExecutionConfigSnapshotException(
                        "Runtime config snapshot hash, identity, or canonical JSON is invalid"
                );
            }
            return config;
        } catch (ExecutionConfigSnapshotException exception) {
            throw exception;
        } catch (RuntimeException | java.io.IOException exception) {
            throw new ExecutionConfigSnapshotException(
                    "Could not restore runtime config snapshot", exception
            );
        }
    }

    private ExecutionBudget budget(JsonNode node) {
        ExecutionBudget.Builder builder = ExecutionBudget.builder();
        optionalLong(node, "maxModelCalls", builder::maxModelCalls);
        optionalLong(node, "maxToolCalls", builder::maxToolCalls);
        optionalLong(node, "maxInputTokens", builder::maxInputTokens);
        optionalLong(node, "maxOutputTokens", builder::maxOutputTokens);
        optionalLong(node, "maxTotalTokens", builder::maxTotalTokens);
        if (!node.required("maxCost").isNull()) {
            builder.maxCost(new BigDecimal(node.required("maxCost").textValue()));
        }
        JsonNode pricing = node.required("pricing");
        if (!pricing.isNull()) {
            builder.pricing(new ModelPricing(
                    new ModelIdentity(text(pricing, "provider"), text(pricing, "model")),
                    new BigDecimal(text(pricing, "inputCostPerMillionTokens")),
                    new BigDecimal(text(pricing, "outputCostPerMillionTokens"))
            ));
        }
        return builder.build();
    }

    private void optionalLong(JsonNode node, String field, java.util.function.LongConsumer setter) {
        JsonNode value = node.required(field);
        if (!value.isNull()) {
            setter.accept(value.longValue());
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isTextual()) {
            throw new ExecutionConfigSnapshotException(field + " must be textual");
        }
        return value.textValue();
    }
}
