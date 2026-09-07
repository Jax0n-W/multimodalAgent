package com.multimodalAgent.agent.runtime;

import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.tool.ToolErrorCode;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class AgentRunner {

    private final AgentModel model;
    private final ToolExecutor toolExecutor;

    public AgentRunner(AgentModel model, ToolExecutor toolExecutor) {
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
    }

    public AgentRunResult run(AgentRunSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        List<AgentMessage> messages = new ArrayList<>(spec.messages());
        Set<String> toolsUsed = new LinkedHashSet<>();
        TokenUsage totalUsage = TokenUsage.ZERO;

        for (int iteration = 1; iteration <= spec.maxIterations(); iteration++) {
            ModelTurn turn;
            try {
                turn = Objects.requireNonNull(
                        model.generate(List.copyOf(messages)),
                        "model returned a null turn"
                );
            } catch (RuntimeException exception) {
                return stopped(
                        AgentStopReason.MODEL_ERROR,
                        iteration,
                        toolsUsed,
                        messages,
                        totalUsage,
                        null,
                        exception.getMessage()
                );
            }

            totalUsage = totalUsage.plus(turn.tokenUsage());
            if (turn.finishReason() == ModelFinishReason.STOP) {
                messages.add(AgentMessage.assistant(turn.content()));
                return new AgentRunResult(
                        turn.content(),
                        AgentStopReason.COMPLETED,
                        iteration,
                        List.copyOf(toolsUsed),
                        messages,
                        totalUsage,
                        null,
                        null
                );
            }

            messages.add(AgentMessage.assistantToolCalls(turn.toolCalls()));
            for (ToolCall toolCall : turn.toolCalls()) {
                ToolResult result = toolExecutor.execute(toolCall);
                messages.add(AgentMessage.toolResult(
                        toolCall.id(), toolCall.name(), result.messageForModel()
                ));
                if (!result.success()) {
                    ToolErrorCode errorCode = result.error().code();
                    if (errorCode != ToolErrorCode.TOOL_NOT_FOUND) {
                        toolsUsed.add(toolCall.name());
                    }
                    return stopped(
                            AgentStopReason.TOOL_ERROR,
                            iteration,
                            toolsUsed,
                            messages,
                            totalUsage,
                            errorCode,
                            result.error().message()
                    );
                }
                toolsUsed.add(toolCall.name());
            }
        }

        return stopped(
                AgentStopReason.MAX_ITERATIONS,
                spec.maxIterations(),
                toolsUsed,
                messages,
                totalUsage,
                null,
                "Maximum model iterations reached"
        );
    }

    private AgentRunResult stopped(
            AgentStopReason stopReason,
            int iterations,
            Set<String> toolsUsed,
            List<AgentMessage> messages,
            TokenUsage tokenUsage,
            ToolErrorCode toolErrorCode,
            String errorMessage
    ) {
        return new AgentRunResult(
                "",
                stopReason,
                iterations,
                List.copyOf(toolsUsed),
                messages,
                tokenUsage,
                toolErrorCode,
                errorMessage
        );
    }
}
