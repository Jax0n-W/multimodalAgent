package com.multimodalAgent.agent.recovery.persistence;

import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.entity.AgentRuntimeConfigSnapshotEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunPhase;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRecoveryCheckpointRepository;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.persistence.repository.AgentRuntimeConfigSnapshotRepository;
import com.multimodalAgent.agent.recovery.BudgetCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointBoundary;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointConflictException;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointCorruptionException;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointSequenceException;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p10-recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaRecoveryCheckpointStore.class)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class RecoveryCheckpointPersistenceTest {

    private static final String RUN_ID = "recovery-run";
    private static final String SNAPSHOT_ID = "cfg-recovery";

    @Autowired
    private AgentRecoveryCheckpointRepository checkpointRepository;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentRuntimeConfigSnapshotRepository snapshotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JpaRecoveryCheckpointStore store;

    @BeforeEach
    void setUp() {
        checkpointRepository.deleteAll();
        runRepository.deleteAll();
        snapshotRepository.deleteAll();
        snapshotRepository.saveAndFlush(new AgentRuntimeConfigSnapshotEntity(
                SNAPSHOT_ID, 1, "a".repeat(64), "{\"model\":\"test\"}"
        ));
        AgentRunEntity run = new AgentRunEntity(
                RUN_ID, "request-recovery", 42L, "session-recovery",
                AgentRunStatus.RUNNING, AgentRunPhase.MODEL_RUNNING
        );
        run.setRuntimeConfigSnapshotId(SNAPSHOT_ID);
        runRepository.saveAndFlush(run);
    }

    @Test
    void roundTripsExactContinuationStateAndOriginalSnapshot() {
        RecoveryCheckpoint expected = checkpoint("cp-exact", 1, 1);

        store.persist(expected);
        RecoveryCheckpoint restored = store.findByCheckpointId("cp-exact").orElseThrow();

        assertEquals(expected, restored);
        assertEquals(expected.messages(), restored.messages());
        assertEquals(expected.messages().get(1).toolCalls(), restored.messages().get(1).toolCalls());
        assertEquals("{\"answer\":42}", restored.messages().get(2).content());
        assertEquals(List.of("knowledge_search"), restored.toolsUsed());
        assertEquals(Set.of("call-1"), restored.seenToolCallIds());
        assertEquals(Set.of("call-1", "call-2"), restored.approvedToolCallIds());
        assertEquals(new BigDecimal("0.000123"), restored.budgetUsage().cost().orElseThrow());
        assertEquals(SNAPSHOT_ID, restored.runtimeConfigSnapshotId());
    }

    @Test
    void identicalRetryIsIdempotentButConflictingIdentityFailsHard() {
        RecoveryCheckpoint original = checkpoint("cp-idempotent", 1, 1);
        store.persist(original);
        store.persist(original);

        assertEquals(1, checkpointRepository.count());

        RecoveryCheckpoint conflicting = new RecoveryCheckpoint(
                original.checkpointId(), original.runId(), original.sequence(), 2,
                original.boundary(), original.messages(), original.toolsUsed(),
                original.seenToolCallIds(), original.budgetUsage(),
                original.approvedToolCallIds(), original.runtimeConfigSnapshotId(),
                original.createdAt(), original.schemaVersion()
        );
        assertThrows(RecoveryCheckpointConflictException.class, () -> store.persist(conflicting));
        assertEquals(original, store.findByCheckpointId(original.checkpointId()).orElseThrow());
    }

    @Test
    void listsInSequenceOrderAndFindsLatest() {
        RecoveryCheckpoint first = checkpoint("cp-first", 1, 1);
        RecoveryCheckpoint second = checkpoint("cp-second", 2, 2);
        store.persist(first);
        store.persist(second);

        assertEquals(List.of(first, second), store.listByRunId(RUN_ID));
        assertEquals(second, store.findLatestByRunId(RUN_ID).orElseThrow());
    }

    @Test
    void rejectsBackwardAppendAndPreservesDurableHistory() {
        RecoveryCheckpoint first = checkpoint("cp-sequence-1", 1, 1);
        RecoveryCheckpoint third = checkpoint("cp-sequence-3", 3, 3);
        store.persist(first);
        store.persist(third);

        assertThrows(
                RecoveryCheckpointSequenceException.class,
                () -> store.persist(checkpoint("cp-sequence-2", 2, 2))
        );

        assertEquals(List.of(first, third), store.listByRunId(RUN_ID));
    }

    @Test
    void rejectsEqualSequenceForNewCheckpointIdentity() {
        RecoveryCheckpoint original = checkpoint("cp-equal-a", 2, 2);
        store.persist(original);

        assertThrows(
                RecoveryCheckpointSequenceException.class,
                () -> store.persist(checkpoint("cp-equal-b", 2, 2))
        );

        assertEquals(List.of(original), store.listByRunId(RUN_ID));
    }

    @Test
    void exactRetryAtLatestSequenceRemainsIdempotent() {
        RecoveryCheckpoint latest = checkpoint("cp-retry-latest", 3, 3);
        store.persist(latest);
        store.persist(latest);

        assertEquals(1, checkpointRepository.count());
        assertEquals(latest, store.findLatestByRunId(RUN_ID).orElseThrow());
    }

    @Test
    void allowsForwardSequenceGap() {
        RecoveryCheckpoint first = checkpoint("cp-gap-1", 1, 1);
        RecoveryCheckpoint third = checkpoint("cp-gap-3", 3, 3);

        store.persist(first);
        store.persist(third);

        assertEquals(List.of(first, third), store.listByRunId(RUN_ID));
    }

    @Test
    void serializesConcurrentAppendValidationOnTheRunRow() throws Exception {
        RecoveryCheckpoint first = checkpoint("cp-concurrent-1", 1, 1);
        RecoveryCheckpoint higher = checkpoint("cp-concurrent-3", 3, 3);
        RecoveryCheckpoint lower = checkpoint("cp-concurrent-2", 2, 2);
        store.persist(first);

        CyclicBarrier concurrentStart = new CyclicBarrier(2);
        ConcurrentLinkedQueue<Long> rejectedSequences = new ConcurrentLinkedQueue<>();
        ExecutorService writers = Executors.newFixedThreadPool(2);
        try {
            Future<?> higherResult = writers.submit(() -> persistConcurrently(
                    higher, concurrentStart, rejectedSequences
            ));
            Future<?> lowerResult = writers.submit(() -> persistConcurrently(
                    lower, concurrentStart, rejectedSequences
            ));
            higherResult.get(5, TimeUnit.SECONDS);
            lowerResult.get(5, TimeUnit.SECONDS);
        } finally {
            writers.shutdownNow();
        }

        List<RecoveryCheckpoint> durable = store.listByRunId(RUN_ID);
        assertTrue(
                durable.equals(List.of(first, lower, higher))
                        || durable.equals(List.of(first, higher))
        );
        if (durable.equals(List.of(first, higher))) {
            assertEquals(List.of(2L), List.copyOf(rejectedSequences));
        } else {
            assertTrue(rejectedSequences.isEmpty());
        }
    }

    @Test
    void recoveryAppendLookupRequiresPessimisticWriteLock() throws Exception {
        Lock lock = AgentRunRepository.class
                .getMethod("findByRunIdForRecoveryAppend", String.class)
                .getAnnotation(Lock.class);

        assertNotNull(lock);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }

    @Test
    void unsupportedSchemaAndCorruptJsonFailClosed() {
        insertRaw("cp-unsupported", 1, 99, validStateJson(99));
        insertRaw("cp-corrupt", 2, 1, "{not-json");

        assertThrows(
                RecoveryCheckpointCorruptionException.class,
                () -> store.findByCheckpointId("cp-unsupported")
        );
        assertThrows(
                RecoveryCheckpointCorruptionException.class,
                () -> store.findByCheckpointId("cp-corrupt")
        );
    }

    @Test
    void mismatchedSnapshotReferenceFailsClosedBeforePersistence() {
        snapshotRepository.saveAndFlush(new AgentRuntimeConfigSnapshotEntity(
                "cfg-other", 1, "b".repeat(64), "{\"model\":\"other\"}"
        ));
        RecoveryCheckpoint original = checkpoint("cp-wrong-snapshot", 1, 1);
        RecoveryCheckpoint wrong = new RecoveryCheckpoint(
                original.checkpointId(), original.runId(), original.sequence(), original.iteration(),
                original.boundary(), original.messages(), original.toolsUsed(),
                original.seenToolCallIds(), original.budgetUsage(),
                original.approvedToolCallIds(), "cfg-other", original.createdAt(),
                original.schemaVersion()
        );

        assertThrows(RecoveryCheckpointCorruptionException.class, () -> store.persist(wrong));
        assertTrue(store.listByRunId(RUN_ID).isEmpty());
    }

    @Test
    void historicalRunWithoutCheckpointRemainsValid() {
        assertTrue(runRepository.findByRunId(RUN_ID).isPresent());
        assertTrue(store.findLatestByRunId(RUN_ID).isEmpty());
        assertTrue(store.listByRunId(RUN_ID).isEmpty());
    }

    private RecoveryCheckpoint checkpoint(String checkpointId, long sequence, int iteration) {
        ToolCall call = new ToolCall(
                "call-1",
                "knowledge_search",
                Map.of("query", "redis", "filters", Map.of("year", 2026))
        );
        return new RecoveryCheckpoint(
                checkpointId,
                RUN_ID,
                sequence,
                iteration,
                RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME,
                List.of(
                        AgentMessage.user("Find it"),
                        AgentMessage.assistantToolCalls(List.of(call)),
                        AgentMessage.toolResult("call-1", "knowledge_search", "{\"answer\":42}")
                ),
                List.of("knowledge_search"),
                new LinkedHashSet<>(List.of("call-1")),
                new BudgetCheckpoint(
                        1, 1, 80, 20, 100,
                        Optional.of(new BigDecimal("0.000123")), false
                ),
                new LinkedHashSet<>(List.of("call-2", "call-1")),
                SNAPSHOT_ID,
                Instant.parse("2026-09-28T01:02:03.123456Z"),
                RecoveryCheckpoint.CURRENT_SCHEMA_VERSION
        );
    }

    private void persistConcurrently(
            RecoveryCheckpoint checkpoint,
            CyclicBarrier concurrentStart,
            ConcurrentLinkedQueue<Long> rejectedSequences
    ) {
        try {
            concurrentStart.await(5, TimeUnit.SECONDS);
            store.persist(checkpoint);
        } catch (RecoveryCheckpointSequenceException exception) {
            rejectedSequences.add(checkpoint.sequence());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void insertRaw(String checkpointId, long sequence, int schemaVersion, String stateJson) {
        jdbcTemplate.update(
                """
                INSERT INTO agent_recovery_checkpoints (
                    checkpoint_id, run_id, checkpoint_sequence, schema_version, iteration,
                    boundary, state_json, runtime_config_snapshot_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                checkpointId, RUN_ID, sequence, schemaVersion, 1,
                RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME.name(),
                stateJson, SNAPSHOT_ID, Instant.parse("2026-09-28T01:02:03Z")
        );
    }

    private String validStateJson(int schemaVersion) {
        return """
                {"schemaVersion":%d,"messages":[{"role":"USER","content":"hello",\
                "toolCalls":[],"toolCallId":null,"toolName":null}],"toolsUsed":[],\
                "seenToolCallIds":[],"budgetUsage":{"modelCalls":0,"toolCalls":0,\
                "inputTokens":0,"outputTokens":0,"totalTokens":0,"cost":null,\
                "unknownUsageObserved":false},"approvedToolCallIds":[]}
                """.formatted(schemaVersion).replace("\\\n", "");
    }
}
