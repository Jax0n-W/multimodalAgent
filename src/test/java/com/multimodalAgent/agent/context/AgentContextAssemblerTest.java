package com.multimodalAgent.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-09-30T01:00:00Z");

    @Test
    void requestMessagesRoundTripUnchangedAndSnapshotIsImmutable() {
        List<AgentMessage> messages = requestMessages("original");
        AgentContextSnapshot snapshot = assembler(
                List.of(new RequestMessageContextSource())
        ).assemble(input(messages));

        assertEquals(messages, snapshot.messages());
        assertEquals(List.of(RequestMessageContextSource.SOURCE_ID),
                snapshot.orderedContributions().stream()
                        .map(ContextProvenance::sourceId)
                        .toList());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.messages().add(AgentMessage.user("mutation")));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.orderedContributions().clear());
    }

    @Test
    void ordersSourcesByOrderThenSourceIdRegardlessOfRegistrationOrder() {
        AgentContextSnapshot snapshot = assembler(List.of(
                source("later", "1", 20, "later"),
                source("z-source", "1", 10, "z"),
                source("a-source", "1", 10, "a")
        )).assemble(input(List.of(AgentMessage.user("request"))));

        assertEquals(List.of("a-source", "z-source", "later"),
                snapshot.orderedContributions().stream()
                        .map(ContextProvenance::sourceId)
                        .toList());
        assertEquals(List.of("a", "z", "later"), snapshot.messages().stream()
                .map(AgentMessage::content)
                .toList());
    }

    @Test
    void sourceVersionBreaksTheOtherwiseIdenticalOrderingTieDeterministically() {
        AgentContextSnapshot snapshot = assembler(List.of(
                source("same-source", "2", 10, "v2"),
                source("same-source", "1", 10, "v1")
        )).assemble(input(List.of(AgentMessage.user("request"))));

        assertEquals(List.of("1", "2"), snapshot.orderedContributions().stream()
                .map(ContextProvenance::sourceVersion)
                .toList());
        assertEquals(List.of("v1", "v2"), snapshot.messages().stream()
                .map(AgentMessage::content)
                .toList());
    }

    @Test
    void semanticIdentityIgnoresClockAndMapIterationButTracksEverySemanticChange() {
        LinkedHashMap<String, Object> firstArguments = new LinkedHashMap<>();
        firstArguments.put("z", 2);
        firstArguments.put("a", Map.of("second", true, "first", 1));
        LinkedHashMap<String, Object> secondArguments = new LinkedHashMap<>();
        secondArguments.put("a", Map.of("first", 1, "second", true));
        secondArguments.put("z", 2);
        List<AgentMessage> firstMessages = List.of(AgentMessage.assistantToolCalls(List.of(
                new ToolCall("call-1", "tool", firstArguments)
        )));
        List<AgentMessage> secondMessages = List.of(AgentMessage.assistantToolCalls(List.of(
                new ToolCall("call-1", "tool", secondArguments)
        )));

        AgentContextSnapshot first = assemblerAt(
                List.of(source("source", "1", 5, firstMessages)), NOW
        ).assemble(input(List.of(AgentMessage.user("ignored by source"))));
        AgentContextSnapshot same = assemblerAt(
                List.of(source("source", "1", 5, secondMessages)), NOW.plusSeconds(60)
        ).assemble(input(List.of(AgentMessage.user("ignored by source"))));
        AgentContextSnapshot changedMessage = assembler(
                List.of(source("source", "1", 5, "changed"))
        ).assemble(input(List.of(AgentMessage.user("request"))));
        AgentContextSnapshot changedVersion = assembler(
                List.of(source("source", "2", 5, secondMessages))
        ).assemble(input(List.of(AgentMessage.user("ignored by source"))));
        AgentContextSnapshot changedOrder = assembler(
                List.of(source("source", "1", 6, secondMessages))
        ).assemble(input(List.of(AgentMessage.user("ignored by source"))));

        assertEquals(first.snapshotId(), same.snapshotId());
        assertEquals(first.canonicalJson(), same.canonicalJson());
        assertNotEquals(first.createdAt(), same.createdAt());
        assertNotEquals(first.snapshotId(), changedMessage.snapshotId());
        assertNotEquals(first.snapshotId(), changedVersion.snapshotId());
        assertNotEquals(first.snapshotId(), changedOrder.snapshotId());
    }

    @Test
    void canonicalRestorePreservesExactToolMessagesAndNestedArguments() {
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("nullable", null);
        arguments.put("nested", Map.of("items", List.of(1, 2, 3), "enabled", true));
        List<AgentMessage> messages = List.of(
                AgentMessage.system("system"),
                AgentMessage.user("user"),
                AgentMessage.assistant("assistant"),
                AgentMessage.assistantToolCalls(List.of(
                        new ToolCall("call-7", "knowledge_search", arguments)
                )),
                AgentMessage.toolResult("call-7", "knowledge_search", "result")
        );
        AgentContextSnapshotFactory factory = new AgentContextSnapshotFactory(
                new ObjectMapper()
        );
        AgentContextSnapshot snapshot = new AgentContextAssembler(
                List.of(source("request", "1", 0, messages)),
                factory,
                Clock.fixed(NOW, ZoneOffset.UTC)
        ).assemble(input(List.of(AgentMessage.user("unused"))));

        AgentContextSnapshot restored = factory.restore(
                snapshot.snapshotId(), snapshot.createdAt(), snapshot.canonicalJson()
        );

        assertEquals(snapshot, restored);
        assertEquals(messages, restored.messages());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deeplyFreezesNestedToolArgumentsWithoutChangingSnapshotIdentity() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("level", 1);
        Map<String, Object> listItem = new HashMap<>();
        listItem.put("enabled", true);
        List<Object> mutableList = new ArrayList<>();
        mutableList.add(listItem);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("options", nested);
        arguments.put("items", mutableList);
        AgentContextSnapshot snapshot = assembler(List.of(source(
                "source",
                "1",
                0,
                List.of(AgentMessage.assistantToolCalls(List.of(
                        new ToolCall("call-immutable", "example_tool", arguments)
                )))
        ))).assemble(input(List.of(AgentMessage.user("unused"))));
        String canonicalJson = snapshot.canonicalJson();
        String contextHash = snapshot.contextHash();
        String snapshotId = snapshot.snapshotId();

        nested.put("level", 2);
        listItem.put("enabled", false);
        mutableList.add("late mutation");

        Map<String, Object> frozenArguments = snapshot.messages().get(0)
                .toolCalls().get(0).arguments();
        Map<String, Object> frozenNested = (Map<String, Object>) frozenArguments.get("options");
        List<Object> frozenList = (List<Object>) frozenArguments.get("items");
        Map<String, Object> frozenListItem = (Map<String, Object>) frozenList.get(0);
        assertEquals(1, frozenNested.get("level"));
        assertEquals(true, frozenListItem.get("enabled"));
        assertEquals(1, frozenList.size());
        assertEquals(canonicalJson, snapshot.canonicalJson());
        assertEquals(contextHash, snapshot.contextHash());
        assertEquals(snapshotId, snapshot.snapshotId());
        assertThrows(UnsupportedOperationException.class,
                () -> frozenNested.put("level", 3));
        assertThrows(UnsupportedOperationException.class,
                () -> frozenList.add("forbidden"));
        assertThrows(UnsupportedOperationException.class,
                () -> frozenListItem.put("enabled", false));
    }

    @Test
    void rejectsInconsistentProvenanceAndUnsupportedSchema() throws Exception {
        AgentContextSnapshotFactory factory = new AgentContextSnapshotFactory(
                new ObjectMapper()
        );
        List<AgentMessage> messages = List.of(
                AgentMessage.user("first"),
                AgentMessage.assistant("second")
        );
        ContextAssemblyInput input = input(messages);
        String firstHash = factory.contentHash(messages.subList(0, 1));
        String secondHash = factory.contentHash(messages.subList(1, 2));

        assertThrows(ContextAssemblyException.class, () -> factory.create(
                input,
                List.of(new ContextProvenance("source", "1", 0, 1, firstHash)),
                messages,
                NOW
        ));
        assertThrows(ContextAssemblyException.class, () -> factory.create(
                input,
                List.of(new ContextProvenance(
                        "source", "1", 0, 2, "0".repeat(64)
                )),
                messages,
                NOW
        ));
        assertThrows(ContextAssemblyException.class, () -> factory.create(
                input,
                List.of(
                        new ContextProvenance("first", "1", 0, 2, firstHash),
                        new ContextProvenance(
                                "second",
                                "1",
                                1,
                                0,
                                factory.contentHash(List.of())
                        )
                ),
                messages,
                NOW
        ));
        assertThrows(ContextAssemblyException.class, () -> factory.create(
                input,
                List.of(
                        new ContextProvenance("source", "1", 0, 1, firstHash),
                        new ContextProvenance("source", "1", 1, 1, secondHash)
                ),
                messages,
                NOW
        ));
        assertThrows(ContextAssemblyException.class, () -> factory.create(
                input,
                List.of(
                        new ContextProvenance("z-source", "1", 1, 1, firstHash),
                        new ContextProvenance("a-source", "1", 0, 1, secondHash)
                ),
                messages,
                NOW
        ));

        AgentContextSnapshot valid = assembler(List.of(
                source("source", "1", 0, messages)
        )).assemble(input(List.of(AgentMessage.user("unused"))));
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode unknownSchema =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                        valid.canonicalJson()
                );
        unknownSchema.put("schemaVersion", 2);
        ContextAssemblyException failure = assertThrows(
                ContextAssemblyException.class,
                () -> factory.restore(
                        "context-v2-" + "0".repeat(64),
                        valid.createdAt(),
                        mapper.writeValueAsString(unknownSchema)
                )
        );
        assertTrue(failure.getMessage().contains("Unsupported"));
    }

    @Test
    void duplicateOrInvalidSourceMetadataFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> assembler(List.of(
                source("duplicate", "1", 1, "a"),
                source("duplicate", "1", 2, "b")
        )));
        assertThrows(IllegalArgumentException.class, () -> assembler(List.of(
                source(" ", "1", 1, "bad")
        )));
        assertThrows(IllegalArgumentException.class, () -> assembler(List.of(
                source("source", " ", 1, "bad")
        )));
        assertThrows(IllegalArgumentException.class, () -> assembler(List.of(
                source("source", "1", -1, "bad")
        )));
    }

    @Test
    void sourceFailureIsFailClosedAndNeverSilentlyDropsContext() {
        ContextSource failing = new ContextSource() {
            @Override public String sourceId() { return "failing"; }
            @Override public String sourceVersion() { return "1"; }
            @Override public int order() { return 0; }
            @Override public ContextContribution load(ContextAssemblyInput input) {
                throw new IllegalStateException("source unavailable");
            }
        };

        ContextAssemblyException failure = assertThrows(
                ContextAssemblyException.class,
                () -> assembler(List.of(failing)).assemble(input(
                        List.of(AgentMessage.user("must not disappear"))
                ))
        );

        assertEquals("source unavailable", failure.getCause().getMessage());
    }

    private AgentContextAssembler assembler(List<? extends ContextSource> sources) {
        return assemblerAt(sources, NOW);
    }

    private AgentContextAssembler assemblerAt(
            List<? extends ContextSource> sources,
            Instant instant
    ) {
        return new AgentContextAssembler(
                sources,
                new AgentContextSnapshotFactory(new ObjectMapper()),
                Clock.fixed(instant, ZoneOffset.UTC)
        );
    }

    private ContextAssemblyInput input(List<AgentMessage> messages) {
        return new ContextAssemblyInput("run-1", "session-1", 42L, messages);
    }

    private List<AgentMessage> requestMessages(String content) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system("system"));
        messages.add(AgentMessage.user(content));
        return messages;
    }

    private ContextSource source(
            String id,
            String version,
            int order,
            String content
    ) {
        return source(id, version, order, List.of(AgentMessage.user(content)));
    }

    private ContextSource source(
            String id,
            String version,
            int order,
            List<AgentMessage> messages
    ) {
        return new ContextSource() {
            @Override public String sourceId() { return id; }
            @Override public String sourceVersion() { return version; }
            @Override public int order() { return order; }
            @Override public ContextContribution load(ContextAssemblyInput input) {
                return new ContextContribution(messages);
            }
        };
    }
}
