package com.multimodalAgent.agent.context.skill;

import com.multimodalAgent.agent.context.ContextAssemblyInput;

import java.util.Optional;

/** Deterministic, model-free selection port for at most one trusted Skill. */
@FunctionalInterface
public interface SkillResolver {

    Optional<SkillDefinition> resolve(ContextAssemblyInput input);
}
