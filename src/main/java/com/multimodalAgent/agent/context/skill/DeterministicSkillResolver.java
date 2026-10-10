package com.multimodalAgent.agent.context.skill;

import com.multimodalAgent.agent.context.ContextAssemblyInput;
import com.multimodalAgent.agent.runtime.model.AgentMessageRole;

import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Fixed keyword resolver with explicit priority and stable identity tie-breakers. */
public final class DeterministicSkillResolver implements SkillResolver {

    private static final Comparator<SkillDefinition> RESOLUTION_ORDER =
            Comparator.comparingInt(SkillDefinition::priority).reversed()
                    .thenComparing(SkillDefinition::skillId)
                    .thenComparing(SkillDefinition::version);

    private final SkillRegistry registry;

    public DeterministicSkillResolver(SkillRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public Optional<SkillDefinition> resolve(ContextAssemblyInput input) {
        Objects.requireNonNull(input, "input must not be null");
        String searchableText = input.requestMessages().stream()
                .filter(message -> message.role() == AgentMessageRole.USER)
                .map(message -> message.content().toLowerCase(Locale.ROOT))
                .reduce("", (left, right) -> left + "\n" + right);
        return registry.definitions().stream()
                .filter(definition -> definition.matchKeywords().stream()
                        .anyMatch(searchableText::contains))
                .sorted(RESOLUTION_ORDER)
                .findFirst();
    }
}
