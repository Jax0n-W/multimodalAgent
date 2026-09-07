package com.multimodalAgent.agent.runtime.tool;

public interface AgentTool<I, O> {

    ToolDescriptor<I> descriptor();

    O execute(I input);

    default String name() {
        return descriptor().name();
    }
}
