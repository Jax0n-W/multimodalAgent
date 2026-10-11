package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.HighRiskNotificationIntent;
import com.multimodalAgent.agent.domain.HighRiskNotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface HighRiskNotificationIntentRepository
        extends JpaRepository<HighRiskNotificationIntent, String> {

    @Query("select i.notificationId from HighRiskNotificationIntent i "
            + "where i.status in :statuses and i.nextAttemptAt <= :now "
            + "order by i.createdAt, i.notificationId")
    List<String> findDueIds(
            @Param("statuses") Collection<HighRiskNotificationStatus> statuses,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HighRiskNotificationIntent i set i.status = :processing, "
            + "i.leaseOwner = :owner, i.leaseExpiresAt = :leaseUntil, "
            + "i.attemptCount = i.attemptCount + 1, i.updatedAt = :now "
            + "where i.notificationId = :id and i.status in :claimable "
            + "and i.nextAttemptAt <= :now")
    int claim(
            @Param("id") String id,
            @Param("claimable") Collection<HighRiskNotificationStatus> claimable,
            @Param("processing") HighRiskNotificationStatus processing,
            @Param("owner") String owner,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil
    );

    List<HighRiskNotificationIntent> findByReportId(Long reportId);

    List<HighRiskNotificationIntent> findByStatusAndLeaseExpiresAtBefore(
            HighRiskNotificationStatus status, Instant cutoff);
}
