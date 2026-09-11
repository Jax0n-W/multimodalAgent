package com.multimodalAgent.agent.persistence;

import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.entity.AgentStepEntity;
import com.multimodalAgent.agent.persistence.entity.ToolExecutionEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunPhase;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.model.AgentStepStatus;
import com.multimodalAgent.agent.persistence.model.AgentStepType;
import com.multimodalAgent.agent.persistence.model.ToolExecutionStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.persistence.repository.AgentStepRepository;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-runtime-persistence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AgentRuntimePersistenceTest {

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private AgentStepRepository agentStepRepository;

    @Autowired
    private ToolExecutionRepository toolExecutionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldCreateRuntimeTablesWithFlyway() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE UPPER(TABLE_SCHEMA) = 'PUBLIC'
                  AND UPPER(TABLE_NAME) IN ('AGENT_RUNS', 'AGENT_STEPS', 'TOOL_EXECUTIONS')
                """,
                Integer.class
        );

        assertEquals(3, tableCount);
    }

    @Test
    void shouldSaveAndFindAgentRunByRunId() {
        AgentRunEntity run = newRun("run-save", "request-save", "session-save");
        run.setModelVersion("gpt-4o-mini-2026-09");
        run.setPromptVersion("prompt-v1");
        run.setSkillVersion("skills-v1");
        run.setCurrentIteration(2);
        run.setStartedAt(Instant.parse("2026-09-09T08:00:00Z"));
        agentRunRepository.saveAndFlush(run);
        entityManager.clear();

        AgentRunEntity restored = agentRunRepository.findByRunId("run-save").orElseThrow();

        assertEquals("run-save", restored.getRunId());
        assertEquals("run-save", agentRunRepository.findByRequestId("request-save").orElseThrow().getRunId());
        assertEquals(1, agentRunRepository.findBySessionIdOrderByCreatedAtDesc("session-save").size());
        assertEquals("session-save", restored.getSessionId());
        assertEquals(1001L, restored.getUserId());
        assertEquals(2, restored.getCurrentIteration());
        assertEquals(AgentRunStatus.RUNNING, restored.getStatus());
        assertEquals(AgentRunPhase.MODEL_RUNNING, restored.getPhase());
        assertEquals("gpt-4o-mini-2026-09", restored.getModelVersion());
        assertEquals("prompt-v1", restored.getPromptVersion());
        assertEquals("skills-v1", restored.getSkillVersion());
        assertEquals(Instant.parse("2026-09-09T08:00:00Z"), restored.getStartedAt());
        assertNotNull(restored.getCreatedAt());
        assertNotNull(restored.getUpdatedAt());
    }

    @Test
    void shouldAssociateMultipleOrderedStepsWithOneRun() {
        agentRunRepository.saveAndFlush(newRun("run-steps", "request-steps", "session-steps"));
        agentStepRepository.saveAndFlush(new AgentStepEntity(
                "step-model-2", "run-steps", 2, 3, AgentStepType.MODEL, AgentStepStatus.SUCCEEDED
        ));
        agentStepRepository.saveAndFlush(new AgentStepEntity(
                "step-model-1", "run-steps", 1, 1, AgentStepType.MODEL, AgentStepStatus.SUCCEEDED
        ));
        agentStepRepository.saveAndFlush(new AgentStepEntity(
                "step-tool-1", "run-steps", 1, 2, AgentStepType.TOOL, AgentStepStatus.SUCCEEDED
        ));
        entityManager.clear();

        List<AgentStepEntity> steps =
                agentStepRepository.findByRunIdOrderByStepIndexAsc("run-steps");

        assertEquals(3, steps.size());
        assertEquals(List.of(1, 2, 3), steps.stream().map(AgentStepEntity::getStepIndex).toList());
        assertEquals(List.of(1, 1, 2), steps.stream().map(AgentStepEntity::getIteration).toList());
        assertEquals(List.of(AgentStepType.MODEL, AgentStepType.TOOL, AgentStepType.MODEL),
                steps.stream().map(AgentStepEntity::getStepType).toList());
        assertEquals(1, steps.stream().map(AgentStepEntity::getRunId).distinct().count());
        assertEquals("run-steps", steps.get(0).getRunId());
    }

    @Test
    void shouldAssociateToolExecutionWithRunStepAndToolCall() {
        agentRunRepository.saveAndFlush(newRun("run-tool", "request-tool", "session-tool"));
        agentStepRepository.saveAndFlush(new AgentStepEntity(
                "step-tool", "run-tool", 1, 1, AgentStepType.TOOL, AgentStepStatus.RUNNING
        ));
        ToolExecutionEntity execution = new ToolExecutionEntity(
                "execution-1",
                "run-tool",
                "step-tool",
                "call-1",
                "knowledge_search",
                ToolExecutionStatus.SUCCEEDED
        );
        execution.setArgumentsHash("sha256:arguments");
        execution.setIdempotencyKey("run-tool:call-1");
        execution.setResultSummary("Redis Sentinel provides automatic failover.");
        toolExecutionRepository.saveAndFlush(execution);
        entityManager.clear();

        ToolExecutionEntity restored = toolExecutionRepository
                .findByRunIdOrderByCreatedAtAscIdAsc("run-tool")
                .get(0);

        assertEquals("execution-1", restored.getExecutionId());
        assertEquals("run-tool", restored.getRunId());
        assertEquals("step-tool", restored.getStepId());
        assertEquals("call-1", restored.getToolCallId());
        assertEquals("knowledge_search", restored.getToolName());
        assertEquals(ToolExecutionStatus.SUCCEEDED, restored.getStatus());
        assertEquals("execution-1",
                toolExecutionRepository.findByIdempotencyKey("run-tool:call-1").orElseThrow().getExecutionId());
    }

    @Test
    void shouldPersistEnumsAsStringsIncludingUnknownToolOutcome() {
        agentRunRepository.saveAndFlush(newRun("run-enum", "request-enum", "session-enum"));
        agentStepRepository.saveAndFlush(new AgentStepEntity(
                "step-enum", "run-enum", 1, 1, AgentStepType.TOOL, AgentStepStatus.RUNNING
        ));
        toolExecutionRepository.saveAndFlush(new ToolExecutionEntity(
                "execution-unknown",
                "run-enum",
                "step-enum",
                "call-unknown",
                "appointment_create",
                ToolExecutionStatus.UNKNOWN
        ));
        entityManager.clear();

        AgentRunEntity restoredRun = agentRunRepository.findByRunId("run-enum").orElseThrow();
        ToolExecutionEntity restoredExecution = toolExecutionRepository
                .findByToolCallId("call-unknown")
                .get(0);
        String rawRunStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM agent_runs WHERE run_id = ?",
                String.class,
                "run-enum"
        );
        String rawToolStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM tool_executions WHERE execution_id = ?",
                String.class,
                "execution-unknown"
        );

        assertEquals(AgentRunStatus.RUNNING, restoredRun.getStatus());
        assertEquals(ToolExecutionStatus.UNKNOWN, restoredExecution.getStatus());
        assertEquals("RUNNING", rawRunStatus);
        assertEquals("UNKNOWN", rawToolStatus);
    }

    @Test
    void shouldPersistBlockedAndCancelledToolStatusesAsStrings() {
        agentRunRepository.saveAndFlush(newRun("run-tool-status", "request-tool-status", "session-tool-status"));
        agentStepRepository.saveAndFlush(new AgentStepEntity(
                "step-tool-status", "run-tool-status", 1, 1, AgentStepType.TOOL, AgentStepStatus.RUNNING
        ));
        toolExecutionRepository.saveAndFlush(new ToolExecutionEntity(
                "execution-blocked", "run-tool-status", "step-tool-status", "call-blocked",
                "appointment_create", ToolExecutionStatus.BLOCKED
        ));
        toolExecutionRepository.saveAndFlush(new ToolExecutionEntity(
                "execution-cancelled", "run-tool-status", "step-tool-status", "call-cancelled",
                "appointment_create", ToolExecutionStatus.CANCELLED
        ));
        entityManager.clear();

        assertEquals("BLOCKED", jdbcTemplate.queryForObject(
                "SELECT status FROM tool_executions WHERE execution_id = 'execution-blocked'", String.class));
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM tool_executions WHERE execution_id = 'execution-cancelled'", String.class));
    }

    @Test
    void shouldPersistNewStopReasonsAsStrings() {
        List<AgentStopReason> reasons = List.of(
                AgentStopReason.MODEL_TIMEOUT,
                AgentStopReason.CANCELLED,
                AgentStopReason.INTERNAL_ERROR
        );
        for (int index = 0; index < reasons.size(); index++) {
            AgentRunEntity run = newRun(
                    "run-stop-" + index,
                    "request-stop-" + index,
                    "session-stop-" + index
            );
            run.setStopReason(reasons.get(index));
            agentRunRepository.saveAndFlush(run);
        }
        entityManager.clear();

        for (int index = 0; index < reasons.size(); index++) {
            assertEquals(reasons.get(index).name(), jdbcTemplate.queryForObject(
                    "SELECT stop_reason FROM agent_runs WHERE run_id = ?",
                    String.class,
                    "run-stop-" + index
            ));
        }
    }

    @Test
    void shouldRejectDuplicateStepIndexWithinTheSameRunButAllowItAcrossRuns() {
        agentRunRepository.saveAndFlush(newRun("run-step-a", "request-step-a", "session-step-a"));
        agentRunRepository.saveAndFlush(newRun("run-step-b", "request-step-b", "session-step-b"));
        agentStepRepository.saveAndFlush(new AgentStepEntity(
                "step-a-1", "run-step-a", 1, 1, AgentStepType.MODEL, AgentStepStatus.SUCCEEDED
        ));
        agentStepRepository.saveAndFlush(new AgentStepEntity(
                "step-b-1", "run-step-b", 1, 1, AgentStepType.MODEL, AgentStepStatus.SUCCEEDED
        ));

        assertThrows(DataIntegrityViolationException.class, () ->
                agentStepRepository.saveAndFlush(new AgentStepEntity(
                        "step-a-duplicate", "run-step-a", 2, 1,
                        AgentStepType.MODEL, AgentStepStatus.SUCCEEDED
                ))
        );
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRejectStaleAgentRunUpdate() {
        agentRunRepository.saveAndFlush(newRun("run-lock", "request-lock", "session-lock"));
        AgentRunEntity firstWriter = agentRunRepository.findByRunId("run-lock").orElseThrow();
        AgentRunEntity staleWriter = agentRunRepository.findByRunId("run-lock").orElseThrow();

        firstWriter.setCurrentIteration(1);
        agentRunRepository.saveAndFlush(firstWriter);
        staleWriter.setCurrentIteration(2);

        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> agentRunRepository.saveAndFlush(staleWriter));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRejectStaleToolExecutionUpdate() {
        agentRunRepository.saveAndFlush(newRun("run-tool-lock", "request-tool-lock", "session-tool-lock"));
        agentStepRepository.saveAndFlush(new AgentStepEntity(
                "step-tool-lock", "run-tool-lock", 1, 1, AgentStepType.TOOL, AgentStepStatus.RUNNING
        ));
        toolExecutionRepository.saveAndFlush(new ToolExecutionEntity(
                "execution-lock", "run-tool-lock", "step-tool-lock", "call-lock",
                "appointment_create", ToolExecutionStatus.STARTED
        ));
        ToolExecutionEntity firstWriter = toolExecutionRepository.findByToolCallId("call-lock").get(0);
        ToolExecutionEntity staleWriter = toolExecutionRepository.findByToolCallId("call-lock").get(0);

        firstWriter.setStatus(ToolExecutionStatus.SUCCEEDED);
        toolExecutionRepository.saveAndFlush(firstWriter);
        staleWriter.setStatus(ToolExecutionStatus.UNKNOWN);

        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> toolExecutionRepository.saveAndFlush(staleWriter));
    }

    @Test
    void shouldRejectDuplicateRunId() {
        agentRunRepository.saveAndFlush(newRun("run-duplicate", "request-first", "session-duplicate"));

        assertThrows(DataIntegrityViolationException.class, () ->
                agentRunRepository.saveAndFlush(
                        newRun("run-duplicate", "request-second", "session-duplicate")
                )
        );
    }

    @Test
    void shouldRejectDuplicateRequestIdUsedForIdempotency() {
        agentRunRepository.saveAndFlush(newRun("run-first", "request-duplicate", "session-duplicate"));

        assertThrows(DataIntegrityViolationException.class, () ->
                agentRunRepository.saveAndFlush(
                        newRun("run-second", "request-duplicate", "session-duplicate")
                )
        );
    }

    private AgentRunEntity newRun(String runId, String requestId, String sessionId) {
        return new AgentRunEntity(
                runId,
                requestId,
                1001L,
                sessionId,
                AgentRunStatus.RUNNING,
                AgentRunPhase.MODEL_RUNNING
        );
    }
}
