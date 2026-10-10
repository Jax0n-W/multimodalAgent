package com.multimodalAgent.agent.streaming.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.context.AgentContextAssembler;
import com.multimodalAgent.agent.context.AgentContextSnapshot;
import com.multimodalAgent.agent.context.ContextAssemblyInput;
import com.multimodalAgent.agent.context.memory.ConversationMemoryReader;
import com.multimodalAgent.agent.context.memory.ConversationTurn;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversationMemoryProductionConfigurationTest {

    private final StreamingAgentExecutionConfiguration configuration =
            new StreamingAgentExecutionConfiguration();

    @Test
    void disabledProductionMemoryKeepsRequestOnlyBehavior() {
        AtomicInteger reads = new AtomicInteger();
        ConversationMemoryReader reader = query -> {
            reads.incrementAndGet();
            return List.of();
        };
        AgentContextSnapshot snapshot = configuration.agentContextAssembler(
                new multimodalAgentProperties(), new ObjectMapper(), reader
        ).assemble(input());

        assertEquals(0, reads.get());
        assertEquals(List.of("chat-business-context", "request-messages"), snapshot.orderedContributions().stream()
                .map(value -> value.sourceId()).toList());
        assertEquals(List.of(AgentMessage.user("current")), snapshot.messages());
    }

    @Test
    void enabledProductionMemoryPrecedesRequestAndUsesConfiguredWindow() {
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getRuntime().getMemory().setEnabled(true);
        properties.getRuntime().getMemory().setMaxTurns(1);
        properties.getRuntime().getMemory().setMaxTotalChars(100);
        ConversationMemoryReader reader = query -> List.of(new ConversationTurn(
                "old-run", "session", 7L, "old-user", "old-assistant",
                Instant.parse("2026-10-10T00:00:00Z")
        ));

        AgentContextSnapshot snapshot = configuration.agentContextAssembler(
                properties, new ObjectMapper(), reader
        ).assemble(input());

        assertEquals(List.of("chat-business-context", "conversation-memory", "request-messages"),
                snapshot.orderedContributions().stream().map(value -> value.sourceId()).toList());
        assertEquals(List.of(
                AgentMessage.user("old-user"),
                AgentMessage.assistant("old-assistant"),
                AgentMessage.user("current")
        ), snapshot.messages());
    }

    @Test
    void invalidMemoryConfigurationFailsFast() {
        multimodalAgentProperties properties = new multimodalAgentProperties();

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> properties.getRuntime().getMemory().setMaxTurns(0)
        );
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> properties.getRuntime().getMemory().setMaxTotalChars(0)
        );
    }

    private ContextAssemblyInput input() {
        return new ContextAssemblyInput(
                "current-run", "session", 7L, List.of(AgentMessage.user("current"))
        );
    }
}
