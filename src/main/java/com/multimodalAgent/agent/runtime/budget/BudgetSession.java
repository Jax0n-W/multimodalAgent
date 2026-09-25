package com.multimodalAgent.agent.runtime.budget;

import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Mutable, run-scoped budget accounting. A session must never be shared between runs. */
public final class BudgetSession {

    private final ExecutionBudget budget;
    private final Optional<ModelIdentity> modelIdentity;
    private final ModelCostCalculator costCalculator;
    private long modelCalls;
    private long toolCalls;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private BigDecimal cost = BigDecimal.ZERO;
    private boolean unknownUsageObserved;

    public BudgetSession(ExecutionBudget budget, Optional<ModelIdentity> modelIdentity) {
        this(budget, modelIdentity, new ModelCostCalculator());
    }

    BudgetSession(
            ExecutionBudget budget,
            Optional<ModelIdentity> modelIdentity,
            ModelCostCalculator costCalculator
    ) {
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.modelIdentity = Objects.requireNonNull(
                modelIdentity, "modelIdentity must not be null"
        );
        this.costCalculator = Objects.requireNonNull(
                costCalculator, "costCalculator must not be null"
        );
    }

    public Optional<BudgetBlock> admitModelCall() {
        Optional<BudgetBlock> block = exhausted(
                BudgetDimension.MODEL_CALLS, budget.maxModelCalls(), modelCalls
        );
        if (block.isPresent()) {
            return block;
        }
        block = futureWorkBlock();
        if (block.isPresent()) {
            return block;
        }
        modelCalls++;
        return Optional.empty();
    }

    public Optional<BudgetBlock> admitToolCall() {
        Optional<BudgetBlock> block = exhausted(
                BudgetDimension.TOOL_CALLS, budget.maxToolCalls(), toolCalls
        );
        if (block.isPresent()) {
            return block;
        }
        block = futureWorkBlock();
        if (block.isPresent()) {
            return block;
        }
        toolCalls++;
        return Optional.empty();
    }

    /** Records confirmed provider usage after MODEL_COMPLETED; it never rewrites that turn. */
    public void account(TokenUsage usage) {
        Objects.requireNonNull(usage, "usage must not be null");
        if (!usage.isComplete()) {
            unknownUsageObserved = true;
            return;
        }
        inputTokens = Math.addExact(inputTokens, usage.inputTokens());
        outputTokens = Math.addExact(outputTokens, usage.outputTokens());
        totalTokens = Math.addExact(totalTokens, usage.totalTokens());
        if (budget.pricing().isPresent() && modelIdentity.isPresent()) {
            costCalculator.calculate(
                    modelIdentity.get(), usage, budget.pricing().get()
            ).ifPresent(value -> cost = cost.add(value));
        }
    }

    public long modelCalls() {
        return modelCalls;
    }

    public long toolCalls() {
        return toolCalls;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public long totalTokens() {
        return totalTokens;
    }

    public Optional<BigDecimal> cost() {
        if (budget.pricing().isEmpty()
                || modelIdentity.isEmpty()
                || !budget.pricing().get().identity().equals(modelIdentity.get())
                || unknownUsageObserved) {
            return Optional.empty();
        }
        return Optional.of(cost);
    }

    public boolean unknownUsageObserved() {
        return unknownUsageObserved;
    }

    /** Returns an immutable view; callers never receive the mutable session itself. */
    public BudgetUsage usage() {
        return new BudgetUsage(
                modelCalls,
                toolCalls,
                inputTokens,
                outputTokens,
                totalTokens,
                cost(),
                unknownUsageObserved
        );
    }

    private Optional<BudgetBlock> futureWorkBlock() {
        if (unknownUsageObserved && budget.hasTokenLimit()) {
            if (budget.maxInputTokens().isPresent()) {
                return Optional.of(BudgetBlock.unverifiable(
                        BudgetDimension.INPUT_TOKENS,
                        BigDecimal.valueOf(budget.maxInputTokens().getAsLong())
                ));
            }
            if (budget.maxOutputTokens().isPresent()) {
                return Optional.of(BudgetBlock.unverifiable(
                        BudgetDimension.OUTPUT_TOKENS,
                        BigDecimal.valueOf(budget.maxOutputTokens().getAsLong())
                ));
            }
            return Optional.of(BudgetBlock.unverifiable(
                    BudgetDimension.TOTAL_TOKENS,
                    BigDecimal.valueOf(budget.maxTotalTokens().orElseThrow())
            ));
        }

        Optional<BudgetBlock> block = exhausted(
                BudgetDimension.INPUT_TOKENS, budget.maxInputTokens(), inputTokens
        );
        if (block.isEmpty()) {
            block = exhausted(
                    BudgetDimension.OUTPUT_TOKENS, budget.maxOutputTokens(), outputTokens
            );
        }
        if (block.isEmpty()) {
            block = exhausted(
                    BudgetDimension.TOTAL_TOKENS, budget.maxTotalTokens(), totalTokens
            );
        }
        if (block.isPresent()) {
            return block;
        }

        if (budget.maxCost().isEmpty()) {
            return Optional.empty();
        }
        BigDecimal costLimit = budget.maxCost().get();
        if (budget.pricing().isEmpty()
                || modelIdentity.isEmpty()
                || !budget.pricing().get().identity().equals(modelIdentity.get())
                || unknownUsageObserved) {
            return Optional.of(BudgetBlock.unverifiable(BudgetDimension.COST, costLimit));
        }
        if (cost.compareTo(costLimit) >= 0) {
            return Optional.of(BudgetBlock.exhausted(BudgetDimension.COST, costLimit, cost));
        }
        return Optional.empty();
    }

    private Optional<BudgetBlock> exhausted(
            BudgetDimension dimension,
            OptionalLong limit,
            long observed
    ) {
        if (limit.isPresent() && observed >= limit.getAsLong()) {
            return Optional.of(BudgetBlock.exhausted(
                    dimension, limit.getAsLong(), observed
            ));
        }
        return Optional.empty();
    }
}
