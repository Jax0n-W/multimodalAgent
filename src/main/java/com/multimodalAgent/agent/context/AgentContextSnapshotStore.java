package com.multimodalAgent.agent.context;

import java.util.Optional;

public interface AgentContextSnapshotStore {

    AgentContextSnapshot persistIfAbsent(AgentContextSnapshot snapshot);

    Optional<AgentContextSnapshot> findById(String snapshotId);
}
