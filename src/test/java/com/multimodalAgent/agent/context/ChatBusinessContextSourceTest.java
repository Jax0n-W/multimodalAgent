package com.multimodalAgent.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatBusinessContextSourceTest {

    @Test
    void trustedBusinessContextIsSeparateAndSnapshotBound() {
        AgentContextSnapshot snapshot = new AgentContextAssembler(
                List.of(new ChatBusinessContextSource(), new RequestMessageContextSource()),
                new AgentContextSnapshotFactory(new ObjectMapper()),
                Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC)
        ).assemble(new ContextAssemblyInput(
                "run", "session", 7L,
                List.of(AgentMessage.user("current user message")),
                Set.of(),
                List.of(AgentMessage.system("trusted safety context"))
        ));

        assertEquals(List.of("chat-business-context", "request-messages"),
                snapshot.orderedContributions().stream()
                        .map(ContextProvenance::sourceId).toList());
        assertEquals(List.of(
                AgentMessage.system("trusted safety context"),
                AgentMessage.user("current user message")
        ), snapshot.messages());
    }

    @Test
    void callerCannotPromoteUserContentIntoTrustedBusinessContext() {
        assertThrows(IllegalArgumentException.class, () -> new ContextAssemblyInput(
                "run", "session", 7L,
                List.of(AgentMessage.user("current")), Set.of(),
                List.of(AgentMessage.user("untrusted"))
        ));
    }
}
