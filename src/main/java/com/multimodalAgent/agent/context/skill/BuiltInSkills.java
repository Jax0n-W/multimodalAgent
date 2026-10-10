package com.multimodalAgent.agent.context.skill;

import java.util.List;
import java.util.Set;

/** Trusted, compile-time Skill catalog for the minimal P11.3 contract. */
public final class BuiltInSkills {

    private BuiltInSkills() {
    }

    public static SkillRegistry registry() {
        return new SkillRegistry(List.of(new SkillDefinition(
                "sleep-guidance",
                "1",
                "General supportive sleep-hygiene guidance for students.",
                "Offer brief, practical, non-diagnostic sleep-hygiene suggestions. "
                        + "Acknowledge the student's experience, avoid medical claims, and do not "
                        + "override existing risk detection, escalation, or professional-referral "
                        + "boundaries.",
                Set.of(),
                Set.of("睡眠", "失眠", "睡不着", "insomnia", "can't sleep", "cannot sleep"),
                100
        )));
    }
}
