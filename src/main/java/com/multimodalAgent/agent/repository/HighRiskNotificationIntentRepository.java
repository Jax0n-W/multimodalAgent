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
            + "i.leaseOwner = :owner, i.claimToken = :claimToken, "
            + "i.leaseExpiresAt = :leaseUntil, "
            + "i.attemptCount = i.attemptCount + 1, i.updatedAt = :now "
            + "where i.notificationId = :id and i.status in :claimable "
            + "and i.nextAttemptAt <= :now")
    int claim(
            @Param("id") String id,
            @Param("claimable") Collection<HighRiskNotificationStatus> claimable,
            @Param("processing") HighRiskNotificationStatus processing,
            @Param("owner") String owner,
            @Param("claimToken") String claimToken,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HighRiskNotificationIntent i set i.status = :processing, i.updatedAt = :now "
            + "where i.notificationId = :id and i.status = :claimed "
            + "and i.leaseOwner = :owner and i.claimToken = :claimToken")
    int beginDelivery(
            @Param("id") String id,
            @Param("claimed") HighRiskNotificationStatus claimed,
            @Param("processing") HighRiskNotificationStatus processing,
            @Param("owner") String owner,
            @Param("claimToken") String claimToken,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HighRiskNotificationIntent i set i.status = :delivered, "
            + "i.leaseOwner = null, i.claimToken = null, i.leaseExpiresAt = null, "
            + "i.updatedAt = :now "
            + "where i.notificationId = :id and i.status = :processing "
            + "and i.leaseOwner = :owner and i.claimToken = :claimToken")
    int markDelivered(
            @Param("id") String id,
            @Param("processing") HighRiskNotificationStatus processing,
            @Param("delivered") HighRiskNotificationStatus delivered,
            @Param("owner") String owner,
            @Param("claimToken") String claimToken,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HighRiskNotificationIntent i set i.status = :target, "
            + "i.nextAttemptAt = :nextAttemptAt, i.lastFailureReason = :reason, "
            + "i.leaseOwner = null, i.claimToken = null, i.leaseExpiresAt = null, "
            + "i.updatedAt = :now "
            + "where i.notificationId = :id and i.status = :processing "
            + "and i.leaseOwner = :owner and i.claimToken = :claimToken")
    int finishProcessing(
            @Param("id") String id,
            @Param("processing") HighRiskNotificationStatus processing,
            @Param("target") HighRiskNotificationStatus target,
            @Param("owner") String owner,
            @Param("claimToken") String claimToken,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("reason") String reason,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HighRiskNotificationIntent i set i.status = :target, "
            + "i.nextAttemptAt = :nextAttemptAt, i.lastFailureReason = :reason, "
            + "i.leaseOwner = null, i.claimToken = null, i.leaseExpiresAt = null, "
            + "i.updatedAt = :now "
            + "where i.notificationId = :id and i.status = :claimed "
            + "and i.claimToken = :claimToken and i.leaseExpiresAt < :now")
    int quarantineClaimed(
            @Param("id") String id,
            @Param("claimed") HighRiskNotificationStatus claimed,
            @Param("target") HighRiskNotificationStatus target,
            @Param("claimToken") String claimToken,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("reason") String reason,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HighRiskNotificationIntent i set i.status = :uncertain, "
            + "i.lastFailureReason = :reason, i.leaseOwner = null, "
            + "i.claimToken = null, i.leaseExpiresAt = null, i.updatedAt = :now "
            + "where i.notificationId = :id and i.status = :processing "
            + "and i.claimToken = :claimToken and i.leaseExpiresAt < :now")
    int quarantineProcessing(
            @Param("id") String id,
            @Param("processing") HighRiskNotificationStatus processing,
            @Param("uncertain") HighRiskNotificationStatus uncertain,
            @Param("claimToken") String claimToken,
            @Param("reason") String reason,
            @Param("now") Instant now
    );

    List<HighRiskNotificationIntent> findByReportId(Long reportId);

    List<HighRiskNotificationIntent> findByStatusAndLeaseExpiresAtBefore(
            HighRiskNotificationStatus status, Instant cutoff);
}
