package com.multimodalAgent.agent.runtime.tool;

/** Marker for tools whose output is external or otherwise non-authoritative model evidence. */
public interface UntrustedEvidenceTool {

    default String evidenceKind() {
        return "tool-output";
    }

    default String evidenceSource() {
        if (this instanceof AgentTool<?, ?> tool) {
            return "tool:" + tool.name();
        }
        return "tool:unknown";
    }
}
