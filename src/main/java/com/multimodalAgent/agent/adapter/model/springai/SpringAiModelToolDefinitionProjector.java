package com.multimodalAgent.agent.adapter.model.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.model.ModelToolDefinition;
import com.multimodalAgent.agent.runtime.model.ModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

import java.util.Map;
import java.util.Objects;

/**
 * Generates a provider-neutral schema with Spring AI's schema utility at the adapter boundary.
 */
public final class SpringAiModelToolDefinitionProjector
        implements ModelToolDefinitionProjector {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public SpringAiModelToolDefinitionProjector(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public ModelToolDefinition project(ToolDescriptor<?> descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        try {
            String schema = JsonSchemaGenerator.generateForType(descriptor.inputType());
            return new ModelToolDefinition(
                    descriptor.name(),
                    descriptor.description(),
                    objectMapper.readValue(schema, MAP_TYPE)
            );
        } catch (RuntimeException exception) {
            throw new SpringAiModelAdapterException(
                    "Could not generate schema for tool: " + descriptor.name(),
                    exception
            );
        } catch (Exception exception) {
            throw new SpringAiModelAdapterException(
                    "Could not parse schema for tool: " + descriptor.name(),
                    exception
            );
        }
    }
}
