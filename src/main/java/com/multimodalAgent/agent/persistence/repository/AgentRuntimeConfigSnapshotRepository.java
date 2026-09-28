package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.AgentRuntimeConfigSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AgentRuntimeConfigSnapshotRepository
        extends JpaRepository<AgentRuntimeConfigSnapshotEntity, String> {

    @Modifying
    @Query(value = """
            INSERT INTO agent_runtime_config_snapshots (
                snapshot_id,
                schema_version,
                config_hash,
                config_json,
                created_at
            ) VALUES (
                :snapshotId,
                :schemaVersion,
                :configHash,
                :configJson,
                :createdAt
            )
            ON DUPLICATE KEY UPDATE snapshot_id = snapshot_id
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("snapshotId") String snapshotId,
            @Param("schemaVersion") int schemaVersion,
            @Param("configHash") String configHash,
            @Param("configJson") String configJson,
            @Param("createdAt") Instant createdAt
    );
}
