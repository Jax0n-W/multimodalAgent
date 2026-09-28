package com.multimodalAgent.agent.eval;

import java.util.Objects;

/** Snapshot identity plus evidence source; only durable AgentRun evidence is formal provenance. */
public record EvalSnapshotObservation(
        String snapshotId,
        EvalSnapshotProvenance provenance
) {

    public EvalSnapshotObservation {
        Objects.requireNonNull(provenance, "provenance must not be null");
        if (provenance == EvalSnapshotProvenance.MISSING) {
            if (snapshotId != null) {
                throw new IllegalArgumentException("missing snapshot must not have an identity");
            }
        } else if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("observed snapshotId must not be blank");
        }
    }

    public static EvalSnapshotObservation durableAgentRun(String snapshotId) {
        return new EvalSnapshotObservation(
                snapshotId, EvalSnapshotProvenance.DURABLE_AGENT_RUN
        );
    }

    public static EvalSnapshotObservation nonDurable(String snapshotId) {
        return new EvalSnapshotObservation(snapshotId, EvalSnapshotProvenance.NON_DURABLE);
    }

    public static EvalSnapshotObservation missing() {
        return new EvalSnapshotObservation(null, EvalSnapshotProvenance.MISSING);
    }

    public boolean isDurable() {
        return provenance == EvalSnapshotProvenance.DURABLE_AGENT_RUN;
    }
}
