package com.multimodalAgent.agent.runtime.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenUsageTest {

    @Test
    void knownUsageRemainsKnownWhenAggregated() {
        TokenUsage aggregate = new TokenUsage(10, 2).plus(new TokenUsage(5, 3));

        assertEquals(new TokenUsage(15, 5), aggregate);
        assertTrue(aggregate.isComplete());
    }

    @Test
    void oneUnknownTurnMakesTheRunAggregateIncomplete() {
        TokenUsage aggregate = new TokenUsage(10, 2).plus(TokenUsage.UNKNOWN);

        assertEquals(10, aggregate.inputTokens());
        assertEquals(2, aggregate.outputTokens());
        assertFalse(aggregate.isComplete());
        assertEquals(TokenUsageStatus.UNKNOWN_OR_INCOMPLETE, aggregate.status());
    }
}
