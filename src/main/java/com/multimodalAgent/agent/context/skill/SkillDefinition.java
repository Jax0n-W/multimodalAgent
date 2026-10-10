package com.multimodalAgent.agent.context.skill;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Immutable, trusted server-side definition of one deterministic Skill version. */
public record SkillDefinition(
        String skillId,
        String version,
        String description,
        String instructions,
        Set<String> requiredTools,
        Set<String> matchKeywords,
        int priority
) {

    public static final int MAX_DESCRIPTION_LENGTH = 512;
    public static final int MAX_INSTRUCTIONS_LENGTH = 4000;
    public static final int MAX_KEYWORD_LENGTH = 100;
    private static final Pattern SKILL_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");

    public SkillDefinition {
        requireMatch(skillId, SKILL_ID, "skillId");
        requireMatch(version, VERSION, "version");
        requireText(description, "description");
        requireText(instructions, "instructions");
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("description exceeds maximum length");
        }
        if (instructions.length() > MAX_INSTRUCTIONS_LENGTH) {
            throw new IllegalArgumentException("instructions exceeds maximum length");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("priority must not be negative");
        }
        requiredTools = immutableSortedValues(requiredTools, "requiredTools", false);
        matchKeywords = immutableSortedValues(matchKeywords, "matchKeywords", true);
    }

    private static Set<String> immutableSortedValues(
            Set<String> values,
            String field,
            boolean keyword
    ) {
        Objects.requireNonNull(values, field + " must not be null");
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            requireText(value, field + " value");
            String normalized = keyword
                    ? value.strip().toLowerCase(java.util.Locale.ROOT)
                    : value;
            if (keyword && normalized.length() > MAX_KEYWORD_LENGTH) {
                throw new IllegalArgumentException("match keyword exceeds maximum length");
            }
            sorted.add(normalized);
        }
        if (keyword && sorted.isEmpty()) {
            throw new IllegalArgumentException("matchKeywords must not be empty");
        }
        return Collections.unmodifiableSet(sorted);
    }

    private static void requireMatch(String value, Pattern pattern, String field) {
        requireText(value, field);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " has an invalid format");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
