package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.SpringAiModelAdapterException;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Incrementally assembles raw OpenAI ToolCall deltas by their provider-supplied index. */
final class PartialToolCallAssembler {

    private static final TypeReference<Map<String, Object>> ARGUMENT_MAP = new TypeReference<>() {
    };

    private final TreeMap<Integer, MutableToolCall> calls = new TreeMap<>();

    void accept(List<OpenAiApi.ChatCompletionMessage.ToolCall> fragments) {
        if (fragments == null) {
            return;
        }
        for (OpenAiApi.ChatCompletionMessage.ToolCall fragment : fragments) {
            accept(fragment);
        }
    }

    boolean isEmpty() {
        return calls.isEmpty();
    }

    List<ToolCall> assemble(ObjectMapper objectMapper, String providerName) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        List<ToolCall> result = new ArrayList<>(calls.size());
        Set<String> ids = new HashSet<>();
        for (Map.Entry<Integer, MutableToolCall> entry : calls.entrySet()) {
            ToolCall call = entry.getValue().assemble(objectMapper, providerName, entry.getKey());
            if (!ids.add(call.id())) {
                throw failure(providerName, "duplicate toolCallId " + call.id());
            }
            result.add(call);
        }
        return List.copyOf(result);
    }

    private void accept(OpenAiApi.ChatCompletionMessage.ToolCall fragment) {
        if (fragment == null || fragment.index() == null || fragment.index() < 0) {
            throw new SpringAiModelAdapterException(
                    "Streaming tool-call fragment must contain a non-negative provider index"
            );
        }
        calls.computeIfAbsent(fragment.index(), ignored -> new MutableToolCall())
                .append(fragment);
    }

    private static SpringAiModelAdapterException failure(String providerName, String detail) {
        return new SpringAiModelAdapterException(
                providerName + " returned an incomplete streaming tool call: " + detail
        );
    }

    private static final class MutableToolCall {

        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
        private String type;

        private void append(OpenAiApi.ChatCompletionMessage.ToolCall fragment) {
            append(id, fragment.id());
            if (fragment.type() != null && !fragment.type().isBlank()) {
                if (type != null && !type.equalsIgnoreCase(fragment.type())) {
                    throw new SpringAiModelAdapterException(
                            "Streaming tool-call fragment changed its type"
                    );
                }
                type = fragment.type();
            }
            OpenAiApi.ChatCompletionMessage.ChatCompletionFunction function = fragment.function();
            if (function != null) {
                append(name, function.name());
                append(arguments, function.arguments());
            }
        }

        private ToolCall assemble(ObjectMapper objectMapper, String providerName, int index) {
            String completedId = id.toString();
            String completedName = name.toString();
            String completedArguments = arguments.toString();
            if (completedId.isBlank()) {
                throw failure(providerName, "missing id at index " + index);
            }
            if (completedName.isBlank()) {
                throw failure(providerName, "missing function name at index " + index);
            }
            if (type == null) {
                throw failure(providerName, "missing type at index " + index);
            }
            if (!"function".equalsIgnoreCase(type)) {
                throw failure(providerName, "unsupported type " + type + " at index " + index);
            }
            if (completedArguments.isBlank()) {
                throw failure(providerName, "missing arguments at index " + index);
            }
            try {
                Map<String, Object> parsed = objectMapper.readValue(
                        completedArguments,
                        ARGUMENT_MAP
                );
                return new ToolCall(completedId, completedName, parsed);
            } catch (Exception exception) {
                throw new SpringAiModelAdapterException(
                        providerName + " returned malformed JSON arguments for streaming tool "
                                + completedName,
                        exception
                );
            }
        }

        private static void append(StringBuilder target, String fragment) {
            if (fragment != null && !fragment.isEmpty()) {
                target.append(fragment);
            }
        }
    }
}
