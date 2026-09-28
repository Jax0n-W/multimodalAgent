package com.multimodalAgent.agent.eval;

import java.util.List;
import java.util.Objects;

public record EvalRun(
        EvalRunMetadata metadata,
        List<EvalRecord> records,
        EvalSummary summary
) {

    public EvalRun {
        Objects.requireNonNull(metadata, "metadata must not be null");
        records = List.copyOf(Objects.requireNonNull(records, "records must not be null"));
        Objects.requireNonNull(summary, "summary must not be null");
    }
}
