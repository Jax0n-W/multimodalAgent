package com.multimodalAgent.agent.persistence.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshot;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotException;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotFactory;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotStore;
import com.multimodalAgent.agent.execution.config.ResolvedExecutionConfigResolver;
import com.multimodalAgent.agent.execution.config.ResolvedModelConfig;
import com.multimodalAgent.agent.execution.config.SnapshottingAgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.persistence.repository.AgentRuntimeConfigSnapshotRepository;
import com.multimodalAgent.agent.persistence.repository.AgentStepRepository;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p9-config-snapshot;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaExecutionConfigSnapshotStore.class)
class ExecutionConfigSnapshotPersistenceTest {

    @Autowired
    private AgentRuntimeConfigSnapshotRepository snapshotRepository;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentStepRepository stepRepository;

    @Autowired
    private ToolExecutionRepository toolExecutionRepository;

    @Autowired
    private JpaExecutionConfigSnapshotStore transactionalSnapshotStore;

    private JpaExecutionConfigSnapshotStore snapshotStore;
    private JpaExecutionHistoryStore historyStore;
    private ExecutionConfigSnapshotFactory factory;
    private ResolvedExecutionConfigResolver resolver;

    @BeforeEach
    void setUp() {
        snapshotStore = new JpaExecutionConfigSnapshotStore(snapshotRepository);
        historyStore = new JpaExecutionHistoryStore(
                runRepository, stepRepository, toolExecutionRepository
        );
        factory = new ExecutionConfigSnapshotFactory(new ObjectMapper());
        resolver = new ResolvedExecutionConfigResolver(new ResolvedModelConfig(
                new ModelIdentity("ollama", "mindbridge"),
                new BigDecimal("0.35"),
                512,
                new ModelTimeoutPolicy(Duration.ofMinutes(2), Duration.ofSeconds(30))
        ));
    }

    @Test
    void persistsReadsAndIdempotentlyReusesTheCompleteSnapshot() {
        long countBefore = snapshotRepository.count();
        ExecutionConfigSnapshot requested = factory.create(resolver.resolve(request(
                "reuse", 3, Set.of("knowledge_search"), ExecutionBudget.unlimited()
        )));

        ExecutionConfigSnapshot first = snapshotStore.persistIfAbsent(requested);
        ExecutionConfigSnapshot second = snapshotStore.persistIfAbsent(requested);

        assertEquals(requested, first);
        assertEquals(first, second);
        assertEquals(requested, snapshotStore.findById(requested.snapshotId()).orElseThrow());
        assertEquals(countBefore + 1L, snapshotRepository.count());
    }

    @Test
    void concurrentFirstWritersIdempotentlyReuseOneImmutableSnapshot() throws Exception {
        ExecutionConfigSnapshot requested = factory.create(resolver.resolve(request(
                "concurrent", 3, Set.of("knowledge_search"), ExecutionBudget.unlimited()
        )));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ExecutionConfigSnapshot> first = executor.submit(() -> {
                start.await();
                return transactionalSnapshotStore.persistIfAbsent(requested);
            });
            Future<ExecutionConfigSnapshot> second = executor.submit(() -> {
                start.await();
                return transactionalSnapshotStore.persistIfAbsent(requested);
            });
            start.countDown();

            assertEquals(requested, first.get());
            assertEquals(requested, second.get());
            assertEquals(
                    requested,
                    transactionalSnapshotStore.findById(requested.snapshotId()).orElseThrow()
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsDeterministicIdentifierCollisionInsteadOfOverwriting() {
        ExecutionConfigSnapshot original = factory.create(resolver.resolve(request(
                "collision", 3, Set.of("knowledge_search"), ExecutionBudget.unlimited()
        )));
        snapshotStore.persistIfAbsent(original);
        ExecutionConfigSnapshot conflicting = new ExecutionConfigSnapshot(
                original.snapshotId(),
                original.schemaVersion(),
                original.configHash(),
                "{\"different\":true}"
        );

        assertThrows(
                ExecutionConfigSnapshotException.class,
                () -> snapshotStore.persistIfAbsent(conflicting)
        );
        assertEquals(original, snapshotStore.findById(original.snapshotId()).orElseThrow());
    }

    @Test
    void agentRunPermanentlyReferencesTheSnapshotUsedAtAdmission() {
        AgentExecutionRequest originalRequest = request(
                "linked", 3, Set.of("knowledge_search"), ExecutionBudget.unlimited()
        );
        ExecutionConfigSnapshot first = snapshotStore.persistIfAbsent(
                factory.create(resolver.resolve(originalRequest))
        );
        historyStore.admit(originalRequest.withRuntimeConfigSnapshotId(first.snapshotId()));

        ExecutionConfigSnapshot later = snapshotStore.persistIfAbsent(factory.create(
                resolver.resolve(request(
                        "later-config", 8, Set.of("another_tool"),
                        ExecutionBudget.builder().maxModelCalls(1).build()
                ))
        ));

        AgentRunEntity run = runRepository.findByRunId(
                originalRequest.runSpec().runId()
        ).orElseThrow();
        assertNotEquals(first.snapshotId(), later.snapshotId());
        assertEquals(first.snapshotId(), run.getRuntimeConfigSnapshotId());
        assertEquals(first, snapshotStore.findById(first.snapshotId()).orElseThrow());
    }

    @Test
    void snapshotFailureOccursBeforeDurableAdmissionAndRuntimeStart() {
        AtomicInteger runtimeStarts = new AtomicInteger();
        ExecutionConfigSnapshotStore failingStore = new ExecutionConfigSnapshotStore() {
            @Override
            public ExecutionConfigSnapshot persistIfAbsent(ExecutionConfigSnapshot snapshot) {
                throw new ExecutionConfigSnapshotException("snapshot write failed");
            }

            @Override
            public Optional<ExecutionConfigSnapshot> findById(String snapshotId) {
                return Optional.empty();
            }
        };
        SnapshottingAgentExecutionCoordinator coordinator =
                new SnapshottingAgentExecutionCoordinator(
                        resolver,
                        factory,
                        failingStore,
                        request -> {
                            historyStore.admit(request);
                            runtimeStarts.incrementAndGet();
                            return completed();
                        }
                );
        AgentExecutionRequest request = request(
                "failure", 3, Set.of("knowledge_search"), ExecutionBudget.unlimited()
        );

        assertThrows(
                ExecutionConfigSnapshotException.class,
                () -> coordinator.execute(request)
        );
        assertTrue(runRepository.findByRunId(request.runSpec().runId()).isEmpty());
        assertEquals(0, runtimeStarts.get());
    }

    private AgentExecutionRequest request(
            String suffix,
            int maxIterations,
            Set<String> allowedTools,
            ExecutionBudget budget
    ) {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        "p9-snapshot-run-" + suffix,
                        "p9-snapshot-session-" + suffix,
                        List.of(AgentMessage.user("test")),
                        maxIterations,
                        allowedTools,
                        Set.of(),
                        budget
                ),
                "p9-snapshot-request-" + suffix,
                9001L
        );
    }

    private AgentRunResult completed() {
        return new AgentRunResult(
                "done", AgentStopReason.COMPLETED, 1, List.of(),
                List.of(AgentMessage.assistant("done")), TokenUsage.ZERO,
                null, null, null
        );
    }
}
