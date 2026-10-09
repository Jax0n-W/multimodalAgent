package com.multimodalAgent.agent.context;

import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ToolCall;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deep defensive copy for the JSON-shaped semantic content owned by a context snapshot. */
final class ContextSemanticContent {

    private ContextSemanticContent() {
    }

    static List<AgentMessage> freezeMessages(List<AgentMessage> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        List<AgentMessage> frozen = new ArrayList<>(messages.size());
        for (AgentMessage message : messages) {
            AgentMessage value = Objects.requireNonNull(
                    message,
                    "messages must not contain null"
            );
            List<ToolCall> calls = new ArrayList<>(value.toolCalls().size());
            for (ToolCall call : value.toolCalls()) {
                ToolCall toolCall = Objects.requireNonNull(
                        call,
                        "toolCalls must not contain null"
                );
                calls.add(new ToolCall(
                        toolCall.id(),
                        toolCall.name(),
                        freezeArguments(toolCall.arguments())
                ));
            }
            frozen.add(new AgentMessage(
                    value.role(),
                    value.content(),
                    calls,
                    value.toolCallId(),
                    value.toolName()
            ));
        }
        return List.copyOf(frozen);
    }

    private static Map<String, Object> freezeArguments(Map<String, Object> arguments) {
        Map<String, Object> frozen = new LinkedHashMap<>();
        arguments.forEach((key, value) -> {
            if (key == null) {
                throw new ContextAssemblyException("Tool argument keys must not be null");
            }
            frozen.put(key, freezeJsonValue(value));
        });
        return Collections.unmodifiableMap(frozen);
    }

    private static Object freezeJsonValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> frozen = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String stringKey)) {
                    throw new ContextAssemblyException(
                            "Tool argument object keys must be strings"
                    );
                }
                frozen.put(stringKey, freezeJsonValue(nested));
            });
            return Collections.unmodifiableMap(frozen);
        }
        if (value instanceof List<?> list) {
            List<Object> frozen = new ArrayList<>(list.size());
            list.forEach(item -> frozen.add(freezeJsonValue(item)));
            return Collections.unmodifiableList(frozen);
        }
        throw new ContextAssemblyException(
                "Unsupported ToolCall argument value type: " + value.getClass().getName()
        );
    }
}
