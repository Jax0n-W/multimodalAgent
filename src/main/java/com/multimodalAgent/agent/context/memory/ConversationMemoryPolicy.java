package com.multimodalAgent.agent.context.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Selects the newest complete turns, then emits them oldest first. */
public final class ConversationMemoryPolicy {

    private static final Comparator<ConversationTurn> NEWEST_FIRST =
            Comparator.comparing(ConversationTurn::completedAt).reversed()
                    .thenComparing(ConversationTurn::runId, Comparator.reverseOrder());
    private static final Comparator<ConversationTurn> OLDEST_FIRST =
            Comparator.comparing(ConversationTurn::completedAt)
                    .thenComparing(ConversationTurn::runId);

    private final int maxTurns;
    private final int maxTotalChars;

    public ConversationMemoryPolicy(int maxTurns, int maxTotalChars) {
        if (maxTurns <= 0) {
            throw new IllegalArgumentException("maxTurns must be positive");
        }
        if (maxTotalChars <= 0) {
            throw new IllegalArgumentException("maxTotalChars must be positive");
        }
        this.maxTurns = maxTurns;
        this.maxTotalChars = maxTotalChars;
    }

    public int maxTurns() {
        return maxTurns;
    }

    public int maxTotalChars() {
        return maxTotalChars;
    }

    public List<ConversationTurn> select(List<ConversationTurn> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        List<ConversationTurn> ordered = new ArrayList<>(candidates);
        ordered.forEach(turn -> Objects.requireNonNull(
                turn,
                "candidates must not contain null"
        ));
        ordered.sort(NEWEST_FIRST);

        List<ConversationTurn> selected = new ArrayList<>();
        int usedChars = 0;
        for (ConversationTurn turn : ordered) {
            if (selected.size() == maxTurns) {
                break;
            }
            int turnChars = turn.characterCount();
            if (turnChars > maxTotalChars - usedChars) {
                break;
            }
            selected.add(turn);
            usedChars += turnChars;
        }
        selected.sort(OLDEST_FIRST);
        return List.copyOf(selected);
    }
}
