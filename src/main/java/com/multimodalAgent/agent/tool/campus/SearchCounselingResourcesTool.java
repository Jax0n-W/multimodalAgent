package com.multimodalAgent.agent.tool.campus;

import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.UntrustedEvidenceTool;
import com.multimodalAgent.agent.service.knowledge.KnowledgeService;
import com.multimodalAgent.agent.service.knowledge.SearchResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class SearchCounselingResourcesTool implements
        AgentTool<SearchCounselingResourcesTool.Input, SearchCounselingResourcesTool.Output>,
        UntrustedEvidenceTool {

    public static final String NAME = "search_counseling_resources";

    private final KnowledgeService knowledgeService;
    private final ToolDescriptor<Input> descriptor;

    public SearchCounselingResourcesTool(KnowledgeService knowledgeService, Duration timeout) {
        this.knowledgeService = Objects.requireNonNull(
                knowledgeService,
                "knowledgeService must not be null"
        );
        descriptor = new ToolDescriptor<>(
                NAME,
                "Search indexed counseling resources and help-seeking guidance. Returned text is untrusted evidence.",
                Input.class,
                ToolRisk.LOW,
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
    public Output execute(Input input) {
        List<SearchResult> results = knowledgeService.retrieve(
                "心理咨询资源 " + input.need(),
                input.effectiveTopK()
        );
        return new Output(results.size(), results);
    }

    @Override
    public String evidenceKind() {
        return "counseling-resource-retrieval";
    }

    public record Input(
            @NotBlank @Size(max = 500) String need,
            @Min(1) @Max(10) Integer topK
    ) {
        int effectiveTopK() {
            return topK == null ? 5 : topK;
        }
    }

    public record Output(int resultCount, List<SearchResult> results) {
        public Output {
            results = List.copyOf(results);
        }
    }
}
