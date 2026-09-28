# P9.4 Eval Harness

The dataset is split into two versioned suites:

- `src/main/resources/eval/p9.4-runtime-contract-suite.json` is the deterministic contract suite. It includes scripted budget and output-limit edge cases.
- `src/main/resources/eval/p9.4-real-model-baseline-suite.json` selects the cases suitable for the opt-in Ollama baseline. It excludes mandatory output-limit and deterministic budget cases.

Both suites use schema version `1.1.0`. Each case separates `execution` configuration from `oracle` constraints; execution budgets control Runtime and maximum expected calls evaluate observations. `contractPass` is not an answer-quality or task-success metric.

Measurement semantics are fixed:

- `modelCalls = count(MODEL_STARTED)`; telemetry is never a lifecycle-count fallback.
- `toolRequests = count(TOOL_REQUESTED)`.
- `toolCalls = count(TOOL_STARTED)`.
- requested, started, succeeded, and failed tool lists come from their matching lifecycle events.
- expected and forbidden tools are evaluated against requested tools.
- `toolsUsed` is retained only as a compatibility/convenience field.

EvalRunner V1 is strictly sequential: each case finishes and its case-scoped facts are collected before the next case starts. Deterministic scripted coverage maps `ModelFinishReason.LENGTH` to `MODEL_OUTPUT_LIMIT`; the real-model suite does not require Ollama to produce `LENGTH` reliably.

Default verification is deterministic and performs no provider calls:

```shell
mvn clean test
```

Run the local Ollama baseline explicitly:

```shell
mvn -Peval-real -Dtest=RealRuntimeEvalBaselineTest -Deval.gitSha=<commit> -Deval.sourceTreeState=DIRTY test
```

The opt-in test requires an already-running Ollama instance and the model `mindbridge-qwen2.5-7b-ft:latest` (override with `OLLAMA_BASE_URL` and `OLLAMA_MODEL`). If the preflight fails, JUnit skips the run and no baseline numbers are invented.

Artifacts are written to `target/eval/`:

- `eval-results.json`: metadata and per-case records
- `eval-summary.json`: metadata and aggregate metrics
- `eval-report.md`: human-readable summary and reproducibility warnings

Cost remains unknown unless token usage is complete, case-scoped telemetry observes exactly one distinct model identity, and explicit pricing matches that observed identity. Pricing metadata cannot stand in for missing or ambiguous Runtime identity evidence. Baseline comparison reports deltas only; no regression threshold is defined in P9.4.

## Source provenance

A Git SHA identifies committed source only. It cannot identify additional modifications in a dirty working tree. The runner therefore accepts `eval.sourceTreeState` explicitly (`CLEAN`, `DIRTY`, or `UNKNOWN`) and does not call Git, fingerprint the source tree, or infer cleanliness from the presence of a SHA.

`fullyReproducible=true` requires all of the following:

- a non-blank Git SHA;
- `sourceTreeState=CLEAN`;
- suite ID and suite version;
- complete, non-empty durable runtime config snapshot identities read from persisted AgentRun rows;
- model identity.

A dirty-tree run must use `-Deval.sourceTreeState=DIRTY`; it remains a useful development result in `target/eval/`, but is not a formal reproducible baseline.

For a formal real baseline, execution persists the snapshot, performs P6 durable AgentRun admission, runs the existing Runtime, and then reads `agent_runs.runtime_config_snapshot_id` into the Eval observation. A request-intercepted or otherwise non-durable snapshot ID cannot make a run fully reproducible.

## Comparator compatibility

The comparator computes deltas only when suite ID, suite version, and the case-ID set are identical. Case ordering is irrelevant. Dataset mismatch produces `INCOMPATIBLE_BASELINE`. Model identity, runtime configuration snapshot, Git revision, latency, tokens, and cost may differ because measuring those changes is the purpose of comparison.

## Formal baseline workflow

1. Complete P9.4 review.
2. Commit P9.4 manually.
3. Confirm the working tree is clean.
4. Obtain the new commit SHA.
5. Run `mvn -Peval-real -Dtest=RealRuntimeEvalBaselineTest -Deval.gitSha=<P9.4-commit> -Deval.sourceTreeState=CLEAN test`.
6. Verify the reported runtime config snapshot identities.
7. Confirm `fullyReproducible=true`.
8. Only after human review, copy the generated artifacts to `docs/eval/baselines/` if a durable baseline is wanted.
