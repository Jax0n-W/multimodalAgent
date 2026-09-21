package com.multimodalAgent.agent.streaming;

/** Signals that one subscriber could not keep up with its isolated bounded buffer. */
final class SlowExecutionStreamSubscriberException extends IllegalStateException {

    SlowExecutionStreamSubscriberException(String runId) {
        super("Execution stream subscriber buffer overflow for run " + runId);
    }
}
