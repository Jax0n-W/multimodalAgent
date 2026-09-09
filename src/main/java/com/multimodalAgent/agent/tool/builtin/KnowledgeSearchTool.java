package com.multimodalAgent.agent.tool.builtin;

import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.service.knowledge.KnowledgeService;
import com.multimodalAgent.agent.service.knowledge.SearchResult;

import java.util.List;
import java.util.Objects;

public final class KnowledgeSearchTool implements AgentTool<KnowledgeSearchInput, List<SearchResult>> {

    private static final ToolDescriptor<KnowledgeSearchInput> DESCRIPTOR = new ToolDescriptor<>(
            "knowledge_search",
            "Search the application knowledge base for context relevant to a query",
            KnowledgeSearchInput.class,
            ToolRisk.LOW,
            true,
            true,
            false
    );

    private final KnowledgeService knowledgeService;

    public KnowledgeSearchTool(KnowledgeService knowledgeService) {
        this.knowledgeService = Objects.requireNonNull(knowledgeService, "knowledgeService must not be null");
    }

    @Override
    public ToolDescriptor<KnowledgeSearchInput> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<SearchResult> execute(KnowledgeSearchInput input) {
        return knowledgeService.retrieve(input.query(), input.effectiveTopK());
    }
}
