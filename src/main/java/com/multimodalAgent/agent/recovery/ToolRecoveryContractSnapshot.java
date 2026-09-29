package com.multimodalAgent.agent.recovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

public record ToolRecoveryContractSnapshot(
        String toolName,
        String contractVersion,
        ToolReplaySemantics replaySemantics,
        ToolReconciliationSupport reconciliationSupport,
        Optional<String> reconciliationStrategyId,
        String contractId,
        int schemaVersion
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final String ID_PREFIX = "tool-recovery-v1-";

    public ToolRecoveryContractSnapshot {
        requireText(toolName, "toolName");
        requireText(contractVersion, "contractVersion");
        Objects.requireNonNull(replaySemantics, "replaySemantics must not be null");
        Objects.requireNonNull(
                reconciliationSupport,
                "reconciliationSupport must not be null"
        );
        Objects.requireNonNull(
                reconciliationStrategyId,
                "reconciliationStrategyId must not be null"
        );
        reconciliationStrategyId.ifPresent(value ->
                requireText(value, "reconciliationStrategyId")
        );
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported tool recovery contract schema version: " + schemaVersion
            );
        }
        new ToolRecoveryContract(
                contractVersion,
                replaySemantics,
                reconciliationSupport,
                reconciliationStrategyId
        );
        requireText(contractId, "contractId");
        String expectedId = identity(
                toolName,
                contractVersion,
                replaySemantics,
                reconciliationSupport,
                reconciliationStrategyId,
                schemaVersion
        );
        if (!expectedId.equals(contractId)) {
            throw new IllegalArgumentException("Tool recovery contract identity mismatch");
        }
    }

    public static ToolRecoveryContractSnapshot create(
            String toolName,
            ToolRecoveryContract contract
    ) {
        Objects.requireNonNull(contract, "contract must not be null");
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        return new ToolRecoveryContractSnapshot(
                toolName,
                contract.contractVersion(),
                contract.replaySemantics(),
                contract.reconciliationSupport(),
                contract.reconciliationStrategyId(),
                identity(
                        toolName,
                        contract.contractVersion(),
                        contract.replaySemantics(),
                        contract.reconciliationSupport(),
                        contract.reconciliationStrategyId(),
                        schemaVersion
                ),
                schemaVersion
        );
    }

    public ToolRecoveryClass recoveryClass() {
        return switch (replaySemantics) {
            case REPLAY_SAFE -> ToolRecoveryClass.REPLAY_SAFE;
            case IDEMPOTENT -> ToolRecoveryClass.IDEMPOTENT;
            case NON_REPLAYABLE -> reconciliationSupport
                    == ToolReconciliationSupport.SUPPORTED
                    ? ToolRecoveryClass.RECONCILABLE
                    : ToolRecoveryClass.NON_REPLAYABLE;
        };
    }

    private static String identity(
            String toolName,
            String contractVersion,
            ToolReplaySemantics replaySemantics,
            ToolReconciliationSupport reconciliationSupport,
            Optional<String> reconciliationStrategyId,
            int schemaVersion
    ) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "schemaVersion", Integer.toString(schemaVersion));
        append(canonical, "toolName", toolName);
        append(canonical, "contractVersion", contractVersion);
        append(canonical, "replaySemantics", replaySemantics.name());
        append(canonical, "reconciliationSupport", reconciliationSupport.name());
        append(
                canonical,
                "reconciliationStrategyId",
                reconciliationStrategyId.orElse("<absent>")
        );
        return ID_PREFIX + sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String name, String value) {
        target.append(name)
                .append(':')
                .append(value.length())
                .append(':')
                .append(value)
                .append('\n');
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
