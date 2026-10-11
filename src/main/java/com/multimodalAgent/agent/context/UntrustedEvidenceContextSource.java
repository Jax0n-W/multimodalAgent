package com.multimodalAgent.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.model.AgentMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Serializes external evidence as one provenance-bound, non-authoritative data message. */
public final class UntrustedEvidenceContextSource implements ContextSource {

    public static final String SOURCE_ID = "chat-untrusted-evidence";
    public static final String SOURCE_VERSION = "1";
    public static final int ORDER = 0;
    public static final String ENVELOPE_PREFIX = "UNTRUSTED_EVIDENCE_DATA_V1\n";

    private final ObjectMapper objectMapper;

    public UntrustedEvidenceContextSource(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public String sourceVersion() {
        return SOURCE_VERSION;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ContextContribution load(ContextAssemblyInput input) {
        if (input.untrustedEvidence().isEmpty()) {
            return new ContextContribution(List.of());
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 1);
        List<Map<String, String>> items = new ArrayList<>();
        for (UntrustedEvidence evidence : input.untrustedEvidence()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("kind", evidence.kind());
            item.put("source", evidence.source());
            item.put("content", evidence.content());
            items.add(item);
        }
        envelope.put("evidence", items);
        try {
            // ASSISTANT is deliberately used as the existing non-authoritative, non-student role.
            // Provenance, the envelope, and the trusted policy distinguish this from prior turns.
            return new ContextContribution(List.of(AgentMessage.assistant(
                    ENVELOPE_PREFIX + objectMapper.writeValueAsString(envelope)
            )));
        } catch (JsonProcessingException exception) {
            throw new ContextAssemblyException("Could not serialize untrusted evidence", exception);
        }
    }
}
