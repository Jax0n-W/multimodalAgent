package com.multimodalAgent.agent.persistence.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.context.AgentContextAssembler;
import com.multimodalAgent.agent.context.AgentContextSnapshot;
import com.multimodalAgent.agent.context.AgentContextSnapshotFactory;
import com.multimodalAgent.agent.context.ContextAssemblyException;
import com.multimodalAgent.agent.context.ContextAssemblyInput;
import com.multimodalAgent.agent.context.RequestMessageContextSource;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.entity.AgentContextSnapshotEntity;
import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.repository.AgentContextSnapshotRepository;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p11-context-snapshot;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAgentContextSnapshotStore.class, JpaExecutionHistoryStore.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentContextSnapshotPersistenceTest {

    @Autowired
    private AgentContextSnapshotRepository snapshotRepository;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JpaAgentContextSnapshotStore snapshotStore;

    @Autowired
    private JpaExecutionHistoryStore historyStore;

    @Test
    void persistsAndIdempotentlyReusesSameSemanticSnapshotAcrossCreationTimes() {
        AgentContextSnapshot firstRequest = snapshot(
                "reuse", Instant.parse("2026-09-30T03:00:00Z")
        );
        AgentContextSnapshot secondRequest = snapshot(
                "reuse", Instant.parse("2026-09-30T03:01:00Z")
        );
        long before = snapshotRepository.count();

        AgentContextSnapshot first = snapshotStore.persistIfAbsent(firstRequest);
        AgentContextSnapshot second = snapshotStore.persistIfAbsent(secondRequest);

        assertEquals(firstRequest, first);
        assertEquals(first.snapshotId(), second.snapshotId());
        assertEquals(first.createdAt(), second.createdAt());
        assertEquals(before + 1, snapshotRepository.count());
        assertEquals(first, snapshotStore.findById(first.snapshotId()).orElseThrow());
    }

    @Test
    void rejectsSameIdentityWithDifferentStoredContent() {
        AgentContextSnapshot requested = snapshot(
                "collision", Instant.parse("2026-09-30T03:02:00Z")
        );
        snapshotRepository.saveAndFlush(new AgentContextSnapshotEntity(
                requested.snapshotId(),
                requested.schemaVersion(),
                requested.runId(),
                requested.sessionId(),
                requested.userId(),
                requested.contextHash(),
                "{}",
                requested.createdAt()
        ));

        assertThrows(
                ContextAssemblyException.class,
                () -> snapshotStore.persistIfAbsent(requested)
        );
        assertEquals("{}", snapshotRepository.findById(requested.snapshotId())
                .orElseThrow().getContextJson());
    }

    @Test
    void agentRunReferencesInitialContextWhileHistoricalNullRemainsReadable() {
        AgentContextSnapshot context = snapshot(
                "linked", Instant.parse("2026-09-30T03:03:00Z")
        );
        snapshotStore.persistIfAbsent(context);
        AgentExecutionRequest linked = request("linked")
                .withContextSnapshotId(context.snapshotId());
        historyStore.admit(linked);
        historyStore.admit(request("historical"));

        AgentRunEntity linkedRun = runRepository.findByRunId(
                linked.runSpec().runId()
        ).orElseThrow();
        AgentRunEntity historicalRun = runRepository.findByRunId(
                request("historical").runSpec().runId()
        ).orElseThrow();

        assertEquals(context.snapshotId(), linkedRun.getContextSnapshotId());
        assertNull(historicalRun.getContextSnapshotId());
        assertNotEquals(linkedRun.getRunId(), historicalRun.getRunId());
    }

    @Test
    void rejectsContextSnapshotOwnedByAnotherRunBeforeAdmission() {
        AgentContextSnapshot context = snapshot(
                "owner-run", Instant.parse("2026-09-30T03:04:00Z")
        );
        snapshotStore.persistIfAbsent(context);
        AgentExecutionRequest wrongRun = request(
                "p11-context-run-other",
                context.sessionId(),
                context.userId(),
                "wrong-run"
        ).withContextSnapshotId(context.snapshotId());

        assertThrows(ExecutionPersistenceException.class, () -> historyStore.admit(wrongRun));
        assertFalse(runRepository.findByRunId(wrongRun.runSpec().runId()).isPresent());
    }

    @Test
    void rejectsContextSnapshotOwnedByAnotherSessionBeforeAdmission() {
        AgentContextSnapshot context = snapshot(
                "owner-session", Instant.parse("2026-09-30T03:05:00Z")
        );
        snapshotStore.persistIfAbsent(context);
        AgentExecutionRequest wrongSession = request(
                context.runId(),
                "p11-context-session-other",
                context.userId(),
                "wrong-session"
        ).withContextSnapshotId(context.snapshotId());

        assertThrows(
                ExecutionPersistenceException.class,
                () -> historyStore.admit(wrongSession)
        );
        assertFalse(runRepository.findByRunId(wrongSession.runSpec().runId()).isPresent());
    }

    @Test
    void rejectsContextSnapshotOwnedByAnotherUserBeforeAdmission() {
        AgentContextSnapshot context = snapshot(
                "owner-user", Instant.parse("2026-09-30T03:06:00Z")
        );
        snapshotStore.persistIfAbsent(context);
        AgentExecutionRequest wrongUser = request(
                context.runId(),
                context.sessionId(),
                context.userId() + 1,
                "wrong-user"
        ).withContextSnapshotId(context.snapshotId());

        assertThrows(ExecutionPersistenceException.class, () -> historyStore.admit(wrongUser));
        assertFalse(runRepository.findByRunId(wrongUser.runSpec().runId()).isPresent());
    }

    @Test
    void rejectsMissingContextSnapshotBeforeAdmission() {
        AgentExecutionRequest request = request("missing-context")
                .withContextSnapshotId("context-v1-" + "0".repeat(64));

        assertThrows(ExecutionPersistenceException.class, () -> historyStore.admit(request));
        assertFalse(runRepository.findByRunId(request.runSpec().runId()).isPresent());
    }

    @Test
    void admissionDoesNotBypassStoredCanonicalContentVerification() {
        AgentContextSnapshot context = snapshot(
                "corrupt-admission", Instant.parse("2026-09-30T03:07:00Z")
        );
        snapshotRepository.saveAndFlush(new AgentContextSnapshotEntity(
                context.snapshotId(),
                context.schemaVersion(),
                context.runId(),
                context.sessionId(),
                context.userId(),
                context.contextHash(),
                "{}",
                context.createdAt()
        ));
        AgentExecutionRequest request = request("corrupt-admission")
                .withContextSnapshotId(context.snapshotId());

        assertThrows(ContextAssemblyException.class, () -> historyStore.admit(request));
        assertFalse(runRepository.findByRunId(request.runSpec().runId()).isPresent());
    }

    private AgentContextSnapshot snapshot(String suffix, Instant createdAt) {
        AgentExecutionRequest request = request(suffix);
        return new AgentContextAssembler(
                List.of(new RequestMessageContextSource()),
                new AgentContextSnapshotFactory(objectMapper),
                Clock.fixed(createdAt, ZoneOffset.UTC)
        ).assemble(new ContextAssemblyInput(
                request.runSpec().runId(),
                request.runSpec().sessionId(),
                request.userId(),
                request.runSpec().messages()
        ));
    }

    private AgentExecutionRequest request(String suffix) {
        return request(
                "p11-context-run-" + suffix,
                "p11-context-session-" + suffix,
                11001L,
                suffix
        );
    }

    private AgentExecutionRequest request(
            String runId,
            String sessionId,
            Long userId,
            String suffix
    ) {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        runId,
                        sessionId,
                        List.of(AgentMessage.user("message-" + suffix)),
                        3
                ),
                "p11-context-request-" + suffix,
                userId
        );
    }
}
