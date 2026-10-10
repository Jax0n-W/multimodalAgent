package com.multimodalAgent.agent.context.skill;

import com.multimodalAgent.agent.context.ContextAssemblyInput;
import com.multimodalAgent.agent.runtime.model.AgentMessageRole;
import com.multimodalAgent.agent.service.ai.RiskLexicon;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Fixed keyword resolver with explicit priority and stable identity tie-breakers. */
public final class DeterministicSkillResolver implements SkillResolver {

    private static final List<String> NEGATION_PREFIXES = List.of(
            "并没有", "并未", "没有", "不是", "并不", "从未", "不再", "没"
    );

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
        if (RiskLexicon.hasHighRiskSignal(searchableText)) {
            return Optional.empty();
        }
        return registry.definitions().stream()
                .filter(definition -> definition.matchKeywords().stream()
                        .anyMatch(keyword -> isAffirmativeSupportUse(searchableText, keyword)))
                .sorted(RESOLUTION_ORDER)
                .findFirst();
    }

    private boolean isAffirmativeSupportUse(String text, String keyword) {
        int fromIndex = 0;
        while (fromIndex < text.length()) {
            int index = text.indexOf(keyword, fromIndex);
            if (index < 0) {
                return false;
            }
            if (!isNegated(text, keyword, index) && !isKnowledgeQuestion(text, keyword)) {
                return true;
            }
            fromIndex = index + keyword.length();
        }
        return false;
    }

    private boolean isNegated(String text, String keyword, int index) {
        String prefix = text.substring(Math.max(0, index - 8), index).stripTrailing();
        if (NEGATION_PREFIXES.stream().anyMatch(prefix::endsWith)) {
            return true;
        }
        return text.contains("do not have " + keyword)
                || text.contains("don't have " + keyword)
                || text.contains("not experiencing " + keyword)
                || text.contains("no " + keyword);
    }

    private boolean isKnowledgeQuestion(String text, String keyword) {
        return text.contains(keyword + "是什么意思")
                || text.contains("什么是" + keyword)
                || text.contains("请解释" + keyword)
                || text.contains("解释一下" + keyword)
                || text.contains(keyword + "这个概念")
                || text.contains(keyword + "的定义")
                || text.contains("what is " + keyword)
                || text.contains("define " + keyword);
    }
}
