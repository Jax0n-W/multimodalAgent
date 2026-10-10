package com.multimodalAgent.agent.persistence.integration;

import com.multimodalAgent.agent.context.AgentContextSnapshot;
import com.multimodalAgent.agent.context.AgentContextSnapshotStore;
import com.multimodalAgent.agent.context.ContextAssemblyException;
import com.multimodalAgent.agent.context.ContextProvenance;
import com.multimodalAgent.agent.context.RequestMessageContextSource;
import com.multimodalAgent.agent.context.memory.ConversationMemoryQuery;
import com.multimodalAgent.agent.context.memory.ConversationMemoryReader;
import com.multimodalAgent.agent.context.memory.ConversationTurn;
import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentMessageRole;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Reads completed P6 runs and reconstructs only each run's original request contribution. */
@Component
public class JpaCompletedRunConversationMemoryReader implements ConversationMemoryReader {

    private final AgentRunRepository runRepository;
    private final AgentContextSnapshotStore snapshotStore;

    public JpaCompletedRunConversationMemoryReader(
            AgentRunRepository runRepository,
            AgentContextSnapshotStore snapshotStore
    ) {
        this.runRepository = Objects.requireNonNull(
                runRepository,
                "runRepository must not be null"
        );
        this.snapshotStore = Objects.requireNonNull(
                snapshotStore,
                "snapshotStore must not be null"
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationTurn> read(ConversationMemoryQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        List<AgentRunEntity> candidates = runRepository.findConversationMemoryCandidates(
                query.userId(),
                query.sessionId(),
                query.currentRunId(),
                AgentRunStatus.COMPLETED,
                AgentStopReason.COMPLETED,
                PageRequest.of(0, query.limit())
        );
        List<ConversationTurn> turns = new ArrayList<>();
        for (AgentRunEntity run : candidates) {
            validateRunIdentity(query, run);
            AgentContextSnapshot snapshot = snapshotStore.findById(run.getContextSnapshotId())
                    .orElseThrow(() -> new ContextAssemblyException(
                            "Historical context snapshot is missing"
                    ));
            validateSnapshotIdentity(run, snapshot);
            extractOriginalUserMessage(snapshot).ifPresent(message -> turns.add(
                    new ConversationTurn(
                            run.getRunId(),
                            run.getSessionId(),
                            run.getUserId(),
                            message.content(),
                            run.getFinalContent(),
                            run.getCompletedAt()
                    )
            ));
        }
        return List.copyOf(turns);
    }

    private void validateRunIdentity(ConversationMemoryQuery query, AgentRunEntity run) {
        if (!query.userId().equals(run.getUserId())
                || !query.sessionId().equals(run.getSessionId())
                || query.currentRunId().equals(run.getRunId())
                || run.getStatus() != AgentRunStatus.COMPLETED
                || run.getStopReason() != AgentStopReason.COMPLETED
                || run.getCompletedAt() == null
                || run.getFinalContent() == null
                || run.getContextSnapshotId() == null) {
            throw new ContextAssemblyException("Historical AgentRun failed memory visibility checks");
        }
    }

    private void validateSnapshotIdentity(AgentRunEntity run, AgentContextSnapshot snapshot) {
        if (!run.getRunId().equals(snapshot.runId())
                || !run.getSessionId().equals(snapshot.sessionId())
                || !run.getUserId().equals(snapshot.userId())) {
            throw new ContextAssemblyException("Historical context snapshot identity mismatch");
        }
    }

    /** Ambiguous legacy request contributions are skipped; verified corruption is never hidden. */
    private Optional<AgentMessage> extractOriginalUserMessage(AgentContextSnapshot snapshot) {
        int offset = 0;
        AgentMessage request = null;
        int matches = 0;
        for (ContextProvenance provenance : snapshot.orderedContributions()) {
            int end;
            try {
                end = Math.addExact(offset, provenance.messageCount());
            } catch (ArithmeticException exception) {
                throw new ContextAssemblyException("Historical context provenance is invalid", exception);
            }
            if (end > snapshot.messages().size()) {
                throw new ContextAssemblyException("Historical context provenance is invalid");
            }
            if (RequestMessageContextSource.SOURCE_ID.equals(provenance.sourceId())
                    && RequestMessageContextSource.SOURCE_VERSION.equals(
                    provenance.sourceVersion()
            )) {
                matches++;
                if (provenance.messageCount() == 1) {
                    request = snapshot.messages().get(offset);
                }
            }
            offset = end;
        }
        if (offset != snapshot.messages().size()) {
            throw new ContextAssemblyException("Historical context provenance is invalid");
        }
        if (matches != 1 || request == null || request.role() != AgentMessageRole.USER
                || !request.toolCalls().isEmpty()
                || request.toolCallId() != null
                || request.toolName() != null) {
            return Optional.empty();
        }
        return Optional.of(request);
    }
}
