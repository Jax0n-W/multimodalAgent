package com.multimodalAgent.agent.context.skill;

import com.multimodalAgent.agent.context.ContextAssemblyInput;
import com.multimodalAgent.agent.context.ContextContribution;
import com.multimodalAgent.agent.context.ContextSource;
import com.multimodalAgent.agent.runtime.model.AgentMessage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Adds one resolved trusted Skill as immutable system context. */
public final class SkillContextSource implements ContextSource {

    public static final String SOURCE_ID = "agent-skills";
    public static final String SOURCE_VERSION = "1";
    public static final int ORDER = 0;

    private final SkillResolver resolver;

    public SkillContextSource(SkillResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
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
        Objects.requireNonNull(input, "input must not be null");
        Optional<SkillDefinition> selected = resolver.resolve(input);
        if (selected.isEmpty()) {
            return new ContextContribution(List.of());
        }
        SkillDefinition skill = selected.orElseThrow();
        if (!input.allowedTools().containsAll(skill.requiredTools())) {
            throw new IllegalStateException(
                    "Selected Skill requires tools not authorized for this run"
            );
        }
        return new ContextContribution(List.of(AgentMessage.system(systemMessage(skill))));
    }

    private String systemMessage(SkillDefinition skill) {
        return "Trusted Skill: " + skill.skillId() + "@" + skill.version()
                + "\nInstructions:\n" + skill.instructions();
    }
}
