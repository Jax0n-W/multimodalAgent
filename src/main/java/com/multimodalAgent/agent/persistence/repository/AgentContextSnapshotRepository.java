package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.AgentContextSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AgentContextSnapshotRepository
        extends JpaRepository<AgentContextSnapshotEntity, String> {

    @Modifying
    @Query(value = """
            INSERT INTO agent_context_snapshots (
                snapshot_id,
                schema_version,
                run_id,
                session_id,
                user_id,
                context_hash,
                context_json,
                created_at
            ) VALUES (
                :snapshotId,
                :schemaVersion,
                :runId,
                :sessionId,
                :userId,
                :contextHash,
                :contextJson,
                :createdAt
            )
            ON DUPLICATE KEY UPDATE snapshot_id = snapshot_id
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("snapshotId") String snapshotId,
            @Param("schemaVersion") int schemaVersion,
            @Param("runId") String runId,
            @Param("sessionId") String sessionId,
            @Param("userId") Long userId,
            @Param("contextHash") String contextHash,
            @Param("contextJson") String contextJson,
            @Param("createdAt") Instant createdAt
    );
}
