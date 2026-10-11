package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.HighRiskNotificationIntent;
import com.multimodalAgent.agent.domain.HighRiskNotificationStatus;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.ToolStatus;
import com.multimodalAgent.agent.repository.AlertRecordRepository;
import com.multimodalAgent.agent.repository.HighRiskNotificationIntentRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class HighRiskNotificationStateStore {
    private static final EnumSet<HighRiskNotificationStatus> CLAIMABLE = EnumSet.of(
            HighRiskNotificationStatus.PENDING,
            HighRiskNotificationStatus.FAILED_RETRYABLE
    );

    private final HighRiskNotificationIntentRepository intents;
    private final AlertRecordRepository alerts;
    private final PsychologicalReportRepository reports;

    public HighRiskNotificationStateStore(
            HighRiskNotificationIntentRepository intents,
            AlertRecordRepository alerts,
            PsychologicalReportRepository reports
    ) {
        this.intents = intents;
        this.alerts = alerts;
        this.reports = reports;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<String> dueIds(Instant now, int limit) {
        return intents.findDueIds(CLAIMABLE, now).stream().limit(limit).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedNotification> claim(
            String id, String owner, Instant now, Duration leaseDuration
    ) {
        String claimToken = UUID.randomUUID().toString();
        int claimed = intents.claim(
                id, CLAIMABLE, HighRiskNotificationStatus.CLAIMED,
                owner, claimToken, now, now.plus(leaseDuration)
        );
        if (claimed != 1) {
            return Optional.empty();
        }
        HighRiskNotificationIntent intent = intents.findById(id).orElseThrow();
        return Optional.of(new ClaimedNotification(
                intent.getNotificationId(), intent.getReportId(), intent.getAlertRecordId(),
                intent.getRecipient(), intent.getAttemptCount(), intent.getMaxAttempts(), owner,
                claimToken
        ));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void beginDelivery(ClaimedNotification claim, Instant now) {
        requireClaimTransition(intents.beginDelivery(
                claim.notificationId(), HighRiskNotificationStatus.CLAIMED,
                HighRiskNotificationStatus.PROCESSING, claim.owner(), claim.claimToken(), now
        ), claim);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delivered(ClaimedNotification claim, Instant now) {
        requireClaimTransition(intents.markDelivered(
                claim.notificationId(), HighRiskNotificationStatus.PROCESSING,
                HighRiskNotificationStatus.DELIVERED, claim.owner(), claim.claimToken(), now
        ), claim);
        HighRiskNotificationIntent intent = intents.findById(claim.notificationId()).orElseThrow();
        updateAlert(intent, ToolStatus.SUCCESS, null);
        updateReportStatus(intent.getReportId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void definitelyNotDelivered(
            ClaimedNotification claim, String reason, Instant now, Duration retryDelay
    ) {
        boolean exhausted = claim.attemptCount() >= claim.maxAttempts();
        HighRiskNotificationStatus target = exhausted
                ? HighRiskNotificationStatus.PERMANENT_FAILURE
                : HighRiskNotificationStatus.FAILED_RETRYABLE;
        requireClaimTransition(intents.finishProcessing(
                claim.notificationId(), HighRiskNotificationStatus.PROCESSING, target,
                claim.owner(), claim.claimToken(), now.plus(retryDelay), shorten(reason), now
        ), claim);
        HighRiskNotificationIntent intent = intents.findById(claim.notificationId()).orElseThrow();
        updateAlert(intent, ToolStatus.FAILED, reason);
        updateReportStatus(intent.getReportId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void uncertain(ClaimedNotification claim, String reason, Instant now) {
        requireClaimTransition(intents.finishProcessing(
                claim.notificationId(), HighRiskNotificationStatus.PROCESSING,
                HighRiskNotificationStatus.UNCERTAIN, claim.owner(), claim.claimToken(), now,
                shorten(reason), now
        ), claim);
        HighRiskNotificationIntent intent = intents.findById(claim.notificationId()).orElseThrow();
        updateAlert(intent, ToolStatus.FAILED, "Delivery outcome uncertain: " + reason);
        updateReportStatus(intent.getReportId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int quarantineExpiredLeases(Instant now) {
        List<HighRiskNotificationIntent> expiredClaims =
                intents.findByStatusAndLeaseExpiresAtBefore(
                        HighRiskNotificationStatus.CLAIMED, now
                );
        for (HighRiskNotificationIntent intent : expiredClaims) {
            boolean exhausted = intent.getAttemptCount() >= intent.getMaxAttempts();
            HighRiskNotificationStatus target = exhausted
                    ? HighRiskNotificationStatus.PERMANENT_FAILURE
                    : HighRiskNotificationStatus.FAILED_RETRYABLE;
            String reason = "Worker lease expired before external delivery handoff";
            int transitioned = intents.quarantineClaimed(
                    intent.getNotificationId(), HighRiskNotificationStatus.CLAIMED, target,
                    intent.getClaimToken(), now, reason, now
            );
            if (transitioned == 1) {
                HighRiskNotificationIntent quarantined = intents.findById(
                        intent.getNotificationId()).orElseThrow();
                updateAlert(quarantined, ToolStatus.FAILED, reason);
                updateReportStatus(quarantined.getReportId());
            }
        }
        List<HighRiskNotificationIntent> expired = intents.findByStatusAndLeaseExpiresAtBefore(
                HighRiskNotificationStatus.PROCESSING, now
        );
        for (HighRiskNotificationIntent intent : expired) {
            String reason = "Delivery worker lease expired; external outcome is unknown";
            int transitioned = intents.quarantineProcessing(
                    intent.getNotificationId(), HighRiskNotificationStatus.PROCESSING,
                    HighRiskNotificationStatus.UNCERTAIN, intent.getClaimToken(), reason, now
            );
            if (transitioned == 1) {
                HighRiskNotificationIntent quarantined = intents.findById(
                        intent.getNotificationId()).orElseThrow();
                updateAlert(quarantined, ToolStatus.FAILED, reason);
                updateReportStatus(quarantined.getReportId());
            }
        }
        return expiredClaims.size() + expired.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public DeliveryMaterial load(ClaimedNotification claim) {
        PsychologicalReport report = reports.findWithUserById(claim.reportId()).orElseThrow();
        AlertRecord alert = alerts.findById(claim.alertRecordId()).orElseThrow();
        return new DeliveryMaterial(alert, report);
    }

    private void requireClaimTransition(int transitioned, ClaimedNotification claim) {
        if (transitioned != 1) {
            throw new IllegalStateException("Notification claim is no longer owned: "
                    + claim.notificationId());
        }
    }

    private void updateAlert(HighRiskNotificationIntent intent, ToolStatus status, String error) {
        if (intent.getAlertRecordId() == null) {
            return;
        }
        AlertRecord alert = alerts.findById(intent.getAlertRecordId()).orElseThrow();
        alert.incrementAttempts();
        alert.setStatus(status);
        alert.setErrorMessage(error == null ? null : shorten(error));
    }

    private void updateReportStatus(Long reportId) {
        PsychologicalReport report = reports.findById(reportId).orElseThrow();
        List<HighRiskNotificationIntent> reportIntents = intents.findByReportId(reportId);
        boolean allDelivered = !reportIntents.isEmpty() && reportIntents.stream()
                .allMatch(item -> item.getStatus() == HighRiskNotificationStatus.DELIVERED);
        boolean terminalFailure = reportIntents.stream().anyMatch(item ->
                item.getStatus() == HighRiskNotificationStatus.UNCERTAIN
                        || item.getStatus() == HighRiskNotificationStatus.PERMANENT_FAILURE);
        report.setEmailStatus(allDelivered
                ? ToolStatus.SUCCESS
                : terminalFailure ? ToolStatus.FAILED : ToolStatus.PENDING);
        if (terminalFailure) {
            report.setToolError(shorten(reportIntents.stream()
                    .filter(item -> item.getLastFailureReason() != null)
                    .map(HighRiskNotificationIntent::getLastFailureReason)
                    .findFirst()
                    .orElse("High-risk notification failed")));
        }
    }

    private String shorten(String value) {
        if (value == null) return "";
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    public record ClaimedNotification(
            String notificationId,
            Long reportId,
            Long alertRecordId,
            String recipient,
            int attemptCount,
            int maxAttempts,
            String owner,
            String claimToken
    ) { }

    public record DeliveryMaterial(AlertRecord alert, PsychologicalReport report) { }
}
