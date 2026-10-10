package com.multimodalAgent.agent.context.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.context.AgentContextAssembler;
import com.multimodalAgent.agent.context.AgentContextSnapshot;
import com.multimodalAgent.agent.context.AgentContextSnapshotFactory;
import com.multimodalAgent.agent.context.AgentContextSnapshotStore;
import com.multimodalAgent.agent.context.ContextAssemblyException;
import com.multimodalAgent.agent.context.ContextAssemblyInput;
import com.multimodalAgent.agent.context.ContextAssemblingAgentExecutionCoordinator;
import com.multimodalAgent.agent.context.RequestMessageContextSource;
import com.multimodalAgent.agent.context.memory.ConversationMemoryPolicy;
import com.multimodalAgent.agent.context.memory.ConversationMemorySource;
import com.multimodalAgent.agent.context.memory.ConversationTurn;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.recovery.BudgetCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointBoundary;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyContext;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecisionType;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillContextSourceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T04:00:00Z");

    @Test
    void selectedSkillProducesTrustedVersionedSystemMessageAndProvenance() {
        SkillDefinition skill = skill("sleep-guidance", "1", "TRUSTED", Set.of());

        AgentContextSnapshot snapshot = assembler(List.of(
                new RequestMessageContextSource(),
                new SkillContextSource(input -> Optional.of(skill))
        )).assemble(input("sleep", Set.of()));

        assertEquals(List.of("agent-skills", "request-messages"),
                snapshot.orderedContributions().stream().map(value -> value.sourceId()).toList());
        assertEquals(AgentMessage.system(
                "Trusted Skill: sleep-guidance@1\nInstructions:\nTRUSTED"
        ), snapshot.messages().get(0));
        assertEquals(AgentMessage.user("sleep"), snapshot.messages().get(1));
    }

    @Test
    void noMatchReturnsEmptyContributionWithoutChangingRequestMessages() {
        AgentContextSnapshot snapshot = assembler(List.of(
                new SkillContextSource(input -> Optional.empty()),
                new RequestMessageContextSource()
        )).assemble(input("unmatched", Set.of()));

        assertEquals(List.of(AgentMessage.user("unmatched")), snapshot.messages());
        assertEquals(0, snapshot.orderedContributions().get(0).messageCount());
    }

    @Test
    void skillMemoryAndRequestHaveStableLexicalOrderAtTheSameExplicitOrder() {
        SkillDefinition skill = skill("sleep-guidance", "1", "TRUSTED", Set.of());
        ConversationMemorySource memory = new ConversationMemorySource(
                query -> List.of(new ConversationTurn(
                        "old-run", "session", 1L, "old-user", "old-assistant",
                        NOW.minusSeconds(1)
                )),
                new ConversationMemoryPolicy(2, 1000)
        );

        AgentContextSnapshot snapshot = assembler(List.of(
                new RequestMessageContextSource(),
                memory,
                new SkillContextSource(input -> Optional.of(skill))
        )).assemble(input("sleep", Set.of()));

        assertEquals(List.of("agent-skills", "conversation-memory", "request-messages"),
                snapshot.orderedContributions().stream().map(value -> value.sourceId()).toList());
        assertEquals(List.of(
                AgentMessage.system("Trusted Skill: sleep-guidance@1\nInstructions:\nTRUSTED"),
                AgentMessage.user("old-user"),
                AgentMessage.assistant("old-assistant"),
                AgentMessage.user("sleep")
        ), snapshot.messages());
    }

    @Test
    void versionOrInstructionChangeProducesDifferentSemanticSnapshotIdentity() {
        AgentContextSnapshot v1 = snapshotFor(skill(
                "sleep-guidance", "1", "SAME", Set.of()
        ));
        AgentContextSnapshot v2 = snapshotFor(skill(
                "sleep-guidance", "2", "SAME", Set.of()
        ));
        AgentContextSnapshot changed = snapshotFor(skill(
                "sleep-guidance", "1", "CHANGED", Set.of()
        ));

        assertNotEquals(v1.snapshotId(), v2.snapshotId());
        assertNotEquals(v1.snapshotId(), changed.snapshotId());
        assertNotEquals(v1.contextHash(), v2.contextHash());
        assertNotEquals(v1.contextHash(), changed.contextHash());
    }

    @Test
    void missingRequiredToolAuthorizationFailsClosedBeforeAnyDurableOrRuntimeWork() {
        SkillDefinition skill = skill(
                "tool-skill", "1", "trusted", Set.of("knowledge_search")
        );
        AtomicInteger snapshotWrites = new AtomicInteger();
        AtomicInteger downstreamCalls = new AtomicInteger();
        ContextAssemblingAgentExecutionCoordinator coordinator =
                new ContextAssemblingAgentExecutionCoordinator(
                        assembler(List.of(
                                new SkillContextSource(input -> Optional.of(skill)),
                                new RequestMessageContextSource()
                        )),
                        new AgentContextSnapshotStore() {
                            @Override
                            public AgentContextSnapshot persistIfAbsent(
                                    AgentContextSnapshot snapshot
                            ) {
                                snapshotWrites.incrementAndGet();
                                return snapshot;
                            }

                            @Override
                            public Optional<AgentContextSnapshot> findById(String snapshotId) {
                                return Optional.empty();
                            }
                        },
                        request -> {
                            downstreamCalls.incrementAndGet();
                            throw new AssertionError("must not execute");
                        }
                );
        AgentExecutionRequest request = request(Set.of());

        assertThrows(ContextAssemblyException.class, () -> coordinator.execute(request));
        assertEquals(0, snapshotWrites.get());
        assertEquals(0, downstreamCalls.get());
    }

    @Test
    void resolverFailureAlsoFailsBeforeSnapshotAndAdmission() {
        AtomicInteger snapshotWrites = new AtomicInteger();
        AtomicInteger downstreamCalls = new AtomicInteger();
        ContextAssemblingAgentExecutionCoordinator coordinator =
                new ContextAssemblingAgentExecutionCoordinator(
                        assembler(List.of(
                                new SkillContextSource(input -> {
                                    throw new IllegalStateException("resolution failed");
                                }),
                                new RequestMessageContextSource()
                        )),
                        new AgentContextSnapshotStore() {
                            @Override
                            public AgentContextSnapshot persistIfAbsent(
                                    AgentContextSnapshot snapshot
                            ) {
                                snapshotWrites.incrementAndGet();
                                return snapshot;
                            }

                            @Override
                            public Optional<AgentContextSnapshot> findById(String snapshotId) {
                                return Optional.empty();
                            }
                        },
                        request -> {
                            downstreamCalls.incrementAndGet();
                            throw new AssertionError("must not execute");
                        }
                );

        assertThrows(ContextAssemblyException.class,
                () -> coordinator.execute(request(Set.of())));
        assertEquals(0, snapshotWrites.get());
        assertEquals(0, downstreamCalls.get());
    }

    @Test
    void skillInstructionsCannotAuthorizeAnOtherwiseDeniedToolCall() {
        SkillDefinition mentionsTool = skill(
                "mention-only", "1", "Use danger_tool", Set.of()
        );
        ContextAssemblyInput input = input("sleep", Set.of());
        new SkillContextSource(ignored -> Optional.of(mentionsTool)).load(input);

        DefaultToolPolicyEngine policy = new DefaultToolPolicyEngine();
        ToolDescriptor<Map> descriptor = new ToolDescriptor<>(
                "danger_tool", "test tool", Map.class, ToolRisk.HIGH,
                false, false, false
        );

        assertEquals(ToolPolicyDecisionType.DENY, policy.evaluate(new ToolPolicyRequest(
                new ToolCall("call", "danger_tool", Map.of()),
                descriptor,
                Map.of(),
                new ToolPolicyContext("run", "session", input.allowedTools(), Set.of())
        )).type());
        assertEquals(Set.of(), input.allowedTools());
    }

    @Test
    void newerRegistryDefinitionCannotChangeExistingRecoveryCheckpointMessages() {
        SkillDefinition v1 = skill("sleep-guidance", "1", "ORIGINAL", Set.of());
        AgentContextSnapshot original = snapshotFor(v1);
        RecoveryCheckpoint checkpoint = new RecoveryCheckpoint(
                "checkpoint", "run", 1, 0,
                RecoveryCheckpointBoundary.ITERATION_BOUNDARY,
                original.messages(), List.of(), Set.of(), BudgetCheckpoint.EMPTY,
                Set.of(), "config-v1-" + "0".repeat(64), NOW, 1
        );

        SkillDefinition v2 = skill("sleep-guidance", "2", "UPDATED", Set.of());
        AgentContextSnapshot laterFreshRun = snapshotFor(v2);

        assertEquals(original.messages(), checkpoint.messages());
        assertNotEquals(laterFreshRun.messages(), checkpoint.messages());
        assertEquals("Trusted Skill: sleep-guidance@1", checkpoint.messages().get(0)
                .content().lines().findFirst().orElseThrow());
    }

    private AgentContextSnapshot snapshotFor(SkillDefinition skill) {
        return assembler(List.of(
                new SkillContextSource(input -> Optional.of(skill)),
                new RequestMessageContextSource()
        )).assemble(input("sleep", Set.of()));
    }

    private AgentContextAssembler assembler(List<? extends com.multimodalAgent.agent.context.ContextSource> sources) {
        return new AgentContextAssembler(
                sources,
                new AgentContextSnapshotFactory(new ObjectMapper()),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private ContextAssemblyInput input(String message, Set<String> allowedTools) {
        return new ContextAssemblyInput(
                "run", "session", 1L, List.of(AgentMessage.user(message)), allowedTools
        );
    }

    private AgentExecutionRequest request(Set<String> allowedTools) {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        "run", "session", List.of(AgentMessage.user("sleep")), 3,
                        allowedTools, Set.of(), ExecutionBudget.unlimited()
                ),
                "request", 1L
        );
    }

    private SkillDefinition skill(
            String skillId,
            String version,
            String instructions,
            Set<String> requiredTools
    ) {
        return new SkillDefinition(
                skillId, version, "description", instructions,
                requiredTools, Set.of("sleep"), 10
        );
    }
}
