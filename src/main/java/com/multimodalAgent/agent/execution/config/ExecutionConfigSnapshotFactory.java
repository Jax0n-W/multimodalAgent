package com.multimodalAgent.agent.execution.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.budget.ModelPricing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Produces deterministic, secret-free canonical JSON and its SHA-256 identity. */
public final class ExecutionConfigSnapshotFactory {

    private final ObjectMapper objectMapper;

    public ExecutionConfigSnapshotFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null")
                .copy()
                .disable(SerializationFeature.INDENT_OUTPUT);
    }

    public ExecutionConfigSnapshot create(ResolvedExecutionConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        String canonicalJson = canonicalJson(config);
        String hash = sha256(canonicalJson);
        return new ExecutionConfigSnapshot(
                "exec-config-v" + config.schemaVersion() + "-" + hash,
                config.schemaVersion(),
                hash,
                canonicalJson
        );
    }

    private String canonicalJson(ResolvedExecutionConfig config) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", config.schemaVersion());

        ObjectNode model = root.putObject("model");
        model.put("provider", config.model().identity().provider());
        model.put("model", config.model().identity().model());
        model.put("temperature", decimal(config.model().temperature()));
        model.put("maxTokensPerInvocation", config.model().maxTokensPerInvocation());
        model.put("invocationTimeout", config.model().timeoutPolicy().invocationTimeout().toString());
        model.put("idleTimeout", config.model().timeoutPolicy().idleTimeout().toString());

        ObjectNode runtime = root.putObject("runtime");
        runtime.put("maxIterations", config.runtime().maxIterations());
        var tools = runtime.putArray("allowedTools");
        config.runtime().allowedTools().forEach(tools::add);

        appendBudget(root.putObject("budget"), config.budget());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new ExecutionConfigSnapshotException(
                    "Could not canonicalize resolved execution configuration",
                    exception
            );
        }
    }

    private void appendBudget(ObjectNode target, ExecutionBudget budget) {
        putOptionalLong(target, "maxModelCalls", budget.maxModelCalls());
        putOptionalLong(target, "maxToolCalls", budget.maxToolCalls());
        putOptionalLong(target, "maxInputTokens", budget.maxInputTokens());
        putOptionalLong(target, "maxOutputTokens", budget.maxOutputTokens());
        putOptionalLong(target, "maxTotalTokens", budget.maxTotalTokens());
        putOptionalDecimal(target, "maxCost", budget.maxCost());
        if (budget.pricing().isEmpty()) {
            target.putNull("pricing");
            return;
        }
        ModelPricing pricing = budget.pricing().orElseThrow();
        ObjectNode pricingNode = target.putObject("pricing");
        pricingNode.put("provider", pricing.identity().provider());
        pricingNode.put("model", pricing.identity().model());
        pricingNode.put("inputCostPerMillionTokens", decimal(
                pricing.inputCostPerMillionTokens()
        ));
        pricingNode.put("outputCostPerMillionTokens", decimal(
                pricing.outputCostPerMillionTokens()
        ));
    }

    private void putOptionalLong(ObjectNode target, String name, OptionalLong value) {
        if (value.isPresent()) {
            target.put(name, value.getAsLong());
        } else {
            target.putNull(name);
        }
    }

    private void putOptionalDecimal(
            ObjectNode target,
            String name,
            Optional<BigDecimal> value
    ) {
        if (value.isPresent()) {
            target.put(name, decimal(value.orElseThrow()));
        } else {
            target.putNull(name);
        }
    }

    private String decimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return (normalized.signum() == 0 ? BigDecimal.ZERO : normalized).toPlainString();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
