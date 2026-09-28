package com.multimodalAgent.agent.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Writes deterministic JSON structures and a human-readable summary. */
public final class EvalReportWriter {

    private final ObjectMapper objectMapper;

    public EvalReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null")
                .copy()
                .findAndRegisterModules()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public EvalReportFiles write(Path outputDirectory, EvalRun run) throws IOException {
        Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
        Objects.requireNonNull(run, "run must not be null");
        Files.createDirectories(outputDirectory);
        Path results = outputDirectory.resolve("eval-results.json");
        Path summary = outputDirectory.resolve("eval-summary.json");
        Path markdown = outputDirectory.resolve("eval-report.md");
        Files.writeString(
                results,
                resultsJson(run) + "\n",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                summary,
                summaryJson(run) + "\n",
                StandardCharsets.UTF_8
        );
        Files.writeString(markdown, markdown(run), StandardCharsets.UTF_8);
        return new EvalReportFiles(results, summary, markdown);
    }

    public String resultsJson(EvalRun run) throws JsonProcessingException {
        return objectMapper.writeValueAsString(new ResultsDocument(
                run.metadata(), run.records()
        ));
    }

    public String summaryJson(EvalRun run) throws JsonProcessingException {
        return objectMapper.writeValueAsString(new SummaryDocument(
                run.metadata(), run.summary()
        ));
    }

    public String markdown(EvalRun run) {
        EvalSummary summary = run.summary();
        StringBuilder report = new StringBuilder();
        report.append("# Eval Report\n\n")
                .append("- Target: `").append(run.metadata().target()).append("`\n")
                .append("- Suite: `").append(run.metadata().suiteId()).append("@")
                .append(run.metadata().suiteVersion()).append("`\n")
                .append("- Git SHA: `").append(value(run.metadata().gitSha())).append("`\n")
                .append("- Source tree state: `")
                .append(run.metadata().sourceTreeState()).append("`\n")
                .append("- Fully reproducible: `")
                .append(run.metadata().fullyReproducible()).append("`\n")
                .append("- Cases: ").append(summary.caseCount()).append("\n")
                .append("- Contract pass rate: ").append(summary.contractPassRate()).append("\n")
                .append("- Tool selection correct rate: ")
                .append(summary.toolSelectionCorrectRate()).append("\n")
                .append("- Usage completeness rate: ")
                .append(summary.usageCompletenessRate()).append("\n")
                .append("- Cost completeness rate: ")
                .append(summary.costCompletenessRate()).append("\n")
                .append("- Latency P50/P95 (ms): ")
                .append(summary.p50LatencyMillis()).append(" / ")
                .append(summary.p95LatencyMillis()).append("\n\n")
                .append("> contractPass measures deterministic Runtime contract conformance; ")
                .append("it is not semantic answer quality.\n");
        if (!run.metadata().reproducibilityIssues().isEmpty()) {
            report.append("\n## Reproducibility issues\n\n");
            run.metadata().reproducibilityIssues().forEach(issue ->
                    report.append("- ").append(issue).append("\n")
            );
        }
        return report.toString();
    }

    private String value(String value) {
        return value == null ? "MISSING" : value;
    }

    private record ResultsDocument(EvalRunMetadata metadata, java.util.List<EvalRecord> records) {
    }

    private record SummaryDocument(EvalRunMetadata metadata, EvalSummary summary) {
    }
}
