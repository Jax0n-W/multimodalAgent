package com.multimodalAgent.agent.recovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One startup pass; P7 remains the only cross-node ownership mechanism. */
public final class RecoveryScanner {
    private final RecoveryCandidateStore candidates;
    private final RecoveryEngine engine;

    public RecoveryScanner(RecoveryCandidateStore candidates, RecoveryEngine engine) {
        this.candidates = Objects.requireNonNull(candidates, "candidates must not be null");
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    public List<RecoveryEngineResult> scan() {
        List<RecoveryEngineResult> results = new ArrayList<>();
        for (RecoveryCandidate candidate : candidates.findRunning()) {
            results.add(engine.recover(candidate));
        }
        return List.copyOf(results);
    }
}
