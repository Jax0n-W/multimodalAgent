package com.multimodalAgent.agent.stream;

/**
 * A text fragment observed from a streaming model provider.
 *
 * <p>A delta is not a Runtime fact and cannot replace a complete ModelTurn. Whitespace-only
 * fragments are allowed because they can be meaningful provider output.</p>
 */
public record ModelDelta(int iteration, String content) implements ExecutionStreamPayload {

    public ModelDelta {
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be at least 1");
        }
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
    }

    @Override
    public ExecutionStreamEventKind kind() {
        return ExecutionStreamEventKind.MODEL_DELTA;
    }
}
