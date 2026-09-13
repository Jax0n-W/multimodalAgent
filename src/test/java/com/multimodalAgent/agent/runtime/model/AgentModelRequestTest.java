package com.multimodalAgent.agent.runtime.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentModelRequestTest {

    @Test
    void shouldDefensivelyCopyMessagesAndTools() {
        List<AgentMessage> messages = new ArrayList<>(List.of(AgentMessage.user("question")));
        List<ModelToolDefinition> tools = new ArrayList<>(List.of(definition()));

        AgentModelRequest request = new AgentModelRequest(messages, tools);
        messages.clear();
        tools.clear();

        assertEquals(1, request.messages().size());
        assertEquals(1, request.tools().size());
        assertThrows(UnsupportedOperationException.class,
                () -> request.messages().add(AgentMessage.user("another")));
        assertThrows(UnsupportedOperationException.class,
                () -> request.tools().add(definition()));
    }

    @Test
    void shouldDefensivelyCopyNestedSchemaValues() {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", property);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);

        ModelToolDefinition definition = new ModelToolDefinition("lookup", "Lookup", schema);
        property.put("description", "mutated");

        Map<?, ?> copiedProperties = (Map<?, ?>) definition.inputSchema().get("properties");
        Map<?, ?> copiedProperty = (Map<?, ?>) copiedProperties.get("query");
        assertEquals(Map.of("type", "string"), copiedProperty);
        assertThrows(UnsupportedOperationException.class, copiedProperty::clear);
    }

    private ModelToolDefinition definition() {
        return new ModelToolDefinition(
                "lookup",
                "Lookup",
                Map.of("type", "object")
        );
    }
}
