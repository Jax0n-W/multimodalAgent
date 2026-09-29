package com.multimodalAgent.agent.recovery;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ToolReconcilerRegistry {

    private final Map<String, ToolReconciler> reconcilers;

    public ToolReconcilerRegistry(Collection<? extends ToolReconciler> reconcilers) {
        Objects.requireNonNull(reconcilers, "reconcilers must not be null");
        Map<String, ToolReconciler> indexed = new LinkedHashMap<>();
        for (ToolReconciler reconciler : reconcilers) {
            ToolReconciler item = Objects.requireNonNull(reconciler, "reconciler must not be null");
            String strategyId = requireText(item.strategyId(), "strategyId");
            if (indexed.putIfAbsent(strategyId, item) != null) {
                throw new IllegalArgumentException(
                        "Duplicate ToolReconciler strategy identity: " + strategyId
                );
            }
        }
        this.reconcilers = Map.copyOf(indexed);
    }

    public ToolReconciler require(String strategyId) {
        requireText(strategyId, "strategyId");
        ToolReconciler reconciler = reconcilers.get(strategyId);
        if (reconciler == null) {
            throw new ToolReconcilerUnavailableException(strategyId);
        }
        return reconciler;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
