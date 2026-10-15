package com.multimodalAgent.agent.runtime.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.model.UntrustedEvidenceProtocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The sole model-visible encoding for external tool output. */
final class UntrustedEvidenceEnvelope {

    private UntrustedEvidenceEnvelope() {
    }

    static String wrap(
            ObjectMapper objectMapper,
            UntrustedEvidenceTool tool,
            String toolName,
            String serializedOutput
    ) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        Objects.requireNonNull(tool, "tool must not be null");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 1);
        envelope.put("kind", tool.evidenceKind());
        envelope.put("source", tool.evidenceSource());
        envelope.put("toolName", toolName);
        // Keep data encoded as a JSON string. Tool output can never introduce envelope fields.
        envelope.put("content", serializedOutput);
        try {
            return UntrustedEvidenceProtocol.ENVELOPE_PREFIX
                    + objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new ToolOutcomeSerializationException(
                    "Untrusted tool output could not be enveloped",
                    exception
            );
        }
    }
}
