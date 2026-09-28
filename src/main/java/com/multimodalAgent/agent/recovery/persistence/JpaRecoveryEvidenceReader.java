package com.multimodalAgent.agent.recovery.persistence;

import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.entity.AgentStepEntity;
import com.multimodalAgent.agent.persistence.entity.ToolExecutionEntity;
import com.multimodalAgent.agent.persistence.model.AgentStepType;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.persistence.repository.AgentStepRepository;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.recovery.RecoveryCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointException;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointStore;
import com.multimodalAgent.agent.recovery.RecoveryEvidence;
import com.multimodalAgent.agent.recovery.RecoveryEvidenceIssue;
import com.multimodalAgent.agent.recovery.RecoveryEvidenceReader;
import com.multimodalAgent.agent.recovery.RecoveryModelEvidence;
import com.multimodalAgent.agent.recovery.RecoveryModelStatus;
import com.multimodalAgent.agent.recovery.RecoveryRunEvidence;
import com.multimodalAgent.agent.recovery.RecoveryRunStatus;
import com.multimodalAgent.agent.recovery.RecoveryToolEvidence;
import com.multimodalAgent.agent.recovery.RecoveryToolStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Loads one immutable recovery evidence snapshot without mutating durable state. */
@Component
public class JpaRecoveryEvidenceReader implements RecoveryEvidenceReader {

    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final ToolExecutionRepository toolExecutionRepository;
    private final RecoveryCheckpointStore checkpointStore;

    public JpaRecoveryEvidenceReader(
            AgentRunRepository runRepository,
            AgentStepRepository stepRepository,
            ToolExecutionRepository toolExecutionRepository,
            RecoveryCheckpointStore checkpointStore
    ) {
        this.runRepository = Objects.requireNonNull(
                runRepository,
                "runRepository must not be null"
        );
        this.stepRepository = Objects.requireNonNull(
                stepRepository,
                "stepRepository must not be null"
        );
        this.toolExecutionRepository = Objects.requireNonNull(
                toolExecutionRepository,
                "toolExecutionRepository must not be null"
        );
        this.checkpointStore = Objects.requireNonNull(
                checkpointStore,
                "checkpointStore must not be null"
        );
    }

    @Override
    @Transactional(readOnly = true)
    public RecoveryEvidence load(String runId) {
        requireText(runId, "runId");
        Optional<AgentRunEntity> runEntity = runRepository.findByRunId(runId);
        if (runEntity.isEmpty()) {
            return RecoveryEvidence.missingRun(runId);
        }

        List<AgentStepEntity> steps = stepRepository.findByRunIdOrderByStepIndexAsc(runId);
        List<ToolExecutionEntity> tools =
                toolExecutionRepository.findByRunIdOrderByCreatedAtAscIdAsc(runId);
        List<RecoveryEvidenceIssue> issues = new ArrayList<>();
        Optional<RecoveryCheckpoint> checkpoint;
        try {
            checkpoint = checkpointStore.findLatestByRunId(runId);
        } catch (RecoveryCheckpointException exception) {
            checkpoint = Optional.empty();
            issues.add(RecoveryEvidenceIssue.CHECKPOINT_UNREADABLE);
        }

        RecoveryRunEvidence run = toRunEvidence(runEntity.orElseThrow());
        List<RecoveryModelEvidence> modelFacts = steps.stream()
                .filter(step -> step.getStepType() == AgentStepType.MODEL)
                .map(this::toModelEvidence)
                .toList();
        List<RecoveryToolEvidence> toolFacts = toToolEvidence(steps, tools, issues);
        return new RecoveryEvidence(
                runId,
                Optional.of(run),
                checkpoint,
                modelFacts,
                toolFacts,
                issues
        );
    }

    private RecoveryRunEvidence toRunEvidence(AgentRunEntity run) {
        return new RecoveryRunEvidence(
                run.getRunId(),
                RecoveryRunStatus.valueOf(run.getStatus().name()),
                run.getCurrentIteration(),
                Optional.ofNullable(run.getRuntimeConfigSnapshotId())
        );
    }

    private RecoveryModelEvidence toModelEvidence(AgentStepEntity step) {
        return new RecoveryModelEvidence(
                step.getStepId(),
                step.getIteration(),
                step.getStepIndex(),
                RecoveryModelStatus.valueOf(step.getStatus().name())
        );
    }

    private List<RecoveryToolEvidence> toToolEvidence(
            List<AgentStepEntity> steps,
            List<ToolExecutionEntity> tools,
            List<RecoveryEvidenceIssue> issues
    ) {
        Map<String, AgentStepEntity> stepsById = new HashMap<>();
        for (AgentStepEntity step : steps) {
            stepsById.put(step.getStepId(), step);
        }
        Set<String> linkedToolStepIds = new HashSet<>();
        List<RecoveryToolEvidence> result = new ArrayList<>();
        for (ToolExecutionEntity tool : tools) {
            AgentStepEntity step = stepsById.get(tool.getStepId());
            boolean consistent = step != null && step.getStepType() == AgentStepType.TOOL;
            if (step == null) {
                issues.add(RecoveryEvidenceIssue.TOOL_STEP_MISSING);
            } else if (step.getStepType() != AgentStepType.TOOL) {
                issues.add(RecoveryEvidenceIssue.TOOL_STEP_TYPE_MISMATCH);
            } else {
                linkedToolStepIds.add(step.getStepId());
            }
            result.add(new RecoveryToolEvidence(
                    tool.getExecutionId(),
                    tool.getStepId(),
                    step == null ? 0 : step.getIteration(),
                    step == null ? 0 : step.getStepIndex(),
                    tool.getToolCallId(),
                    tool.getToolName(),
                    RecoveryToolStatus.valueOf(tool.getStatus().name()),
                    consistent
            ));
        }
        if (steps.stream().anyMatch(step -> step.getStepType() == AgentStepType.TOOL
                && !linkedToolStepIds.contains(step.getStepId()))) {
            issues.add(RecoveryEvidenceIssue.TOOL_EXECUTION_MISSING);
        }
        return List.copyOf(result);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
