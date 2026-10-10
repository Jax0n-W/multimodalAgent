package com.multimodalAgent.agent.context.skill;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable deterministic registry of trusted Skill definitions. */
public final class SkillRegistry {

    private static final Comparator<SkillDefinition> IDENTITY_ORDER =
            Comparator.comparing(SkillDefinition::skillId)
                    .thenComparing(SkillDefinition::version);

    private final List<SkillDefinition> definitions;

    public SkillRegistry(Collection<SkillDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions must not be null");
        List<SkillDefinition> ordered = new ArrayList<>();
        for (SkillDefinition definition : definitions) {
            ordered.add(Objects.requireNonNull(
                    definition,
                    "definitions must not contain null"
            ));
        }
        ordered.sort(IDENTITY_ORDER);
        for (int index = 1; index < ordered.size(); index++) {
            SkillDefinition previous = ordered.get(index - 1);
            SkillDefinition current = ordered.get(index);
            if (previous.skillId().equals(current.skillId())
                    && previous.version().equals(current.version())) {
                throw new IllegalArgumentException(
                        "Duplicate Skill identity: " + current.skillId() + "@" + current.version()
                );
            }
        }
        this.definitions = List.copyOf(ordered);
    }

    public Optional<SkillDefinition> find(String skillId, String version) {
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("skillId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        return definitions.stream()
                .filter(definition -> definition.skillId().equals(skillId)
                        && definition.version().equals(version))
                .findFirst();
    }

    public List<SkillDefinition> definitions() {
        return definitions;
    }
}
