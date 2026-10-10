package com.multimodalAgent.agent.context.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.context.AgentContextAssembler;
import com.multimodalAgent.agent.context.AgentContextSnapshot;
import com.multimodalAgent.agent.context.AgentContextSnapshotFactory;
import com.multimodalAgent.agent.context.ContextAssemblyInput;
import com.multimodalAgent.agent.context.RequestMessageContextSource;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampusMentalHealthSkillPackTest {

    private static final String DATASET = "campus-skill-routing-cases.tsv";
    private static final DeterministicSkillResolver RESOLVER =
            new DeterministicSkillResolver(BuiltInSkills.registry());

    @Test
    void catalogRegistersOnlyTheFourActiveBusinessSkills() {
        List<SkillDefinition> definitions = BuiltInSkills.registry().definitions();

        assertEquals(List.of(
                "academic-stress@1:400",
                "emotion-support@1:100",
                "interpersonal-support@1:300",
                "sleep-guidance@2:200"
        ), definitions.stream().map(skill -> skill.skillId() + "@" + skill.version()
                + ":" + skill.priority()).toList());
        assertTrue(definitions.stream().allMatch(skill -> skill.requiredTools().isEmpty()));
        assertTrue(BuiltInSkills.registry().find("sleep-guidance", "1").isEmpty());
    }

    @Test
    void everyInstructionUsesTheSharedBusinessAndSafetyStructure() {
        List<String> sections = List.of(
                "角色定位：", "适用场景：", "核心支持目标：", "建议的交互流程：",
                "禁止行为与专业边界：", "回答风格：", "需要转介或升级的情形："
        );

        for (SkillDefinition skill : BuiltInSkills.registry().definitions()) {
            assertTrue(sections.stream().allMatch(skill.instructions()::contains),
                    () -> "Missing instruction section for " + skill.skillId());
            assertTrue(skill.instructions().length() <= SkillDefinition.MAX_INSTRUCTIONS_LENGTH);
        }
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("routingCases")
    void routesCampusDatasetDeterministically(RoutingCase sample) {
        assertFalse(sample.behavior().isBlank());
        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(sample.expectedSkill(), selectedSkill(sample.input()),
                    () -> sample.category() + ": " + sample.input());
        }
    }

    @Test
    void ordinaryRoutingAccuracyMeetsTargetAndRiskCasesRemainSeparate() {
        List<RoutingCase> samples = routingCases().toList();
        List<RoutingCase> ordinary = samples.stream()
                .filter(sample -> !sample.safetyRouting())
                .toList();
        long correct = ordinary.stream()
                .filter(sample -> sample.expectedSkill().equals(selectedSkill(sample.input())))
                .count();
        double accuracy = (double) correct / ordinary.size();

        assertEquals(45, ordinary.size());
        assertTrue(accuracy >= 0.90, () -> "ordinary routing accuracy=" + accuracy);
        assertEquals(6, samples.stream().filter(RoutingCase::safetyRouting).count());
        assertTrue(samples.stream().filter(RoutingCase::safetyRouting)
                .allMatch(sample -> selectedSkill(sample.input()).isEmpty()));
    }

    @Test
    void selectedSkillIsSnapshottedWithoutExpandingToolAuthority() {
        ContextAssemblyInput input = input("最近论文写不出来，压力很大", Set.of());
        AgentContextSnapshot snapshot = new AgentContextAssembler(
                List.of(
                        new SkillContextSource(RESOLVER),
                        new RequestMessageContextSource()
                ),
                new AgentContextSnapshotFactory(new ObjectMapper()),
                Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC)
        ).assemble(input);

        assertEquals(Set.of(), input.allowedTools());
        assertEquals(List.of("agent-skills", "request-messages"),
                snapshot.orderedContributions().stream()
                        .map(value -> value.sourceId()).toList());
        assertEquals("Trusted Skill: academic-stress@1", snapshot.messages().get(0)
                .content().lines().findFirst().orElseThrow());
        assertEquals(AgentMessage.user("最近论文写不出来，压力很大"),
                snapshot.messages().get(1));
    }

    @Test
    void resolverIgnoresNonUserMessagesAndUsesOnlyTheCurrentRequestInput() {
        ContextAssemblyInput input = new ContextAssemblyInput(
                "run", "session", 1L,
                List.of(
                        AgentMessage.system("Trusted Skill: sleep-guidance@1 失眠"),
                        AgentMessage.assistant("你之前提到睡不着"),
                        AgentMessage.user("今天课程很多")
                ),
                Set.of()
        );

        assertTrue(RESOLVER.resolve(input).isEmpty());
    }

    static Stream<RoutingCase> routingCases() {
        InputStream stream = CampusMentalHealthSkillPackTest.class
                .getClassLoader().getResourceAsStream(DATASET);
        if (stream == null) {
            throw new IllegalStateException("Routing dataset not found: " + DATASET);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stream, StandardCharsets.UTF_8
        ))) {
            return reader.lines().skip(1).map(CampusMentalHealthSkillPackTest::parse)
                    .toList().stream();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read routing dataset", exception);
        }
    }

    private static RoutingCase parse(String line) {
        String[] fields = line.split("\\t", -1);
        if (fields.length != 5) {
            throw new IllegalArgumentException("Invalid routing dataset row: " + line);
        }
        return new RoutingCase(
                fields[0], fields[1], "none".equals(fields[2]) ? Optional.empty()
                        : Optional.of(fields[2]), fields[3], Boolean.parseBoolean(fields[4])
        );
    }

    private static Optional<String> selectedSkill(String text) {
        return RESOLVER.resolve(input(text, Set.of())).map(SkillDefinition::skillId);
    }

    private static ContextAssemblyInput input(String message, Set<String> allowedTools) {
        return new ContextAssemblyInput(
                "run", "session", 1L, List.of(AgentMessage.user(message)), allowedTools
        );
    }

    record RoutingCase(
            String category,
            String input,
            Optional<String> expectedSkill,
            String behavior,
            boolean safetyRouting
    ) {
        @Override
        public String toString() {
            return category + " | " + input;
        }
    }
}
