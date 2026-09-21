package com.multimodalAgent.agent.runtime.control;

/** Internal control flow before an allowed Tool has emitted TOOL_STARTED. */
public final class ExecutionCancelledException extends RuntimeException {

    public ExecutionCancelledException() {
        super("Execution cancellation observed before Tool execution", null, false, false);
    }
}
