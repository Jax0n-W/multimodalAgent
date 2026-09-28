package com.multimodalAgent.agent.recovery;

import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentMessageRole;
import com.multimodalAgent.agent.runtime.model.ToolCall;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Pure, deterministic classifier of immutable durable recovery evidence. */
public final class RecoveryEligibilityEvaluator {

    public RecoveryDecision evaluate(RecoveryEvidence evidence) {
        if (evidence == null) {
            throw new IllegalArgumentException("evidence must not be null");
        }
        if (evidence.run().isEmpty()) {
            return decision(
                    evidence,
                    RecoveryDisposition.NOT_RESUMABLE,
                    RecoveryReason.RUN_NOT_FOUND,
                    List.of()
            );
        }

        RecoveryRunEvidence run = evidence.run().orElseThrow();
        if (isTerminal(run.status())) {
            return decision(
                    evidence,
                    RecoveryDisposition.NOT_RESUMABLE,
                    RecoveryReason.RUN_TERMINAL,
                    List.of()
            );
        }
        if (run.status() == RecoveryRunStatus.CREATED) {
            return decision(
                    evidence,
                    RecoveryDisposition.NOT_RESUMABLE,
                    RecoveryReason.RUN_NOT_STARTED,
                    List.of()
            );
        }

        RecoveryReason evidenceIssue = evidenceIssueReason(evidence.issues());
        if (evidenceIssue != null) {
            return decision(
                    evidence,
                    RecoveryDisposition.NOT_RESUMABLE,
                    evidenceIssue,
                    List.of()
            );
        }
        if (!run.runId().equals(evidence.runId())) {
            return inconsistentCheckpoint(evidence);
        }

        Optional<RecoveryCheckpoint> checkpoint = evidence.latestCheckpoint();
        CheckpointIndex checkpointIndex = checkpoint.map(this::index).orElse(null);
        if (checkpoint.isPresent()
                && !checkpointConsistent(run, checkpoint.orElseThrow(), checkpointIndex)) {
            return inconsistentCheckpoint(evidence);
        }
        if (!modelHistoryConsistent(evidence, checkpoint)) {
            return decision(
                    evidence,
                    RecoveryDisposition.NOT_RESUMABLE,
                    RecoveryReason.INCONSISTENT_MODEL_HISTORY,
                    List.of()
            );
        }
        if (!toolHistoryConsistent(evidence, checkpoint, checkpointIndex)) {
            return decision(
                    evidence,
                    RecoveryDisposition.NOT_RESUMABLE,
                    RecoveryReason.INCONSISTENT_TOOL_HISTORY,
                    List.of()
            );
        }

        if (run.status() == RecoveryRunStatus.WAITING_APPROVAL) {
            if (checkpoint.isPresent()
                    && checkpoint.orElseThrow().boundary()
                    == RecoveryCheckpointBoundary.WAITING_APPROVAL) {
                return decision(
                        evidence,
                        RecoveryDisposition.MANUAL_INTERVENTION,
                        RecoveryReason.AWAITING_APPROVAL,
                        List.of()
                );
            }
            return decision(
                    evidence,
                    RecoveryDisposition.NOT_RESUMABLE,
                    RecoveryReason.MISSING_APPROVAL_CHECKPOINT,
                    List.of()
            );
        }

        if (checkpoint.isEmpty()) {
            return decision(
                    evidence,
                    RecoveryDisposition.NOT_RESUMABLE,
                    RecoveryReason.MISSING_CHECKPOINT,
                    List.of()
            );
        }

        List<RecoveryToolDiagnostic> ambiguousTools = evidence.toolFacts().stream()
                .filter(tool -> tool.status() == RecoveryToolStatus.STARTED
                        || tool.status() == RecoveryToolStatus.UNKNOWN)
                .sorted(Comparator.comparing(RecoveryToolEvidence::toolCallId)
                        .thenComparing(RecoveryToolEvidence::toolName)
                        .thenComparing(tool -> tool.status().name()))
                .map(tool -> new RecoveryToolDiagnostic(
                        tool.toolCallId(), tool.toolName(), tool.status()
                ))
                .toList();
        if (!ambiguousTools.isEmpty()) {
            return decision(
                    evidence,
                    RecoveryDisposition.REQUIRES_RECONCILIATION,
                    RecoveryReason.AMBIGUOUS_TOOL_EXECUTION,
                    ambiguousTools
            );
        }

        if (evidence.toolFacts().stream()
                .filter(tool -> isConfirmed(tool.status()))
                .anyMatch(tool -> !checkpointIndex.hasToolResult(
                        tool.toolCallId(), tool.toolName()
                ))) {
            return decision(
                    evidence,
                    RecoveryDisposition.NOT_RESUMABLE,
                    RecoveryReason.CHECKPOINT_BEHIND_CONFIRMED_TOOL_FACT,
                    List.of()
            );
        }

        RecoveryCheckpoint latest = checkpoint.orElseThrow();
        if (evidence.modelFacts().stream()
                .filter(model -> model.status() == RecoveryModelStatus.SUCCEEDED
                        || model.status() == RecoveryModelStatus.FAILED)
                .anyMatch(model -> !modelOutcomeCovered(model, latest))) {
            return decision(
                    evidence,
                    RecoveryDisposition.NOT_RESUMABLE,
                    RecoveryReason.CHECKPOINT_BEHIND_CONFIRMED_MODEL_FACT,
                    List.of()
            );
        }
        if (evidence.modelFacts().stream()
                .anyMatch(model -> model.status() == RecoveryModelStatus.RUNNING)) {
            return decision(
                    evidence,
                    RecoveryDisposition.NOT_RESUMABLE,
                    RecoveryReason.IN_FLIGHT_MODEL_ATTEMPT,
                    List.of()
            );
        }
        if (hasUnresolvedModelControlState(evidence.modelFacts(), latest)) {
            return decision(
                    evidence,
                    RecoveryDisposition.NOT_RESUMABLE,
                    RecoveryReason.UNRESOLVED_MODEL_CONTROL_STATE,
                    List.of()
            );
        }

        return decision(
                evidence,
                RecoveryDisposition.SAFE_TO_RESUME,
                RecoveryReason.ELIGIBLE,
                List.of()
        );
    }

    private boolean checkpointConsistent(
            RecoveryRunEvidence run,
            RecoveryCheckpoint checkpoint,
            CheckpointIndex index
    ) {
        return checkpoint.runId().equals(run.runId())
                && run.runtimeConfigSnapshotId().isPresent()
                && checkpoint.runtimeConfigSnapshotId().equals(
                        run.runtimeConfigSnapshotId().orElseThrow()
                )
                && checkpoint.iteration() <= run.currentIteration()
                && index.consistent();
    }

    private boolean modelHistoryConsistent(
            RecoveryEvidence evidence,
            Optional<RecoveryCheckpoint> checkpoint
    ) {
        RecoveryRunEvidence run = evidence.run().orElseThrow();
        Set<String> stepIds = new HashSet<>();
        Set<Integer> stepIndexes = new HashSet<>();
        Set<Integer> iterations = new HashSet<>();
        for (RecoveryModelEvidence model : evidence.modelFacts()) {
            if (!stepIds.add(model.stepId())
                    || !stepIndexes.add(model.stepIndex())
                    || !iterations.add(model.iteration())
                    || model.iteration() > run.currentIteration()
                    || model.status() == RecoveryModelStatus.PLANNED
                    || model.status() == RecoveryModelStatus.SKIPPED) {
                return false;
            }
            if (model.status() == RecoveryModelStatus.RUNNING
                    && checkpoint.isPresent()
                    && model.iteration() <= checkpoint.orElseThrow().iteration()) {
                return false;
            }
            if (model.status() == RecoveryModelStatus.FAILED
                    && checkpoint.isPresent()
                    && model.iteration() <= checkpoint.orElseThrow().iteration()) {
                return false;
            }
        }
        if (checkpoint.isPresent()) {
            RecoveryCheckpoint latest = checkpoint.orElseThrow();
            if ((latest.boundary() == RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME
                    || latest.boundary() == RecoveryCheckpointBoundary.WAITING_APPROVAL)
                    && evidence.modelFacts().stream().noneMatch(model ->
                    model.iteration() == latest.iteration()
                            && model.status() == RecoveryModelStatus.SUCCEEDED)) {
                return false;
            }
        }
        return true;
    }

    private boolean toolHistoryConsistent(
            RecoveryEvidence evidence,
            Optional<RecoveryCheckpoint> checkpoint,
            CheckpointIndex checkpointIndex
    ) {
        RecoveryRunEvidence run = evidence.run().orElseThrow();
        Set<String> executionIds = new HashSet<>();
        Set<String> toolCallIds = new HashSet<>();
        Set<Integer> stepIndexes = new HashSet<>();
        Map<String, RecoveryToolEvidence> toolsByCall = new HashMap<>();
        for (RecoveryToolEvidence tool : evidence.toolFacts()) {
            if (!tool.linkedStepConsistent()
                    || tool.iteration() < 1
                    || tool.stepIndex() < 1
                    || tool.iteration() > run.currentIteration()
                    || !executionIds.add(tool.executionId())
                    || !toolCallIds.add(tool.toolCallId())
                    || !stepIndexes.add(tool.stepIndex())) {
                return false;
            }
            toolsByCall.put(tool.toolCallId(), tool);
            if (checkpoint.isPresent()
                    && tool.status() == RecoveryToolStatus.PLANNED
                    && !checkpointIndex.hasToolCall(tool.toolCallId(), tool.toolName())) {
                return false;
            }
        }
        if (checkpoint.isPresent()) {
            for (Map.Entry<String, String> result : checkpointIndex.toolResults().entrySet()) {
                RecoveryToolEvidence tool = toolsByCall.get(result.getKey());
                if (tool == null
                        || !tool.toolName().equals(result.getValue())
                        || !isConfirmed(tool.status())) {
                    return false;
                }
            }
            RecoveryCheckpoint latest = checkpoint.orElseThrow();
            if (latest.boundary() == RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME) {
                AgentMessage last = latest.messages().get(latest.messages().size() - 1);
                if (last.role() != AgentMessageRole.TOOL
                        || !checkpointIndex.hasToolResult(last.toolCallId(), last.toolName())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean modelOutcomeCovered(
            RecoveryModelEvidence model,
            RecoveryCheckpoint checkpoint
    ) {
        return model.status() == RecoveryModelStatus.SUCCEEDED
                && model.iteration() <= checkpoint.iteration();
    }

    private boolean hasUnresolvedModelControlState(
            List<RecoveryModelEvidence> modelFacts,
            RecoveryCheckpoint checkpoint
    ) {
        if (checkpoint.boundary() != RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME
                || modelFacts.stream().noneMatch(model ->
                model.iteration() == checkpoint.iteration()
                        && model.status() == RecoveryModelStatus.SUCCEEDED)) {
            return false;
        }
        AgentMessage last = checkpoint.messages().get(checkpoint.messages().size() - 1);
        return last.role() != AgentMessageRole.ASSISTANT || last.toolCalls().isEmpty();
    }

    private CheckpointIndex index(RecoveryCheckpoint checkpoint) {
        Map<String, String> toolCalls = new LinkedHashMap<>();
        Map<String, String> toolResults = new LinkedHashMap<>();
        boolean consistent = true;
        for (AgentMessage message : checkpoint.messages()) {
            if (message.role() == AgentMessageRole.ASSISTANT) {
                for (ToolCall call : message.toolCalls()) {
                    String previous = toolCalls.putIfAbsent(call.id(), call.name());
                    if (previous != null) {
                        consistent = false;
                    }
                }
            }
            if (message.role() == AgentMessageRole.TOOL) {
                String previous = toolResults.putIfAbsent(
                        message.toolCallId(),
                        message.toolName()
                );
                if (previous != null || !message.toolName().equals(
                        toolCalls.get(message.toolCallId())
                )) {
                    consistent = false;
                }
            }
        }
        if (!toolCalls.keySet().equals(checkpoint.seenToolCallIds())) {
            consistent = false;
        }
        return new CheckpointIndex(
                Map.copyOf(toolCalls),
                Map.copyOf(toolResults),
                consistent
        );
    }

    private RecoveryReason evidenceIssueReason(List<RecoveryEvidenceIssue> issues) {
        if (issues.contains(RecoveryEvidenceIssue.CHECKPOINT_UNREADABLE)) {
            return RecoveryReason.INCONSISTENT_CHECKPOINT_STATE;
        }
        return issues.isEmpty() ? null : RecoveryReason.INCONSISTENT_TOOL_HISTORY;
    }

    private RecoveryDecision inconsistentCheckpoint(RecoveryEvidence evidence) {
        return decision(
                evidence,
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.INCONSISTENT_CHECKPOINT_STATE,
                List.of()
        );
    }

    private RecoveryDecision decision(
            RecoveryEvidence evidence,
            RecoveryDisposition disposition,
            RecoveryReason reason,
            List<RecoveryToolDiagnostic> ambiguousTools
    ) {
        Optional<String> checkpointId = evidence.latestCheckpoint()
                .map(RecoveryCheckpoint::checkpointId);
        Optional<Long> checkpointSequence = evidence.latestCheckpoint()
                .map(RecoveryCheckpoint::sequence);
        List<RecoveryEvidenceIssue> issues = new ArrayList<>(evidence.issues());
        issues.sort(Comparator.comparing(Enum::name));
        return new RecoveryDecision(
                evidence.runId(),
                disposition,
                reason,
                checkpointId,
                checkpointSequence,
                evidence.modelFacts().size(),
                evidence.toolFacts().size(),
                ambiguousTools,
                issues
        );
    }

    private boolean isTerminal(RecoveryRunStatus status) {
        return status == RecoveryRunStatus.COMPLETED
                || status == RecoveryRunStatus.FAILED
                || status == RecoveryRunStatus.CANCELLED;
    }

    private boolean isConfirmed(RecoveryToolStatus status) {
        return status == RecoveryToolStatus.SUCCEEDED
                || status == RecoveryToolStatus.FAILED
                || status == RecoveryToolStatus.BLOCKED
                || status == RecoveryToolStatus.CANCELLED;
    }

    private record CheckpointIndex(
            Map<String, String> toolCalls,
            Map<String, String> toolResults,
            boolean consistent
    ) {

        boolean hasToolCall(String toolCallId, String toolName) {
            return toolName.equals(toolCalls.get(toolCallId));
        }

        boolean hasToolResult(String toolCallId, String toolName) {
            return toolName.equals(toolResults.get(toolCallId));
        }
    }
}
