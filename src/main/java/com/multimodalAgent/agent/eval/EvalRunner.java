package com.multimodalAgent.agent.eval;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Runs cases serially against one existing execution target and aggregates observed facts. */
public final class EvalRunner {

    private final EvalRecordFactory recordFactory;
    private final EvalMetricsAggregator metricsAggregator;
    private final Clock clock;

    public EvalRunner(Clock clock) {
        this(new EvalRecordFactory(), new EvalMetricsAggregator(), clock);
    }

    EvalRunner(
            EvalRecordFactory recordFactory,
            EvalMetricsAggregator metricsAggregator,
            Clock clock
    ) {
        this.recordFactory = Objects.requireNonNull(recordFactory, "recordFactory must not be null");
        this.metricsAggregator = Objects.requireNonNull(
                metricsAggregator, "metricsAggregator must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public EvalRun run(EvalSuite suite, EvalExecutionTarget target, String gitSha) {
        return run(suite, target, gitSha, EvalSourceTreeState.UNKNOWN);
    }

    public EvalRun run(
            EvalSuite suite,
            EvalExecutionTarget target,
            String gitSha,
            EvalSourceTreeState sourceTreeState
    ) {
        Objects.requireNonNull(suite, "suite must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(sourceTreeState, "sourceTreeState must not be null");
        ArrayList<EvalRecord> records = new ArrayList<>();
        TreeSet<String> snapshots = new TreeSet<>();
        TreeSet<String> modelIdentities = new TreeSet<>();
        boolean snapshotMissing = false;
        for (EvalCase evalCase : suite.cases()) {
            EvalObservation observation = target.execute(evalCase);
            records.add(recordFactory.create(evalCase, observation));
            if (observation.runtimeConfigSnapshotId() == null) {
                snapshotMissing = true;
            } else {
                snapshots.add(observation.runtimeConfigSnapshotId());
            }
            observation.modelTelemetry().forEach(telemetry -> modelIdentities.add(
                    telemetry.identity().provider() + "/" + telemetry.identity().model()
            ));
        }
        ArrayList<String> issues = new ArrayList<>();
        if (gitSha == null || gitSha.isBlank()) {
            issues.add("gitSha is missing");
        }
        if (sourceTreeState != EvalSourceTreeState.CLEAN) {
            issues.add("sourceTreeState is " + sourceTreeState);
        }
        if (snapshotMissing || snapshots.isEmpty()) {
            issues.add("one or more runtimeConfigSnapshotIds are missing");
        }
        if (modelIdentities.isEmpty()) {
            issues.add("model identity is missing");
        }
        EvalRunMetadata metadata = new EvalRunMetadata(
                normalize(gitSha),
                sourceTreeState,
                suite.suiteId(),
                suite.suiteVersion(),
                clock.instant(),
                target.targetName(),
                List.copyOf(snapshots),
                List.copyOf(modelIdentities),
                issues.isEmpty(),
                issues
        );
        List<EvalRecord> immutableRecords = List.copyOf(records);
        return new EvalRun(
                metadata,
                immutableRecords,
                metricsAggregator.aggregate(immutableRecords)
        );
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
