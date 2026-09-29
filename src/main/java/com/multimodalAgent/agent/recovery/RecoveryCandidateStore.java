package com.multimodalAgent.agent.recovery;

import java.util.List;

/** Startup discovery port. Implementations return RUNNING runs only. */
public interface RecoveryCandidateStore {
    List<RecoveryCandidate> findRunning();
}
