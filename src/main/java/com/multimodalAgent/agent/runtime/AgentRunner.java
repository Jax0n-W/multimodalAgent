package com.multimodalAgent.agent.runtime;

import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class AgentRunner {

    private final AgentModel model;
    private final ToolRegistry toolRegistry;

    public AgentRunner(AgentModel model, ToolRegistry toolRegistry) {
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
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
                        null
                );
            }

            messages.add(AgentMessage.assistantToolCalls(turn.toolCalls()));
            for (ToolCall toolCall : turn.toolCalls()) {
                AgentTool tool = toolRegistry.find(toolCall.name()).orElse(null);
                if (tool == null) {
                    String error = "Unknown tool: " + toolCall.name();
                    messages.add(AgentMessage.toolResult(
                            toolCall.id(), toolCall.name(), error
                    ));
                    return stopped(
                            AgentStopReason.TOOL_ERROR,
                            iteration,
                            toolsUsed,
                            messages,
                            totalUsage,
                            error
                    );
                }

                toolsUsed.add(tool.name());
                ToolResult result;
                try {
                    result = Objects.requireNonNull(
                            tool.execute(toolCall.arguments()),
                            "tool returned a null result"
                    );
                } catch (RuntimeException exception) {
                    String error = "Tool execution failed: " + tool.name() + ": " + exception.getMessage();
                    messages.add(AgentMessage.toolResult(
                            toolCall.id(), tool.name(), error
                    ));
                    return stopped(
                            AgentStopReason.TOOL_ERROR,
                            iteration,
                            toolsUsed,
                            messages,
                            totalUsage,
                            error
                    );
                }

                messages.add(AgentMessage.toolResult(
                        toolCall.id(), tool.name(), result.content()
                ));
                if (!result.success()) {
                    return stopped(
                            AgentStopReason.TOOL_ERROR,
                            iteration,
                            toolsUsed,
                            messages,
                            totalUsage,
                            result.content()
                    );
                }
            }
        }

        return stopped(
                AgentStopReason.MAX_ITERATIONS,
                spec.maxIterations(),
                toolsUsed,
                messages,
                totalUsage,
                "Maximum model iterations reached"
        );
    }

    private AgentRunResult stopped(
            AgentStopReason stopReason,
            int iterations,
            Set<String> toolsUsed,
            List<AgentMessage> messages,
            TokenUsage tokenUsage,
            String errorMessage
    ) {
        return new AgentRunResult(
                "",
                stopReason,
                iterations,
                List.copyOf(toolsUsed),
                messages,
                tokenUsage,
                errorMessage
        );
    }
}
