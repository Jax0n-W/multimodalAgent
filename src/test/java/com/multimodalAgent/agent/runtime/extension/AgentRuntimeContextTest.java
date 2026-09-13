package com.multimodalAgent.agent.runtime.extension;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeContextTest {

    @Test
    void shouldStoreReadAndRemoveTypedAttributes() {
        RuntimeAttributes attributes = new RuntimeAttributes();
        RuntimeAttributeKey<Integer> count = RuntimeAttributeKey.of("model-count", Integer.class);

        assertFalse(attributes.contains(count));
        assertEquals(0, attributes.put(count, 1).orElse(0));
        assertEquals(1, attributes.get(count).orElseThrow());
        assertEquals(1, attributes.remove(count).orElseThrow());
        assertFalse(attributes.contains(count));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldRejectAValueThatDoesNotMatchTheKeyType() {
        RuntimeAttributes attributes = new RuntimeAttributes();
        RuntimeAttributeKey rawKey = RuntimeAttributeKey.of("trace-id", String.class);

        assertThrows(IllegalArgumentException.class, () -> attributes.put(rawKey, 42));
    }

    @Test
    void shouldTreatSameNameWithDifferentTypesAsDifferentKeys() {
        RuntimeAttributes attributes = new RuntimeAttributes();
        RuntimeAttributeKey<String> text = RuntimeAttributeKey.of("value", String.class);
        RuntimeAttributeKey<Integer> number = RuntimeAttributeKey.of("value", Integer.class);

        attributes.put(text, "forty-two");
        attributes.put(number, 42);

        assertEquals("forty-two", attributes.get(text).orElseThrow());
        assertEquals(42, attributes.get(number).orElseThrow());
    }

    @Test
    void shouldReturnAnImmutableAttributeSnapshot() {
        RuntimeAttributes attributes = new RuntimeAttributes();
        RuntimeAttributeKey<String> traceId = RuntimeAttributeKey.of("trace-id", String.class);
        attributes.put(traceId, "trace-1");

        Map<RuntimeAttributeKey<?>, Object> snapshot = attributes.snapshot();

        assertEquals("trace-1", snapshot.get(traceId));
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        attributes.put(traceId, "trace-2");
        assertEquals("trace-1", snapshot.get(traceId));
    }

    @Test
    void shouldKeepAttributesIsolatedBetweenRunContexts() {
        RuntimeAttributeKey<String> traceId = RuntimeAttributeKey.of("trace-id", String.class);
        AgentRuntimeContext first = AgentRuntimeContext.minimal("run-a", "session-a");
        AgentRuntimeContext second = AgentRuntimeContext.minimal("run-b", "session-b");

        first.attributes().put(traceId, "trace-a");

        assertEquals("trace-a", first.attributes().get(traceId).orElseThrow());
        assertFalse(second.attributes().contains(traceId));
    }

    @Test
    void shouldCreateMinimalContextWithoutInventingApplicationIdentity() {
        AgentRuntimeContext context = AgentRuntimeContext.minimal("run-1", "session-1");

        assertEquals("run-1", context.runId());
        assertEquals("session-1", context.sessionId());
        assertEquals(null, context.requestId());
        assertEquals(null, context.userId());
        assertEquals(null, context.runtimeConfigSnapshotId());
        assertFalse(context.cancellationContext().isCancellationRequested());
    }
}
