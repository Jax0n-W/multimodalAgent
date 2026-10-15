package com.multimodalAgent.agent.tool.campus;

import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.runtime.tool.ContextAwareAgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolExecutionContext;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.UntrustedEvidenceTool;
import com.multimodalAgent.agent.service.ReportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class QueryMyRecentAssessmentsTool implements
        ContextAwareAgentTool<QueryMyRecentAssessmentsTool.Input, QueryMyRecentAssessmentsTool.Output>,
        UntrustedEvidenceTool {

    public static final String NAME = "query_my_recent_assessments";

    private final ReportService reports;
    private final ToolDescriptor<Input> descriptor;

    public QueryMyRecentAssessmentsTool(ReportService reports, Duration timeout) {
        this.reports = Objects.requireNonNull(reports, "reports must not be null");
        descriptor = new ToolDescriptor<>(
                NAME,
                "Query the authenticated user's own recent assessment summaries; raw conversation content is never returned.",
                Input.class,
                ToolRisk.MEDIUM,
                true,
                true,
                false,
                timeout
        );
    }

    @Override
    public ToolDescriptor<Input> descriptor() {
        return descriptor;
    }

    @Override
    public Output execute(Input input, ToolExecutionContext context) {
        int limit = input.effectiveLimit();
        List<Assessment> assessments = reports.myReports(context.requireUserId()).stream()
                .limit(limit)
                .map(QueryMyRecentAssessmentsTool::project)
                .toList();
        return new Output(assessments.size(), assessments);
    }

    @Override
    public String evidenceKind() {
        return "private-assessment-summary";
    }

    private static Assessment project(PsychologicalReport report) {
        return new Assessment(
                report.getId(),
                report.getCreatedAt(),
                report.getEmotion(),
                report.getRiskLevel(),
                report.getSummary()
        );
    }

    public record Input(@Min(1) @Max(10) Integer limit) {
        int effectiveLimit() {
            return limit == null ? 5 : limit;
        }
    }

    public record Output(int resultCount, List<Assessment> assessments) {
        public Output {
            assessments = List.copyOf(assessments);
        }
    }

    public record Assessment(
            Long reportId,
            Instant createdAt,
            EmotionLabel emotion,
            RiskLevel risk,
            String summary
    ) {
    }
}
