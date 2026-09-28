package com.multimodalAgent.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.ModelPricing;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventMetadata;
import com.multimodalAgent.agent.runtime.event.ModelStartedEvent;
import com.multimodalAgent.agent.runtime.event.ToolRequestedEvent;
import com.multimodalAgent.agent.runtime.event.ToolStartedEvent;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.TokenUsageStatus;
import com.multimodalAgent.agent.runtime.model.gateway.ModelFailureKind;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelInvocationTelemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalHarnessTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final ModelIdentity IDENTITY = new ModelIdentity("ollama", "fixture-model");

    @Test
    void loadsVersionedSyntheticDatasetWithRequiredCoverage() throws Exception {
        EvalSuite suite = new EvalDatasetLoader(new ObjectMapper()).loadDefault();

        assertEquals("p9.4-runtime-synthetic", suite.suiteId());
        assertEquals("1.0.0", suite.suiteVersion());
        assertEquals(36, suite.cases().size());
        assertEquals(36, suite.cases().stream().map(EvalCase::caseId).distinct().count());
        assertEquals(Set.of(
                        "direct_answer",
                        "knowledge_search_needed",
                        "knowledge_search_not_needed",
                        "multi_turn_context",
                        "tool_forbidden",
                        "model_call_budget",
                        "tool_call_budget",
                        "output_limit"
                ), suite.cases().stream().map(EvalCase::category)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void rejectsInconsistentCaseContracts() {
        assertThrows(IllegalArgumentException.class, () -> new EvalCase(
                "bad", "1", "test", List.of(AgentMessage.user("hello")),
                AgentStopReason.COMPLETED, Set.of(), Set.of("knowledge_search"),
                Set.of(), 1, null, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new EvalCase(
                "bad", "1", "test", List.of(AgentMessage.user("hello")),
                AgentStopReason.COMPLETED, Set.of("knowledge_search"),
                Set.of("knowledge_search"), Set.of("knowledge_search"), 1, null, null
        ));
    }

    @Test
    void contractPassRequiresStopToolsAndCallBounds() {
        EvalCase evalCase = evalCase(
                Set.of("knowledge_search"),
                Set.of("knowledge_search"),
                Set.of("unsafe_tool"),
                2L,
                1L
        );
        EvalContractEvaluator evaluator = new EvalContractEvaluator();

        assertTrue(evaluator.evaluate(
                evalCase, AgentStopReason.COMPLETED,
                List.of("knowledge_search"), 2, 1
        ).contractPass());
        EvalContractResult failed = evaluator.evaluate(
                evalCase, AgentStopReason.COMPLETED,
                List.of("knowledge_search", "unsafe_tool"), 3, 2
        );
        assertFalse(failed.contractPass());
        assertEquals(1, failed.forbiddenToolViolations());
        assertFalse(failed.modelCallConstraintMatch());
        assertFalse(failed.toolCallConstraintMatch());
    }

    @Test
    void recordUsesObservedEventsAndKeepsUnknownUsageAndCostUnknown() {
        EvalObservation observation = observation(
                TokenUsage.UNKNOWN,
                List.of(
                        modelStarted(1),
                        toolRequested(2, "knowledge_search"),
                        toolStarted(3, "knowledge_search")
                ),
                List.of(telemetry(TokenUsage.UNKNOWN, ModelFailureKind.TIMEOUT)),
                null
        );

        EvalRecord record = new EvalRecordFactory().create(
                evalCase(Set.of("knowledge_search"), Set.of("knowledge_search"),
                        Set.of(), 2L, 1L),
                observation
        );

        assertEquals(1, record.modelCalls());
        assertEquals(1, record.toolCalls());
        assertEquals(List.of("knowledge_search"), record.toolsUsed());
        assertEquals(TokenUsageStatus.UNKNOWN_OR_INCOMPLETE, record.tokenUsageStatus());
        assertNull(record.inputTokens());
        assertNull(record.outputTokens());
        assertNull(record.totalTokens());
        assertEquals(CostStatus.UNKNOWN, record.costStatus());
        assertNull(record.estimatedCost());
        assertEquals(ModelFailureKind.TIMEOUT, record.modelFailureKind());
    }

    @Test
    void toolSelectionUsesRequestedEventWhileToolCallCountUsesStartedEvent() {
        EvalObservation observation = observation(
                TokenUsage.UNKNOWN,
                List.of(modelStarted(1), toolRequested(2, "unsafe_tool")),
                List.of(telemetry(TokenUsage.UNKNOWN, null)),
                null
        );

        EvalRecord record = new EvalRecordFactory().create(
                evalCase(Set.of(), Set.of(), Set.of("unsafe_tool"), 1L, 0L),
                observation
        );

        assertFalse(record.contractPass());
        assertFalse(record.toolSelectionCorrect());
        assertEquals(1, record.forbiddenToolViolations());
        assertEquals(0, record.toolCalls());
    }

    @Test
    void recordCalculatesCostOnlyFromCompleteUsageAndExplicitMatchingPricing() {
        ModelPricing pricing = new ModelPricing(
                IDENTITY, new BigDecimal("2.00"), new BigDecimal("4.00")
        );
        EvalRecord record = new EvalRecordFactory().create(
                evalCase(Set.of(), Set.of(), Set.of(), 1L, 0L),
                observation(new TokenUsage(1_000_000, 500_000),
                        List.of(modelStarted(1)),
                        List.of(telemetry(new TokenUsage(1_000_000, 500_000), null)),
                        pricing)
        );

        assertEquals(CostStatus.KNOWN, record.costStatus());
        assertEquals(0, new BigDecimal("4.00").compareTo(record.estimatedCost()));
    }

    @Test
    void aggregationUsesNearestRankAndDoesNotPretendPartialUsageIsComplete() {
        EvalMetricsAggregator aggregator = new EvalMetricsAggregator();
        assertEquals(5L, EvalMetricsAggregator.percentileNearestRank(
                List.of(1L, 2L, 3L, 4L, 5L), 0.95
        ));
        EvalSummary summary = aggregator.aggregate(List.of(
                record("one", true, 10, 10L, 5L, 15L, CostStatus.UNKNOWN, null),
                record("two", false, 20, null, null, null, CostStatus.UNKNOWN, null),
                record("three", true, 30, 8L, 4L, 12L, CostStatus.UNKNOWN, null)
        ));

        assertEquals(new BigDecimal("0.666667"), summary.contractPassRate());
        assertEquals(new BigDecimal("0.666667"), summary.usageCompletenessRate());
        assertNull(summary.totalTokens());
        assertNull(summary.estimatedCost());
        assertEquals(20L, summary.p50LatencyMillis());
        assertEquals(30L, summary.p95LatencyMillis());
    }

    @Test
    void aggregationUsesTypedFailureKindDistribution() {
        EvalRecord timedOut = record(
                "timeout", false, 10, null, null, null, CostStatus.UNKNOWN, null
        );
        timedOut = new EvalRecord(
                timedOut.caseId(), timedOut.category(), timedOut.contractPass(),
                timedOut.stopReasonMatch(), timedOut.toolSelectionCorrect(),
                timedOut.forbiddenToolViolations(), timedOut.stopReason(),
                timedOut.toolsUsed(), timedOut.iterations(), timedOut.modelCalls(),
                timedOut.toolCalls(), timedOut.inputTokens(), timedOut.outputTokens(),
                timedOut.totalTokens(), timedOut.tokenUsageStatus(),
                timedOut.estimatedCost(), timedOut.costStatus(), timedOut.latencyMillis(),
                ModelFailureKind.TIMEOUT, timedOut.runtimeConfigSnapshotId()
        );

        EvalSummary summary = new EvalMetricsAggregator().aggregate(List.of(
                timedOut,
                record("success", true, 20, null, null, null, CostStatus.UNKNOWN, null)
        ));

        assertEquals(Map.of("TIMEOUT", 1L), summary.failureKindDistribution());
    }

    @Test
    void comparatorAllowsDifferentProvenanceAndCaseOrderingForSameDataset() {
        EvalRecord firstBaseline = record(
                "first", false, 20, null, null, null, CostStatus.UNKNOWN, null
        );
        EvalRecord secondBaseline = record(
                "second", false, 20, null, null, null, CostStatus.UNKNOWN, null
        );
        EvalRecord firstCurrent = record(
                "first", true, 30, null, null, null, CostStatus.UNKNOWN, null
        );
        EvalRecord secondCurrent = record(
                "second", true, 30, null, null, null, CostStatus.UNKNOWN, null
        );
        EvalRun baseline = run(
                "suite", "1", "old-sha", EvalSourceTreeState.CLEAN,
                "old-snapshot", "ollama/old-model",
                List.of(firstBaseline, secondBaseline)
        );
        EvalRun current = run(
                "suite", "1", "new-sha", EvalSourceTreeState.DIRTY,
                "new-snapshot", "ollama/new-model",
                List.of(secondCurrent, firstCurrent)
        );

        EvalComparison comparison = new EvalBaselineComparator().compare(baseline, current);

        assertEquals(new BigDecimal("1.000000"), comparison.contractPassRateDelta());
        assertEquals(10L, comparison.p95LatencyMillisDelta());
        assertNull(comparison.totalTokensDelta());
        assertNull(comparison.estimatedCostDelta());
    }

    @Test
    void comparatorRejectsDifferentSuiteId() {
        assertIncompatible(
                run("suite-a", "1", List.of(record("case", true, 1,
                        null, null, null, CostStatus.UNKNOWN, null))),
                run("suite-b", "1", List.of(record("case", true, 1,
                        null, null, null, CostStatus.UNKNOWN, null)))
        );
    }

    @Test
    void comparatorRejectsDifferentSuiteVersion() {
        assertIncompatible(
                run("suite", "1", List.of(record("case", true, 1,
                        null, null, null, CostStatus.UNKNOWN, null))),
                run("suite", "2", List.of(record("case", true, 1,
                        null, null, null, CostStatus.UNKNOWN, null)))
        );
    }

    @Test
    void comparatorRejectsDifferentCaseIdSet() {
        assertIncompatible(
                run("suite", "1", List.of(record("case-a", true, 1,
                        null, null, null, CostStatus.UNKNOWN, null))),
                run("suite", "1", List.of(record("case-b", true, 1,
                        null, null, null, CostStatus.UNKNOWN, null)))
        );
    }

    @Test
    void runnerAndWriterIncludeReproducibilityMetadataAndStableArtifacts(
            @TempDir Path tempDir
    ) throws Exception {
        EvalSuite suite = new EvalSuite("suite", "1", List.of(
                evalCase(Set.of(), Set.of(), Set.of(), 1L, 0L)
        ));
        EvalExecutionTarget target = new RuntimeEvalExecutionTarget(
                "runtime",
                ignored -> new RuntimeEvalExecutionTarget.Capture(
                        result(TokenUsage.UNKNOWN),
                        List.of(modelStarted(1)),
                        List.of(telemetry(TokenUsage.UNKNOWN, null)),
                        "exec-config-v1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        null,
                        Duration.ofMillis(7)
                )
        );
        EvalRun run = new EvalRunner(Clock.fixed(NOW, ZoneOffset.UTC))
                .run(suite, target, "abc123", EvalSourceTreeState.CLEAN);
        EvalReportFiles files = new EvalReportWriter(new ObjectMapper()).write(tempDir, run);

        assertTrue(run.metadata().fullyReproducible());
        assertEquals(List.of("ollama/fixture-model"), run.metadata().modelIdentities());
        assertTrue(java.nio.file.Files.exists(files.results()));
        assertTrue(java.nio.file.Files.exists(files.summary()));
        assertTrue(java.nio.file.Files.exists(files.markdown()));
        JsonNode summary = new ObjectMapper().findAndRegisterModules()
                .readTree(java.nio.file.Files.readString(files.summary()));
        assertEquals("abc123", summary.path("metadata").path("gitSha").asText());
        assertEquals("CLEAN", summary.path("metadata").path("sourceTreeState").asText());
        assertEquals("suite", summary.path("metadata").path("suiteId").asText());
        assertEquals(NOW.toString(), summary.path("metadata").path("generatedAt").asText());
        assertTrue(java.nio.file.Files.readString(files.markdown())
                .contains("not semantic answer quality"));
        EvalReportWriter writer = new EvalReportWriter(new ObjectMapper());
        assertEquals(writer.resultsJson(run), writer.resultsJson(run));
        assertEquals(writer.summaryJson(run), writer.summaryJson(run));
    }

    @Test
    void dirtyAndUnknownTreesCannotBeFullyReproducible() {
        EvalSuite suite = singleCaseSuite();
        EvalExecutionTarget target = completeTarget(
                "exec-config-v1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );

        EvalRun dirty = runner().run(suite, target, "abc123", EvalSourceTreeState.DIRTY);
        EvalRun unknown = runner().run(suite, target, "abc123", EvalSourceTreeState.UNKNOWN);

        assertFalse(dirty.metadata().fullyReproducible());
        assertEquals(List.of("sourceTreeState is DIRTY"),
                dirty.metadata().reproducibilityIssues());
        assertFalse(unknown.metadata().fullyReproducible());
        assertEquals(List.of("sourceTreeState is UNKNOWN"),
                unknown.metadata().reproducibilityIssues());
    }

    @Test
    void missingGitShaCannotBeFullyReproducibleEvenForCleanTree() {
        EvalRun run = runner().run(
                singleCaseSuite(),
                completeTarget("exec-config-v1-a"),
                null,
                EvalSourceTreeState.CLEAN
        );

        assertFalse(run.metadata().fullyReproducible());
        assertEquals(List.of("gitSha is missing"), run.metadata().reproducibilityIssues());
    }

    @Test
    void missingSnapshotCannotBeFullyReproducibleEvenForCleanTree() {
        EvalRun run = runner().run(
                singleCaseSuite(), completeTarget(null), "abc123", EvalSourceTreeState.CLEAN
        );

        assertFalse(run.metadata().fullyReproducible());
        assertEquals(List.of("one or more runtimeConfigSnapshotIds are missing"),
                run.metadata().reproducibilityIssues());
    }

    @Test
    void dirtyTreeSerializationIsExplicitAndDeterministic() throws Exception {
        EvalRun run = runner().run(
                singleCaseSuite(), completeTarget("exec-config-v1-a"),
                "abc123", EvalSourceTreeState.DIRTY
        );
        EvalReportWriter writer = new EvalReportWriter(new ObjectMapper());
        String first = writer.summaryJson(run);
        String second = writer.summaryJson(run);
        JsonNode json = new ObjectMapper().findAndRegisterModules().readTree(first);

        assertEquals(first, second);
        assertEquals("DIRTY", json.path("metadata").path("sourceTreeState").asText());
        assertFalse(json.path("metadata").path("fullyReproducible").asBoolean());
    }

    @Test
    void defaultMavenConfigurationExcludesAllRealProviderTests() throws Exception {
        String pom = java.nio.file.Files.readString(Path.of("pom.xml"));

        assertTrue(pom.contains(
                "<surefire.excludedGroups>real-model,eval-real</surefire.excludedGroups>"
        ));
        assertTrue(pom.contains("<id>eval-real</id>"));
        assertTrue(pom.contains("<surefire.excludedGroups>real-model</surefire.excludedGroups>"));
    }

    @Test
    void runnerMarksMissingReproducibilityFactsInsteadOfInventingThem() {
        EvalSuite suite = new EvalSuite("suite", "1", List.of(
                evalCase(Set.of(), Set.of(), Set.of(), 1L, 0L)
        ));
        EvalExecutionTarget target = new RuntimeEvalExecutionTarget(
                "runtime",
                ignored -> new RuntimeEvalExecutionTarget.Capture(
                        result(TokenUsage.UNKNOWN), List.of(), List.of(), null,
                        null, Duration.ZERO
                )
        );

        EvalRun run = new EvalRunner(Clock.fixed(NOW, ZoneOffset.UTC))
                .run(suite, target, null, EvalSourceTreeState.UNKNOWN);

        assertFalse(run.metadata().fullyReproducible());
        assertEquals(4, run.metadata().reproducibilityIssues().size());
    }

    private EvalCase evalCase(
            Set<String> allowed,
            Set<String> expected,
            Set<String> forbidden,
            Long maxModelCalls,
            Long maxToolCalls
    ) {
        return new EvalCase(
                "case", "1", "fixture", List.of(AgentMessage.user("fixture")),
                AgentStopReason.COMPLETED, allowed, expected, forbidden, 3,
                maxModelCalls, maxToolCalls
        );
    }

    private EvalObservation observation(
            TokenUsage usage,
            List<AgentEvent> events,
            List<ModelInvocationTelemetry> telemetry,
            ModelPricing pricing
    ) {
        return new EvalObservation(
                result(usage), events, telemetry,
                "exec-config-v1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                pricing, Duration.ofMillis(12)
        );
    }

    private AgentRunResult result(TokenUsage usage) {
        return new AgentRunResult(
                "done", AgentStopReason.COMPLETED, 1, List.of("knowledge_search"),
                List.of(AgentMessage.assistant("done")), usage, null, null, null
        );
    }

    private ModelInvocationTelemetry telemetry(
            TokenUsage usage,
            ModelFailureKind failureKind
    ) {
        return new ModelInvocationTelemetry(
                "invocation", IDENTITY, 1, Duration.ofMillis(5),
                failureKind == null ? ModelFinishReason.STOP : null,
                usage, failureKind
        );
    }

    private ModelStartedEvent modelStarted(long sequence) {
        return new ModelStartedEvent(metadata(sequence, 1));
    }

    private ToolStartedEvent toolStarted(long sequence, String name) {
        return new ToolStartedEvent(metadata(sequence, 1), "call-" + sequence, name);
    }

    private ToolRequestedEvent toolRequested(long sequence, String name) {
        return new ToolRequestedEvent(metadata(sequence, 1), "call-" + sequence, name);
    }

    private AgentEventMetadata metadata(long sequence, int iteration) {
        return new AgentEventMetadata(
                "event-" + sequence, "run", sequence, NOW, iteration
        );
    }

    private EvalRecord record(
            String id,
            boolean pass,
            long latency,
            Long input,
            Long output,
            Long total,
            CostStatus costStatus,
            BigDecimal cost
    ) {
        return new EvalRecord(
                id, "fixture", pass, pass, pass, 0,
                AgentStopReason.COMPLETED, List.of(), 1, 1, 0,
                input, output, total,
                input == null
                        ? TokenUsageStatus.UNKNOWN_OR_INCOMPLETE
                        : TokenUsageStatus.KNOWN,
                cost, costStatus, latency, null, null
        );
    }

    private EvalRunner runner() {
        return new EvalRunner(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private EvalSuite singleCaseSuite() {
        return new EvalSuite("suite", "1", List.of(
                evalCase(Set.of(), Set.of(), Set.of(), 1L, 0L)
        ));
    }

    private EvalExecutionTarget completeTarget(String snapshotId) {
        return new RuntimeEvalExecutionTarget(
                "runtime",
                ignored -> new RuntimeEvalExecutionTarget.Capture(
                        result(TokenUsage.UNKNOWN),
                        List.of(modelStarted(1)),
                        List.of(telemetry(TokenUsage.UNKNOWN, null)),
                        snapshotId,
                        null,
                        Duration.ofMillis(7)
                )
        );
    }

    private EvalRun run(String suiteId, String suiteVersion, List<EvalRecord> records) {
        return run(
                suiteId, suiteVersion, "sha", EvalSourceTreeState.CLEAN,
                "snapshot", "ollama/model", records
        );
    }

    private EvalRun run(
            String suiteId,
            String suiteVersion,
            String gitSha,
            EvalSourceTreeState sourceTreeState,
            String snapshot,
            String model,
            List<EvalRecord> records
    ) {
        EvalRunMetadata metadata = new EvalRunMetadata(
                gitSha,
                sourceTreeState,
                suiteId,
                suiteVersion,
                NOW,
                "runtime",
                List.of(snapshot),
                List.of(model),
                sourceTreeState == EvalSourceTreeState.CLEAN,
                sourceTreeState == EvalSourceTreeState.CLEAN
                        ? List.of()
                        : List.of("sourceTreeState is " + sourceTreeState)
        );
        return new EvalRun(
                metadata,
                records,
                new EvalMetricsAggregator().aggregate(records)
        );
    }

    private void assertIncompatible(EvalRun baseline, EvalRun current) {
        IncompatibleEvalBaselineException exception = assertThrows(
                IncompatibleEvalBaselineException.class,
                () -> new EvalBaselineComparator().compare(baseline, current)
        );
        assertEquals(IncompatibleEvalBaselineException.CODE, exception.code());
    }
}
