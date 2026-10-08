package com.multimodalAgent.agent.context;

import com.multimodalAgent.agent.runtime.model.AgentMessage;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministically assembles every model-visible initial-context contribution. */
public final class AgentContextAssembler {

    private final List<RegisteredSource> sources;
    private final AgentContextSnapshotFactory snapshotFactory;
    private final Clock clock;

    public AgentContextAssembler(
            Collection<? extends ContextSource> sources,
            AgentContextSnapshotFactory snapshotFactory,
            Clock clock
    ) {
        Objects.requireNonNull(sources, "sources must not be null");
        this.snapshotFactory = Objects.requireNonNull(
                snapshotFactory,
                "snapshotFactory must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        List<RegisteredSource> validated = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (ContextSource source : sources) {
            ContextSource item = Objects.requireNonNull(
                    source,
                    "sources must not contain null"
            );
            String sourceId = item.sourceId();
            String sourceVersion = item.sourceVersion();
            int order = item.order();
            requireMetadata(sourceId, "sourceId");
            requireMetadata(sourceVersion, "sourceVersion");
            if (order < 0) {
                throw new IllegalArgumentException("Context source order must not be negative");
            }
            String identity = sourceId + "\u0000" + sourceVersion;
            if (!identities.add(identity)) {
                throw new IllegalArgumentException(
                        "Duplicate context source identity: " + sourceId
                                + "@" + sourceVersion
                );
            }
            validated.add(new RegisteredSource(item, sourceId, sourceVersion, order));
        }
        if (validated.isEmpty()) {
            throw new IllegalArgumentException("At least one context source is required");
        }
        validated.sort(Comparator.comparingInt(RegisteredSource::order)
                .thenComparing(RegisteredSource::sourceId)
                .thenComparing(RegisteredSource::sourceVersion));
        this.sources = List.copyOf(validated);
    }

    public AgentContextSnapshot assemble(ContextAssemblyInput input) {
        Objects.requireNonNull(input, "input must not be null");
        List<ContextProvenance> provenance = new ArrayList<>();
        List<AgentMessage> messages = new ArrayList<>();
        for (RegisteredSource source : sources) {
            ContextContribution contribution;
            try {
                contribution = Objects.requireNonNull(
                        source.source().load(input),
                        "Context source returned null contribution"
                );
            } catch (RuntimeException exception) {
                throw new ContextAssemblyException(
                        "Context source failed closed: " + source.sourceId()
                                + "@" + source.sourceVersion(),
                        exception
                );
            }
            provenance.add(new ContextProvenance(
                    source.sourceId(),
                    source.sourceVersion(),
                    source.order(),
                    contribution.messages().size(),
                    snapshotFactory.contentHash(contribution.messages())
            ));
            messages.addAll(contribution.messages());
        }
        if (messages.isEmpty()) {
            throw new ContextAssemblyException("Assembled semantic context must not be empty");
        }
        return snapshotFactory.create(
                input,
                List.copyOf(provenance),
                List.copyOf(messages),
                clock.instant()
        );
    }

    private void requireMetadata(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private record RegisteredSource(
            ContextSource source,
            String sourceId,
            String sourceVersion,
            int order
    ) {
    }
}
