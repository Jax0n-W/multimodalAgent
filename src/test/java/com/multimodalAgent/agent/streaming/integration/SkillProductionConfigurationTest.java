package com.multimodalAgent.agent.streaming.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.context.AgentContextSnapshot;
import com.multimodalAgent.agent.context.ContextAssemblyInput;
import com.multimodalAgent.agent.context.memory.ConversationMemoryReader;
import com.multimodalAgent.agent.context.memory.ConversationTurn;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillProductionConfigurationTest {

    private final StreamingAgentExecutionConfiguration configuration =
            new StreamingAgentExecutionConfiguration();

    @Test
    void skillsAreDisabledByDefaultAndExistingRequestOnlyBehaviorIsUnchanged() {
        AtomicInteger memoryReads = new AtomicInteger();
        ConversationMemoryReader reader = query -> {
            memoryReads.incrementAndGet();
            return List.of();
        };

        AgentContextSnapshot snapshot = configuration.agentContextAssembler(
                new multimodalAgentProperties(), new ObjectMapper(), reader
        ).assemble(input("我最近睡不着"));

        assertEquals(0, memoryReads.get());
        assertEquals(List.of("request-messages"), snapshot.orderedContributions().stream()
                .map(value -> value.sourceId()).toList());
        assertEquals(List.of(AgentMessage.user("我最近睡不着")), snapshot.messages());
    }

    @Test
    void enabledBuiltInSkillIsResolvedThroughProductionContextAssembly() {
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getRuntime().getSkills().setEnabled(true);

        AgentContextSnapshot snapshot = configuration.agentContextAssembler(
                properties, new ObjectMapper(), query -> List.of()
        ).assemble(input("我最近睡不着"));

        assertEquals(List.of("agent-skills", "request-messages"),
                snapshot.orderedContributions().stream().map(value -> value.sourceId()).toList());
        assertEquals(2, snapshot.messages().size());
        assertEquals("Trusted Skill: sleep-guidance@1", snapshot.messages().get(0)
                .content().lines().findFirst().orElseThrow());
    }

    @Test
    void enabledSkillMemoryAndRequestUseStableProductionOrder() {
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getRuntime().getSkills().setEnabled(true);
        properties.getRuntime().getMemory().setEnabled(true);
        ConversationMemoryReader reader = query -> List.of(new ConversationTurn(
                "old-run", "session", 7L, "old-user", "old-assistant",
                Instant.parse("2026-10-10T00:00:00Z")
        ));

        AgentContextSnapshot snapshot = configuration.agentContextAssembler(
                properties, new ObjectMapper(), reader
        ).assemble(input("sleep trouble"));

        assertEquals(List.of("agent-skills", "conversation-memory", "request-messages"),
                snapshot.orderedContributions().stream().map(value -> value.sourceId()).toList());
    }

    private ContextAssemblyInput input(String message) {
        return new ContextAssemblyInput(
                "current-run", "session", 7L,
                List.of(AgentMessage.user(message)), Set.of("knowledge_search")
        );
    }
}
