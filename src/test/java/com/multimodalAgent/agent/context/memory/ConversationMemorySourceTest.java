package com.multimodalAgent.agent.context.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.context.AgentContextAssembler;
import com.multimodalAgent.agent.context.AgentContextSnapshot;
import com.multimodalAgent.agent.context.AgentContextSnapshotFactory;
import com.multimodalAgent.agent.context.AgentContextSnapshotStore;
import com.multimodalAgent.agent.context.ContextAssemblyException;
import com.multimodalAgent.agent.context.ContextAssemblyInput;
import com.multimodalAgent.agent.context.ContextAssemblingAgentExecutionCoordinator;
import com.multimodalAgent.agent.context.RequestMessageContextSource;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationMemorySourceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T01:00:00Z");

    @Test
    void assemblesHistoryOldestFirstBeforeTheCurrentRequest() {
        ConversationMemoryReader reader = query -> List.of(
                turn("run-b", "second", "answer-b", NOW.plusSeconds(2)),
                turn("run-a", "first", "answer-a", NOW.plusSeconds(1))
        );

        AgentContextSnapshot snapshot = assembler(reader, 6, 12000).assemble(input());

        assertEquals(List.of(
                AgentMessage.user("first"), AgentMessage.assistant("answer-a"),
                AgentMessage.user("second"), AgentMessage.assistant("answer-b"),
                AgentMessage.user("current")
        ), snapshot.messages());
        assertEquals(
                List.of("conversation-memory", "request-messages"),
                snapshot.orderedContributions().stream().map(value -> value.sourceId()).toList()
        );
    }

    @Test
    void windowKeepsNewestWholeTurnsWithDeterministicTieBreakAndCharacterLimit() {
        ConversationMemoryPolicy policy = new ConversationMemoryPolicy(2, 6);
        List<ConversationTurn> selected = policy.select(List.of(
                turn("run-a", "aa", "a", NOW),
                turn("run-c", "cc", "c", NOW.plusSeconds(1)),
                turn("run-b", "bb", "b", NOW.plusSeconds(1))
        ));

        assertEquals(List.of("run-b", "run-c"), selected.stream()
                .map(ConversationTurn::runId).toList());
        assertEquals(2, selected.size());
        assertEquals(6, selected.stream().mapToInt(ConversationTurn::characterCount).sum());
    }

    @Test
    void oversizedNewestTurnProducesEmptyMemoryInsteadOfHalfATurn() {
        ConversationMemoryPolicy policy = new ConversationMemoryPolicy(3, 3);

        assertEquals(List.of(), policy.select(List.of(
                turn("run-a", "123", "4", NOW)
        )));
    }

    @Test
    void readerIsolationViolationFailsClosed() {
        ConversationMemoryReader reader = query -> List.of(new ConversationTurn(
                "run-a", "another-session", query.userId(), "user", "assistant", NOW
        ));

        assertThrows(ContextAssemblyException.class, () -> assembler(reader, 6, 12000)
                .assemble(input()));
    }

    @Test
    void readerFailurePreventsSnapshotPersistenceAndDurableDelegation() {
        AtomicInteger snapshotWrites = new AtomicInteger();
        AtomicInteger durableCalls = new AtomicInteger();
        ConversationMemoryReader reader = query -> {
            throw new IllegalStateException("database unavailable");
        };
        AgentContextSnapshotStore store = new AgentContextSnapshotStore() {
            @Override
            public AgentContextSnapshot persistIfAbsent(AgentContextSnapshot snapshot) {
                snapshotWrites.incrementAndGet();
                return snapshot;
            }

            @Override
            public Optional<AgentContextSnapshot> findById(String snapshotId) {
                return Optional.empty();
            }
        };
        ContextAssemblingAgentExecutionCoordinator coordinator =
                new ContextAssemblingAgentExecutionCoordinator(
                        assembler(reader, 6, 12000),
                        store,
                        request -> {
                            durableCalls.incrementAndGet();
                            throw new AssertionError("must not execute");
                        }
                );
        AgentExecutionRequest request = new AgentExecutionRequest(
                new AgentRunSpec("current-run", "session", List.of(
                        AgentMessage.user("current")
                ), 3),
                "request", 7L
        );

        assertThrows(ContextAssemblyException.class, () -> coordinator.execute(request));
        assertEquals(0, snapshotWrites.get());
        assertEquals(0, durableCalls.get());
    }

    @Test
    void frozenSnapshotDoesNotChangeWhenLaterHistoryAppears() {
        List<ConversationTurn> turns = new ArrayList<>();
        turns.add(turn("run-a", "first", "answer-a", NOW));
        AgentContextAssembler assembler = assembler(query -> List.copyOf(turns), 6, 12000);
        AgentContextSnapshot first = assembler.assemble(input());
        String originalId = first.snapshotId();
        String originalCanonicalJson = first.canonicalJson();
        List<AgentMessage> originalMessages = first.messages();

        turns.add(turn("run-b", "second", "answer-b", NOW.plusSeconds(1)));
        assembler.assemble(new ContextAssemblyInput(
                "current-run-2", "session", 7L, List.of(AgentMessage.user("next"))
        ));

        assertEquals(originalId, first.snapshotId());
        assertEquals(originalCanonicalJson, first.canonicalJson());
        assertEquals(originalMessages, first.messages());
        assertEquals(3, first.messages().size());
        assertEquals(AgentMessage.user("current"), first.messages().get(2));
        assertEquals(first.contextHash(), first.snapshotId().substring("context-v1-".length()));
    }

    private AgentContextAssembler assembler(
            ConversationMemoryReader reader,
            int maxTurns,
            int maxChars
    ) {
        return new AgentContextAssembler(
                List.of(
                        new RequestMessageContextSource(),
                        new ConversationMemorySource(
                                reader,
                                new ConversationMemoryPolicy(maxTurns, maxChars)
                        )
                ),
                new AgentContextSnapshotFactory(new ObjectMapper()),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private ContextAssemblyInput input() {
        return new ContextAssemblyInput(
                "current-run", "session", 7L, List.of(AgentMessage.user("current"))
        );
    }

    private ConversationTurn turn(
            String runId,
            String user,
            String assistant,
            Instant completedAt
    ) {
        return new ConversationTurn(runId, "session", 7L, user, assistant, completedAt);
    }
}
