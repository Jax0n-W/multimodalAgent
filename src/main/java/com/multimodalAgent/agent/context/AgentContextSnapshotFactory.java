package com.multimodalAgent.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentMessageRole;
import com.multimodalAgent.agent.runtime.model.ToolCall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical serializer and verifier for content-addressed semantic context snapshots. */
public final class AgentContextSnapshotFactory {

    private final ObjectMapper objectMapper;

    public AgentContextSnapshotFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null")
                .copy();
    }

    public AgentContextSnapshot create(
            ContextAssemblyInput input,
            List<ContextProvenance> orderedContributions,
            List<AgentMessage> messages,
            Instant createdAt
    ) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        String canonicalJson = canonicalJson(
                AgentContextSnapshot.CURRENT_SCHEMA_VERSION,
                input.runId(),
                input.sessionId(),
                input.userId(),
                orderedContributions,
                messages
        );
        String hash = sha256(canonicalJson);
        return new AgentContextSnapshot(
                "context-v" + AgentContextSnapshot.CURRENT_SCHEMA_VERSION + "-" + hash,
                AgentContextSnapshot.CURRENT_SCHEMA_VERSION,
                input.runId(),
                input.sessionId(),
                input.userId(),
                orderedContributions,
                messages,
                createdAt,
                hash,
                canonicalJson
        );
    }

    public AgentContextSnapshot restore(
            String snapshotId,
            Instant createdAt,
            String canonicalJson
    ) {
        requireText(snapshotId, "snapshotId");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        requireText(canonicalJson, "canonicalJson");
        try {
            JsonNode root = objectMapper.readTree(canonicalJson);
            int schemaVersion = required(root, "schemaVersion").intValue();
            String runId = required(root, "runId").textValue();
            String sessionId = required(root, "sessionId").textValue();
            long userId = required(root, "userId").longValue();
            List<ContextProvenance> contributions = readContributions(
                    required(root, "contributions")
            );
            List<AgentMessage> messages = readMessages(required(root, "messages"));
            String expectedCanonical = canonicalJson(
                    schemaVersion,
                    runId,
                    sessionId,
                    userId,
                    contributions,
                    messages
            );
            if (!canonicalJson.equals(expectedCanonical)) {
                throw new ContextAssemblyException(
                        "Stored context snapshot is not canonical: " + snapshotId
                );
            }
            String hash = sha256(canonicalJson);
            AgentContextSnapshot restored = new AgentContextSnapshot(
                    snapshotId,
                    schemaVersion,
                    runId,
                    sessionId,
                    userId,
                    contributions,
                    messages,
                    createdAt,
                    hash,
                    canonicalJson
            );
            if (!snapshotId.equals("context-v" + schemaVersion + "-" + hash)) {
                throw new ContextAssemblyException(
                        "Stored context snapshot identity does not match its content: "
                                + snapshotId
                );
            }
            return restored;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ContextAssemblyException(
                    "Could not restore context snapshot: " + snapshotId,
                    exception
            );
        }
    }

    public String contentHash(List<AgentMessage> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        return sha256(write(messagesNode(messages)));
    }

    private String canonicalJson(
            int schemaVersion,
            String runId,
            String sessionId,
            Long userId,
            List<ContextProvenance> contributions,
            List<AgentMessage> messages
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", schemaVersion);
        root.put("runId", runId);
        root.put("sessionId", sessionId);
        root.put("userId", userId);
        ArrayNode contributionNodes = root.putArray("contributions");
        for (ContextProvenance contribution : contributions) {
            ObjectNode node = contributionNodes.addObject();
            node.put("sourceId", contribution.sourceId());
            node.put("sourceVersion", contribution.sourceVersion());
            node.put("order", contribution.order());
            node.put("messageCount", contribution.messageCount());
            node.put("contentHash", contribution.contentHash());
        }
        root.set("messages", messagesNode(messages));
        return write(root);
    }

    private ArrayNode messagesNode(List<AgentMessage> messages) {
        ArrayNode result = objectMapper.createArrayNode();
        for (AgentMessage message : messages) {
            ObjectNode node = result.addObject();
            node.put("role", message.role().name());
            node.put("content", message.content());
            ArrayNode toolCalls = node.putArray("toolCalls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode callNode = toolCalls.addObject();
                callNode.put("id", call.id());
                callNode.put("name", call.name());
                callNode.set("arguments", canonicalize(objectMapper.valueToTree(
                        call.arguments()
                )));
            }
            if (message.toolCallId() == null) {
                node.putNull("toolCallId");
            } else {
                node.put("toolCallId", message.toolCallId());
            }
            if (message.toolName() == null) {
                node.putNull("toolName");
            } else {
                node.put("toolName", message.toolName());
            }
        }
        return result;
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            node.fields().forEachRemaining(fields::add);
            fields.sort(Comparator.comparing(Map.Entry::getKey));
            fields.forEach(field -> sorted.set(
                    field.getKey(),
                    canonicalize(field.getValue())
            ));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(value -> array.add(canonicalize(value)));
            return array;
        }
        return node.deepCopy();
    }

    private List<ContextProvenance> readContributions(JsonNode node) {
        if (!node.isArray()) {
            throw new ContextAssemblyException("contributions must be an array");
        }
        List<ContextProvenance> result = new ArrayList<>();
        node.forEach(value -> result.add(new ContextProvenance(
                required(value, "sourceId").textValue(),
                required(value, "sourceVersion").textValue(),
                required(value, "order").intValue(),
                required(value, "messageCount").intValue(),
                required(value, "contentHash").textValue()
        )));
        return List.copyOf(result);
    }

    private List<AgentMessage> readMessages(JsonNode node) {
        if (!node.isArray()) {
            throw new ContextAssemblyException("messages must be an array");
        }
        List<AgentMessage> result = new ArrayList<>();
        node.forEach(value -> {
            List<ToolCall> calls = new ArrayList<>();
            JsonNode callNodes = required(value, "toolCalls");
            if (!callNodes.isArray()) {
                throw new ContextAssemblyException("toolCalls must be an array");
            }
            callNodes.forEach(call -> calls.add(new ToolCall(
                    required(call, "id").textValue(),
                    required(call, "name").textValue(),
                    readArguments(required(call, "arguments"))
            )));
            JsonNode toolCallId = required(value, "toolCallId");
            JsonNode toolName = required(value, "toolName");
            result.add(new AgentMessage(
                    AgentMessageRole.valueOf(required(value, "role").textValue()),
                    required(value, "content").textValue(),
                    calls,
                    toolCallId.isNull() ? null : toolCallId.textValue(),
                    toolName.isNull() ? null : toolName.textValue()
            ));
        });
        return List.copyOf(result);
    }

    private Map<String, Object> readArguments(JsonNode node) {
        if (!node.isObject()) {
            throw new ContextAssemblyException("tool arguments must be an object");
        }
        Map<String, Object> values = objectMapper.convertValue(
                node,
                new TypeReference<LinkedHashMap<String, Object>>() {
                }
        );
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private JsonNode required(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            throw new ContextAssemblyException("Missing context snapshot field: " + field);
        }
        return node.get(field);
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new ContextAssemblyException("Could not canonicalize semantic context", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
