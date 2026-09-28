package com.multimodalAgent.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.BudgetBlock;
import com.multimodalAgent.agent.runtime.budget.BudgetBlockReason;
import com.multimodalAgent.agent.runtime.budget.BudgetDimension;
import com.multimodalAgent.agent.runtime.budget.ModelPricing;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventMetadata;
import com.multimodalAgent.agent.runtime.event.BudgetBlockedEvent;
import com.multimodalAgent.agent.runtime.event.ModelStartedEvent;
import com.multimodalAgent.agent.runtime.event.ToolFailedEvent;
import com.multimodalAgent.agent.runtime.event.ToolRequestedEvent;
import com.multimodalAgent.agent.runtime.event.ToolStartedEvent;
import com.multimodalAgent.agent.runtime.event.ToolSucceededEvent;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.TokenUsageStatus;
import com.multimodalAgent.agent.runtime.model.gateway.ModelFailureKind;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelInvocationTelemetry;
import com.multimodalAgent.agent.runtime.tool.ToolErrorCode;
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
import java.util.ArrayList;

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

        assertEquals("p9.4-runtime-contract-suite", suite.suiteId());
        assertEquals("1.1.0", suite.suiteVersion());
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
    void realModelSuiteExcludesDeterministicBudgetAndOutputLimitContracts() throws Exception {
        EvalSuite suite = new EvalDatasetLoader(new ObjectMapper()).loadRealModel();

        assertEquals("p9.4-real-model-baseline-suite", suite.suiteId());
        assertEquals("1.1.0", suite.suiteVersion());
        assertEquals(30, suite.cases().size());
        assertTrue(suite.cases().stream().noneMatch(evalCase -> Set.of(
                "model_call_budget", "tool_call_budget", "output_limit"
        ).contains(evalCase.category())));
    }

    @Test
    void rejectsInconsistentCaseContracts() {
        assertThrows(IllegalArgumentException.class, () -> new EvalCase(
                "bad", "1", "test", List.of(AgentMessage.user("hello")),
                new EvalExecutionConfig(1, Set.of(), EvalExecutionBudget.UNLIMITED),
                new EvalOracle(AgentStopReason.COMPLETED, Set.of("knowledge_search"),
                        Set.of(), null, null)
        ));
        assertThrows(IllegalArgumentException.class, () -> new EvalCase(
                "bad", "1", "test", List.of(AgentMessage.user("hello")),
                new EvalExecutionConfig(1, Set.of("knowledge_search"),
                        EvalExecutionBudget.UNLIMITED),
                new EvalOracle(AgentStopReason.COMPLETED, Set.of("knowledge_search"),
                        Set.of("knowledge_search"), null, null)
        ));
    }

    @Test
    void executionBudgetAndOracleConstraintAreIndependent() {
        EvalCase evalCase = new EvalCase(
                "independent", "1", "fixture", List.of(AgentMessage.user("fixture")),
                new EvalExecutionConfig(3, Set.of(), new EvalExecutionBudget(1L, 0L)),
                new EvalOracle(AgentStopReason.COMPLETED, Set.of(), Set.of(), 2L, 1L)
        );

        assertEquals(1L, evalCase.execution().budget().maxModelCalls());
        assertEquals(2L, evalCase.oracle().maxExpectedModelCalls());
        assertEquals(0L, evalCase.execution().budget().maxToolCalls());
        assertEquals(1L, evalCase.oracle().maxExpectedToolCalls());
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
        assertEquals(1, record.toolRequests());
        assertEquals(1, record.toolCalls());
        assertEquals(List.of("knowledge_search"), record.requestedTools());
        assertEquals(List.of("knowledge_search"), record.startedTools());
        assertEquals(TokenUsageStatus.UNKNOWN_OR_INCOMPLETE, record.tokenUsageStatus());
        assertNull(record.inputTokens());
        assertNull(record.outputTokens());
        assertNull(record.totalTokens());
        assertEquals(CostStatus.UNKNOWN, record.costStatus());
        assertNull(record.estimatedCost());
        assertEquals(ModelFailureKind.TIMEOUT, record.modelFailureKind());
    }

    @Test
    void modelCallCountUsesOnlyModelStartedEvents() {
        EvalRecord record = new EvalRecordFactory().create(
                evalCase(Set.of(), Set.of(), Set.of(), 0L, 0L),
                observation(TokenUsage.UNKNOWN, List.of(),
                        List.of(telemetry(TokenUsage.UNKNOWN, null)), null)
        );

        assertEquals(0, record.modelCalls());
    }

    @Test
    void toolLifecycleFactsRemainDistinct() {
        EvalRecord requestedOnly = new EvalRecordFactory().create(
                evalCase(Set.of("knowledge_search"), Set.of("knowledge_search"),
                        Set.of(), 1L, 0L),
                observation(TokenUsage.UNKNOWN,
                        List.of(modelStarted(1), toolRequested(2, "knowledge_search")),
                        List.of(), null)
        );
        EvalRecord succeeded = new EvalRecordFactory().create(
                evalCase(Set.of("knowledge_search"), Set.of("knowledge_search"),
                        Set.of(), 1L, 1L),
                observation(TokenUsage.UNKNOWN, List.of(
                        modelStarted(1), toolRequested(2, "knowledge_search"),
                        toolStarted(3, "knowledge_search"),
                        toolSucceeded(4, "knowledge_search")
                ), List.of(), null)
        );
        EvalRecord failed = new EvalRecordFactory().create(
                evalCase(Set.of("knowledge_search"), Set.of("knowledge_search"),
                        Set.of(), 1L, 1L),
                observation(TokenUsage.UNKNOWN, List.of(
                        modelStarted(1), toolRequested(2, "knowledge_search"),
                        toolStarted(3, "knowledge_search"),
                        toolFailed(4, "knowledge_search")
                ), List.of(), null)
        );

        assertEquals(1, requestedOnly.toolRequests());
        assertEquals(0, requestedOnly.toolCalls());
        assertEquals(List.of("knowledge_search"), requestedOnly.requestedTools());
        assertTrue(requestedOnly.startedTools().isEmpty());
        assertEquals(List.of("knowledge_search"), succeeded.succeededTools());
        assertTrue(succeeded.failedTools().isEmpty());
        assertEquals(List.of("knowledge_search"), failed.failedTools());
        assertTrue(failed.succeededTools().isEmpty());
        EvalSummary summary = new EvalMetricsAggregator().aggregate(
                List.of(requestedOnly, succeeded, failed)
        );
        assertEquals(new BigDecimal("1.000000"), summary.avgToolRequests());
        assertEquals(new BigDecimal("0.666667"), summary.avgToolCalls());
    }

    @Test
    void requestedButBudgetBlockedToolCountsAsRequestAndNotAsCall() {
        EvalObservation observation = observation(
                TokenUsage.UNKNOWN,
                List.of(
                        modelStarted(1),
                        toolRequested(2, "unsafe_tool"),
                        BudgetBlockedEvent.forTool(
                                metadata(3, 1),
                                new BudgetBlock(
                                        BudgetDimension.TOOL_CALLS,
                                        BudgetBlockReason.EXHAUSTED,
                                        BigDecimal.ZERO,
                                        java.util.Optional.of(BigDecimal.ZERO)
                                ),
                                "call-2",
                                "unsafe_tool"
                        )
                ),
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
        assertEquals(1, record.toolRequests());
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
    void recordKeepsCostUnknownWhenObservedIdentityDoesNotMatchPricing() {
        ModelPricing pricing = new ModelPricing(
                IDENTITY, new BigDecimal("2.00"), new BigDecimal("4.00")
        );
        EvalRecord record = new EvalRecordFactory().create(
                evalCase(Set.of(), Set.of(), Set.of(), 1L, 0L),
                observation(new TokenUsage(1_000_000, 500_000),
                        List.of(modelStarted(1)),
                        List.of(telemetry(
                                new ModelIdentity("ollama", "different-model"),
                                new TokenUsage(1_000_000, 500_000),
                                null
                        )),
                        pricing)
        );

        assertEquals(CostStatus.UNKNOWN, record.costStatus());
        assertNull(record.estimatedCost());
    }

    @Test
    void recordKeepsCostUnknownWhenObservedIdentityIsMissingOrAmbiguous() {
        ModelPricing pricing = new ModelPricing(
                IDENTITY, new BigDecimal("2.00"), new BigDecimal("4.00")
        );
        TokenUsage usage = new TokenUsage(1_000_000, 500_000);
        EvalCase evalCase = evalCase(Set.of(), Set.of(), Set.of(), 2L, 0L);

        EvalRecord missing = new EvalRecordFactory().create(
                evalCase,
                observation(usage, List.of(modelStarted(1)), List.of(), pricing)
        );
        EvalRecord ambiguous = new EvalRecordFactory().create(
                evalCase,
                observation(usage, List.of(modelStarted(1), modelStarted(2)), List.of(
                        telemetry(IDENTITY, usage, null),
                        telemetry(new ModelIdentity("ollama", "different-model"), usage, null)
                ), pricing)
        );

        assertEquals(CostStatus.UNKNOWN, missing.costStatus());
        assertNull(missing.estimatedCost());
        assertEquals(CostStatus.UNKNOWN, ambiguous.costStatus());
        assertNull(ambiguous.estimatedCost());
    }

    @Test
    void recordKeepsCostUnknownWhenMatchingIdentityHasIncompleteUsage() {
        ModelPricing pricing = new ModelPricing(
                IDENTITY, new BigDecimal("2.00"), new BigDecimal("4.00")
        );
        EvalRecord record = new EvalRecordFactory().create(
                evalCase(Set.of(), Set.of(), Set.of(), 1L, 0L),
                observation(TokenUsage.UNKNOWN, List.of(modelStarted(1)),
                        List.of(telemetry(TokenUsage.UNKNOWN, null)), pricing)
        );

        assertEquals(CostStatus.UNKNOWN, record.costStatus());
        assertNull(record.estimatedCost());
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
                timedOut.toolsUsed(), timedOut.toolRequests(), timedOut.requestedTools(),
                timedOut.startedTools(), timedOut.succeededTools(), timedOut.failedTools(),
                timedOut.iterations(), timedOut.modelCalls(), timedOut.toolCalls(),
                timedOut.inputTokens(), timedOut.outputTokens(),
                timedOut.totalTokens(), timedOut.tokenUsageStatus(),
                timedOut.estimatedCost(), timedOut.costStatus(), timedOut.latencyMillis(),
                ModelFailureKind.TIMEOUT, timedOut.runtimeConfigSnapshotId(),
                timedOut.runtimeConfigSnapshotProvenance()
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
                        EvalSnapshotObservation.durableAgentRun(
                                "exec-config-v1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        ),
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
    void requestInterceptedSnapshotCannotBeFullyReproducible() {
        EvalExecutionTarget target = new RuntimeEvalExecutionTarget(
                "runtime",
                ignored -> new RuntimeEvalExecutionTarget.Capture(
                        result(TokenUsage.UNKNOWN), List.of(modelStarted(1)),
                        List.of(telemetry(TokenUsage.UNKNOWN, null)),
                        EvalSnapshotObservation.nonDurable("exec-config-v1-intercepted"),
                        null, Duration.ZERO
                )
        );

        EvalRun run = runner().run(
                singleCaseSuite(), target, "abc123", EvalSourceTreeState.CLEAN
        );

        assertFalse(run.metadata().fullyReproducible());
        assertTrue(run.metadata().runtimeConfigSnapshotIds().isEmpty());
        assertEquals(EvalSnapshotProvenance.NON_DURABLE,
                run.records().get(0).runtimeConfigSnapshotProvenance());
    }

    @Test
    void runnerExecutesCasesSequentiallyInDatasetOrder() {
        List<String> observed = new ArrayList<>();
        EvalSuite suite = new EvalSuite("suite", "1", List.of(
                evalCase("first"), evalCase("second"), evalCase("third")
        ));
        EvalExecutionTarget target = new RuntimeEvalExecutionTarget(
                "runtime",
                evalCase -> {
                    observed.add(evalCase.caseId());
                    return new RuntimeEvalExecutionTarget.Capture(
                            result(TokenUsage.UNKNOWN), List.of(modelStarted(1)),
                            List.of(telemetry(TokenUsage.UNKNOWN, null)),
                            EvalSnapshotObservation.durableAgentRun("snapshot-" + evalCase.caseId()),
                            null, Duration.ZERO
                    );
                }
        );

        runner().run(suite, target, "abc123", EvalSourceTreeState.CLEAN);

        assertEquals(List.of("first", "second", "third"), observed);
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
                        result(TokenUsage.UNKNOWN), List.of(), List.of(),
                        EvalSnapshotObservation.missing(),
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
                new EvalExecutionConfig(3, allowed,
                        new EvalExecutionBudget(maxModelCalls, maxToolCalls)),
                new EvalOracle(AgentStopReason.COMPLETED, expected, forbidden,
                        maxModelCalls, maxToolCalls)
        );
    }

    private EvalCase evalCase(String caseId) {
        return new EvalCase(
                caseId, "1", "fixture", List.of(AgentMessage.user("fixture")),
                new EvalExecutionConfig(3, Set.of(), EvalExecutionBudget.UNLIMITED),
                new EvalOracle(AgentStopReason.COMPLETED, Set.of(), Set.of(), 1L, 0L)
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
                EvalSnapshotObservation.durableAgentRun(
                        "exec-config-v1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                ),
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
        return telemetry(IDENTITY, usage, failureKind);
    }

    private ModelInvocationTelemetry telemetry(
            ModelIdentity identity,
            TokenUsage usage,
            ModelFailureKind failureKind
    ) {
        return new ModelInvocationTelemetry(
                "invocation-" + identity.model(), identity, 1, Duration.ofMillis(5),
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

    private ToolSucceededEvent toolSucceeded(long sequence, String name) {
        return new ToolSucceededEvent(metadata(sequence, 1), "call-3", name);
    }

    private ToolFailedEvent toolFailed(long sequence, String name) {
        return new ToolFailedEvent(
                metadata(sequence, 1), "call-3", name, ToolErrorCode.EXECUTION_FAILED
        );
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
                AgentStopReason.COMPLETED, List.of(), 0,
                List.of(), List.of(), List.of(), List.of(), 1, 1, 0,
                input, output, total,
                input == null
                        ? TokenUsageStatus.UNKNOWN_OR_INCOMPLETE
                        : TokenUsageStatus.KNOWN,
                cost, costStatus, latency, null, null, EvalSnapshotProvenance.MISSING
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
                        snapshotId == null
                                ? EvalSnapshotObservation.missing()
                                : EvalSnapshotObservation.durableAgentRun(snapshotId),
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
