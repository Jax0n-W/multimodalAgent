package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.ToolReconciliationAttemptEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ToolReconciliationAttemptRepository
        extends JpaRepository<ToolReconciliationAttemptEntity, Long> {

    List<ToolReconciliationAttemptEntity> findByToolExecutionIdOrderByAttemptNoDesc(
            String toolExecutionId
    );

    Optional<ToolReconciliationAttemptEntity> findByReconciliationId(String reconciliationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select attempt
            from ToolReconciliationAttemptEntity attempt
            where attempt.reconciliationId = :reconciliationId
            """)
    Optional<ToolReconciliationAttemptEntity> findByReconciliationIdForUpdate(
            @Param("reconciliationId") String reconciliationId
    );
}
