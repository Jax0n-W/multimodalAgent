package com.multimodalAgent.agent.persistence.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.context.AgentContextAssembler;
import com.multimodalAgent.agent.context.AgentContextSnapshot;
import com.multimodalAgent.agent.context.AgentContextSnapshotFactory;
import com.multimodalAgent.agent.context.ContextAssemblyException;
import com.multimodalAgent.agent.context.ContextAssemblyInput;
import com.multimodalAgent.agent.context.RequestMessageContextSource;
import com.multimodalAgent.agent.context.memory.ConversationMemoryPolicy;
import com.multimodalAgent.agent.context.memory.ConversationMemoryQuery;
import com.multimodalAgent.agent.context.memory.ConversationMemorySource;
import com.multimodalAgent.agent.context.memory.ConversationTurn;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.entity.AgentContextSnapshotEntity;
import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunPhase;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.repository.AgentContextSnapshotRepository;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.AgentEventMetadata;
import com.multimodalAgent.agent.runtime.event.RunCompletedEvent;
import com.multimodalAgent.agent.runtime.event.RunStartedEvent;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p11-conversation-memory;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAgentContextSnapshotStore.class,
        JpaExecutionHistoryStore.class,
        JpaCompletedRunConversationMemoryReader.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConversationMemoryPersistenceTest {

    private static final String SESSION = "memory-session";
    private static final Long USER = 11201L;
    private static final Instant BASE = Instant.parse("2026-10-10T02:00:00Z");

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentContextSnapshotRepository snapshotRepository;

    @Autowired
    private JpaAgentContextSnapshotStore snapshotStore;

    @Autowired
    private JpaExecutionHistoryStore historyStore;

    @Autowired
    private JpaCompletedRunConversationMemoryReader reader;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        runRepository.deleteAll();
        snapshotRepository.deleteAll();
    }

    @Test
    void durableFirstRunFeedsSecondRunSnapshotAndAdmission() {
        String firstRunId = "memory-durable-a";
        AgentContextSnapshot firstContext = requestSnapshot(
                firstRunId, SESSION, USER, "我最近考试压力很大", BASE
        );
        snapshotStore.persistIfAbsent(firstContext);
        AgentExecutionRequest firstRequest = request(
                firstRunId, SESSION, USER, "我最近考试压力很大"
        ).withContextSnapshotId(firstContext.snapshotId());
        historyStore.admit(firstRequest);
        historyStore.record(new RunStartedEvent(metadata(firstRunId, 1, BASE.plusSeconds(1))));
        historyStore.record(new RunCompletedEvent(metadata(firstRunId, 2, BASE.plusSeconds(2))));
        historyStore.finalizeRun(firstRunId, completed(
                "我理解你最近比较紧张", firstRequest.runSpec().messages()
        ));

        String secondRunId = "memory-durable-b";
        AgentContextSnapshot secondContext = memoryAssembler(6, 12000).assemble(
                new ContextAssemblyInput(
                        secondRunId,
                        SESSION,
                        USER,
                        List.of(AgentMessage.user("你还记得我刚才说什么吗"))
                )
        );
        snapshotStore.persistIfAbsent(secondContext);
        AgentExecutionRequest secondRequest = request(
                secondRunId, SESSION, USER, "你还记得我刚才说什么吗"
        ).withContextSnapshotId(secondContext.snapshotId());
        historyStore.admit(secondRequest);

        assertEquals(List.of(
                AgentMessage.user("我最近考试压力很大"),
                AgentMessage.assistant("我理解你最近比较紧张"),
                AgentMessage.user("你还记得我刚才说什么吗")
        ), secondContext.messages());
        assertEquals(secondContext.snapshotId(), runRepository.findByRunId(secondRunId)
                .orElseThrow().getContextSnapshotId());
        assertEquals(2, runRepository.count());
        assertEquals(AgentRunStatus.COMPLETED, runRepository.findByRunId(firstRunId)
                .orElseThrow().getStatus());
        assertEquals(firstContext, snapshotStore.findById(firstContext.snapshotId()).orElseThrow());
    }

    @Test
    void isolatesUserAndSessionAndExcludesCurrentRun() {
        completedRun("isolation-visible", SESSION, USER, "visible", "answer", BASE);
        completedRun("isolation-other-user", SESSION, USER + 1, "secret", "hidden", BASE);
        completedRun("isolation-other-session", "another-session", USER,
                "other", "hidden", BASE);

        List<ConversationTurn> visible = reader.read(new ConversationMemoryQuery(
                USER, SESSION, "current", 10
        ));
        List<ConversationTurn> currentExcluded = reader.read(new ConversationMemoryQuery(
                USER, SESSION, "isolation-visible", 10
        ));

        assertEquals(List.of("isolation-visible"), visible.stream()
                .map(ConversationTurn::runId).toList());
        assertEquals(List.of(), currentExcluded);
    }

    @Test
    void exposesOnlyCompletedFinalizedRunsAndSkipsLegacyNullSnapshot() {
        completedRun("visibility-valid", SESSION, USER, "valid", "answer", BASE);
        saveRun("visibility-running", SESSION, USER, AgentRunStatus.RUNNING, null,
                "answer", null, BASE.plusSeconds(1));
        saveRun("visibility-waiting", SESSION, USER, AgentRunStatus.WAITING_APPROVAL,
                AgentStopReason.WAITING_APPROVAL, "answer", null, BASE.plusSeconds(1));
        saveRun("visibility-cancelled", SESSION, USER, AgentRunStatus.CANCELLED,
                AgentStopReason.CANCELLED, "answer", null, BASE.plusSeconds(1));
        AgentContextSnapshot noFinal = requestSnapshot(
                "visibility-no-final", SESSION, USER, "no-final", BASE
        );
        snapshotStore.persistIfAbsent(noFinal);
        saveRun("visibility-no-final", SESSION, USER, AgentRunStatus.COMPLETED,
                AgentStopReason.COMPLETED, null, noFinal.snapshotId(), BASE.plusSeconds(2));
        saveRun("visibility-legacy-null", SESSION, USER, AgentRunStatus.COMPLETED,
                AgentStopReason.COMPLETED, "legacy", null, BASE.plusSeconds(3));
        AgentContextSnapshot failed = requestSnapshot(
                "visibility-failed", SESSION, USER, "failed", BASE
        );
        snapshotStore.persistIfAbsent(failed);
        saveRun("visibility-failed", SESSION, USER, AgentRunStatus.FAILED,
                AgentStopReason.MODEL_ERROR, "not-visible", failed.snapshotId(),
                BASE.plusSeconds(4));

        List<ConversationTurn> turns = reader.read(new ConversationMemoryQuery(
                USER, SESSION, "current-visibility", 10
        ));

        assertEquals(List.of("visibility-valid"), turns.stream()
                .map(ConversationTurn::runId).toList());
    }

    @Test
    void reconstructsEachRunsOwnRequestWithoutDuplicatingPriorSnapshotMemory() {
        completedRun("duplicate-a", SESSION, USER, "request-a", "answer-a", BASE);
        AgentContextSnapshot runBContext = memoryAssembler(6, 12000).assemble(
                new ContextAssemblyInput(
                        "duplicate-b", SESSION, USER, List.of(AgentMessage.user("request-b"))
                )
        );
        snapshotStore.persistIfAbsent(runBContext);
        saveRun("duplicate-b", SESSION, USER, AgentRunStatus.COMPLETED,
                AgentStopReason.COMPLETED, "answer-b", runBContext.snapshotId(),
                BASE.plusSeconds(1));

        List<ConversationTurn> turns = reader.read(new ConversationMemoryQuery(
                USER, SESSION, "duplicate-c", 10
        ));

        assertEquals(List.of("duplicate-b", "duplicate-a"), turns.stream()
                .map(ConversationTurn::runId).toList());
        assertEquals(List.of("request-b", "request-a"), turns.stream()
                .map(ConversationTurn::userContent).toList());
    }

    @Test
    void durableQueryUsesRunIdDescendingAsCompletionTimeTieBreaker() {
        completedRun("tie-a", SESSION, USER, "a", "answer-a", BASE);
        completedRun("tie-b", SESSION, USER, "b", "answer-b", BASE);

        assertEquals(List.of("tie-b", "tie-a"), reader.read(new ConversationMemoryQuery(
                USER, SESSION, "tie-current", 6
        )).stream().map(ConversationTurn::runId).toList());
    }

    @Test
    void corruptedHistoricalSnapshotFailsClosedWithoutLeakingContent() {
        AgentContextSnapshot valid = requestSnapshot(
                "corrupt-history", SESSION, USER, "sensitive", BASE
        );
        snapshotRepository.saveAndFlush(new AgentContextSnapshotEntity(
                valid.snapshotId(), valid.schemaVersion(), valid.runId(), valid.sessionId(),
                valid.userId(), valid.contextHash(), "{}", valid.createdAt()
        ));
        saveRun("corrupt-history", SESSION, USER, AgentRunStatus.COMPLETED,
                AgentStopReason.COMPLETED, "answer", valid.snapshotId(), BASE);

        ContextAssemblyException exception = assertThrows(
                ContextAssemblyException.class,
                () -> reader.read(new ConversationMemoryQuery(USER, SESSION, "current", 6))
        );

        assertFalse(exception.getMessage().contains("sensitive"));
    }

    @Test
    void ambiguousMultiMessageRequestContributionIsSkippedConservatively() {
        String runId = "ambiguous-request";
        AgentContextSnapshot context = new AgentContextAssembler(
                List.of(new RequestMessageContextSource()),
                new AgentContextSnapshotFactory(objectMapper),
                Clock.fixed(BASE, ZoneOffset.UTC)
        ).assemble(new ContextAssemblyInput(
                runId,
                SESSION,
                USER,
                List.of(AgentMessage.user("one"), AgentMessage.user("two"))
        ));
        snapshotStore.persistIfAbsent(context);
        saveRun(runId, SESSION, USER, AgentRunStatus.COMPLETED,
                AgentStopReason.COMPLETED, "answer", context.snapshotId(), BASE);

        assertEquals(List.of(), reader.read(new ConversationMemoryQuery(
                USER, SESSION, "current-ambiguous", 6
        )));
    }

    private AgentRunEntity completedRun(
            String runId,
            String sessionId,
            Long userId,
            String userContent,
            String finalContent,
            Instant completedAt
    ) {
        AgentContextSnapshot context = requestSnapshot(
                runId, sessionId, userId, userContent, completedAt.minusSeconds(1)
        );
        snapshotStore.persistIfAbsent(context);
        return saveRun(runId, sessionId, userId, AgentRunStatus.COMPLETED,
                AgentStopReason.COMPLETED, finalContent, context.snapshotId(), completedAt);
    }

    private AgentRunEntity saveRun(
            String runId,
            String sessionId,
            Long userId,
            AgentRunStatus status,
            AgentStopReason stopReason,
            String finalContent,
            String contextSnapshotId,
            Instant completedAt
    ) {
        AgentRunEntity run = new AgentRunEntity(
                runId, "request-" + runId, userId, sessionId, status, AgentRunPhase.RECEIVED
        );
        run.setStopReason(stopReason);
        run.setFinalContent(finalContent);
        run.setContextSnapshotId(contextSnapshotId);
        run.setCompletedAt(completedAt);
        return runRepository.saveAndFlush(run);
    }

    private AgentContextSnapshot requestSnapshot(
            String runId,
            String sessionId,
            Long userId,
            String message,
            Instant createdAt
    ) {
        return new AgentContextAssembler(
                List.of(new RequestMessageContextSource()),
                new AgentContextSnapshotFactory(objectMapper),
                Clock.fixed(createdAt, ZoneOffset.UTC)
        ).assemble(new ContextAssemblyInput(
                runId, sessionId, userId, List.of(AgentMessage.user(message))
        ));
    }

    private AgentContextAssembler memoryAssembler(int maxTurns, int maxChars) {
        return new AgentContextAssembler(
                List.of(
                        new RequestMessageContextSource(),
                        new ConversationMemorySource(
                                reader, new ConversationMemoryPolicy(maxTurns, maxChars)
                        )
                ),
                new AgentContextSnapshotFactory(objectMapper),
                Clock.fixed(BASE.plusSeconds(30), ZoneOffset.UTC)
        );
    }

    private AgentExecutionRequest request(
            String runId,
            String sessionId,
            Long userId,
            String message
    ) {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        runId, sessionId, List.of(AgentMessage.user(message)), 3
                ),
                "request-" + runId,
                userId
        );
    }

    private AgentEventMetadata metadata(String runId, long sequence, Instant occurredAt) {
        return new AgentEventMetadata(
                "event-" + runId + "-" + sequence, runId, sequence, occurredAt, 0
        );
    }

    private AgentRunResult completed(String content, List<AgentMessage> messages) {
        return new AgentRunResult(
                content, AgentStopReason.COMPLETED, 1, List.of(), messages,
                TokenUsage.ZERO, null, null, null
        );
    }
}
