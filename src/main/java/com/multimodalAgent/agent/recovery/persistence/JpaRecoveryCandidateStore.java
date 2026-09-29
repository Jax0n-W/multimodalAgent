package com.multimodalAgent.agent.recovery.persistence;

import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.recovery.RecoveryCandidate;
import com.multimodalAgent.agent.recovery.RecoveryCandidateStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Component
public class JpaRecoveryCandidateStore implements RecoveryCandidateStore {
    private final AgentRunRepository repository;

    public JpaRecoveryCandidateStore(AgentRunRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecoveryCandidate> findRunning() {
        return repository.findByStatusOrderByCreatedAtAsc(AgentRunStatus.RUNNING).stream()
                .map(run -> new RecoveryCandidate(run.getRunId(), run.getSessionId()))
                .toList();
    }
}
