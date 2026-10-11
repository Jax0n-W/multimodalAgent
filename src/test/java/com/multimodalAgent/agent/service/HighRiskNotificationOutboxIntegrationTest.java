package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.HighRiskNotificationIntent;
import com.multimodalAgent.agent.domain.HighRiskNotificationStatus;
import com.multimodalAgent.agent.domain.IntentType;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.ToolStatus;
import com.multimodalAgent.agent.repository.AlertRecordRepository;
import com.multimodalAgent.agent.repository.HighRiskNotificationIntentRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.service.mcp.AlertNotifier;
import com.multimodalAgent.agent.service.mcp.DefinitelyNotDeliveredException;
import com.multimodalAgent.agent.service.mcp.ExcelReportWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-outbox;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "multimodal-agent.ai.provider=mock",
        "multimodal-agent.knowledge.use-chroma=false",
        "multimodal-agent.mcp.email.outbox-worker-enabled=false"
})
class HighRiskNotificationOutboxIntegrationTest {
    @Autowired private HighRiskNotificationDispatcher dispatcher;
    @Autowired private HighRiskNotificationStateStore stateStore;
    @Autowired private ToolOrchestrationService orchestration;
    @Autowired private HighRiskNotificationIntentRepository intents;
    @Autowired private AlertRecordRepository alerts;
    @Autowired private PsychologicalReportRepository reports;
    @Autowired private UserAccountRepository users;
    @Autowired private multimodalAgentProperties properties;

    @MockBean private AlertNotifier notifier;
    @MockBean private ExcelReportWriter excel;

    @BeforeEach
    void clean() {
        intents.deleteAll();
        alerts.deleteAll();
        reports.deleteAll();
        reset(notifier, excel);
        properties.getMcp().getEmail().setOutboxRetryDelay(Duration.ZERO);
    }

    @AfterEach
    void restoreProperties() {
        properties.getMcp().getEmail().setRecipients(List.of("counselor@example.com"));
        properties.getMcp().getEmail().setOutboxRetryDelay(Duration.ofMinutes(1));
    }

    @Test
    void concurrentWorkersClaimOneNotificationOnlyOnce() throws Exception {
        HighRiskNotificationIntent intent = pendingIntent(3);
        doAnswer(invocation -> {
            Thread.sleep(75);
            return null;
        }).when(notifier).notify(any(), any());

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(dispatcher::drainOnce);
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(dispatcher::drainOnce);
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);

        HighRiskNotificationIntent stored = intents.findById(intent.getNotificationId()).orElseThrow();
        assertEquals(HighRiskNotificationStatus.DELIVERED, stored.getStatus());
        assertEquals(1, stored.getAttemptCount());
        assertEquals(1, first.get() + second.get());
        verify(notifier, times(1)).notify(any(), any());
    }

    @Test
    void provenNoDeliveryRetriesButNeverExceedsDurableAttemptLimit() {
        HighRiskNotificationIntent intent = pendingIntent(2);
        doThrow(new DefinitelyNotDeliveredException("connection refused before send"))
                .doNothing()
                .when(notifier).notify(any(), any());

        dispatcher.drainOnce();
        assertEquals(HighRiskNotificationStatus.FAILED_RETRYABLE,
                intents.findById(intent.getNotificationId()).orElseThrow().getStatus());
        dispatcher.drainOnce();

        HighRiskNotificationIntent stored = intents.findById(intent.getNotificationId()).orElseThrow();
        assertEquals(HighRiskNotificationStatus.DELIVERED, stored.getStatus());
        assertEquals(2, stored.getAttemptCount());
        assertEquals(ToolStatus.SUCCESS,
                reports.findById(stored.getReportId()).orElseThrow().getEmailStatus());
        verify(notifier, times(2)).notify(any(), any());
    }

    @Test
    void ambiguousTransportFailureBecomesUncertainAndIsNotBlindlyRetried() {
        HighRiskNotificationIntent intent = pendingIntent(3);
        doThrow(new IllegalStateException("socket closed after write"))
                .when(notifier).notify(any(), any());

        dispatcher.drainOnce();
        dispatcher.drainOnce();

        HighRiskNotificationIntent stored = intents.findById(intent.getNotificationId()).orElseThrow();
        assertEquals(HighRiskNotificationStatus.UNCERTAIN, stored.getStatus());
        assertEquals(1, stored.getAttemptCount());
        assertEquals(ToolStatus.FAILED,
                reports.findById(stored.getReportId()).orElseThrow().getEmailStatus());
        verify(notifier, times(1)).notify(any(), any());
    }

    @Test
    void expiredProcessingLeaseIsQuarantinedInsteadOfRedelivered() {
        HighRiskNotificationIntent intent = pendingIntent(3);
        intent.setStatus(HighRiskNotificationStatus.PROCESSING);
        intent.setLeaseOwner("dead-worker");
        intent.setClaimToken("expired-processing-token");
        intent.setLeaseExpiresAt(Instant.now().minusSeconds(5));
        intents.save(intent);

        dispatcher.drainOnce();

        assertEquals(HighRiskNotificationStatus.UNCERTAIN,
                intents.findById(intent.getNotificationId()).orElseThrow().getStatus());
        verifyNoInteractions(notifier);
    }

    @Test
    void expiredClaimBeforeExternalHandoffIsSafelyRetryable() {
        HighRiskNotificationIntent intent = pendingIntent(3);
        intent.setStatus(HighRiskNotificationStatus.CLAIMED);
        intent.setAttemptCount(1);
        intent.setLeaseOwner("dead-worker");
        intent.setClaimToken("expired-token");
        intent.setLeaseExpiresAt(Instant.now().minusSeconds(5));
        intents.save(intent);

        stateStore.quarantineExpiredLeases(Instant.now());

        HighRiskNotificationIntent stored = intents.findById(intent.getNotificationId())
                .orElseThrow();
        assertEquals(HighRiskNotificationStatus.FAILED_RETRYABLE, stored.getStatus());
        assertEquals(1, stored.getAttemptCount());
        verifyNoInteractions(notifier);
    }

    @Test
    void staleClaimCannotMutateReclaimedAttemptWithSameOwner() {
        HighRiskNotificationIntent intent = pendingIntent(3);
        String stableOwner = "same-dispatcher";
        Instant firstClaimedAt = Instant.now();
        var staleClaim = stateStore.claim(
                intent.getNotificationId(), stableOwner, firstClaimedAt, Duration.ofSeconds(30)
        ).orElseThrow();

        HighRiskNotificationIntent expired = intents.findById(intent.getNotificationId())
                .orElseThrow();
        expired.setLeaseExpiresAt(firstClaimedAt.minusMillis(1));
        intents.saveAndFlush(expired);
        stateStore.quarantineExpiredLeases(firstClaimedAt);

        var currentClaim = stateStore.claim(
                intent.getNotificationId(), stableOwner, firstClaimedAt.plusSeconds(1),
                Duration.ofMinutes(1)
        ).orElseThrow();
        assertNotEquals(staleClaim.claimToken(), currentClaim.claimToken());

        assertThrows(IllegalStateException.class,
                () -> stateStore.beginDelivery(staleClaim, firstClaimedAt.plusSeconds(2)));
        stateStore.beginDelivery(currentClaim, firstClaimedAt.plusSeconds(2));
        assertThrows(IllegalStateException.class,
                () -> stateStore.delivered(staleClaim, firstClaimedAt.plusSeconds(3)));
        assertThrows(IllegalStateException.class,
                () -> stateStore.uncertain(
                        staleClaim, "stale attempt", firstClaimedAt.plusSeconds(3)));
        assertThrows(IllegalStateException.class,
                () -> stateStore.definitelyNotDelivered(
                        staleClaim, "stale attempt", firstClaimedAt.plusSeconds(3), Duration.ZERO));

        HighRiskNotificationIntent processing = intents.findById(intent.getNotificationId())
                .orElseThrow();
        assertEquals(HighRiskNotificationStatus.PROCESSING, processing.getStatus());
        assertEquals(currentClaim.claimToken(), processing.getClaimToken());

        stateStore.delivered(currentClaim, firstClaimedAt.plusSeconds(4));
        assertEquals(HighRiskNotificationStatus.DELIVERED,
                intents.findById(intent.getNotificationId()).orElseThrow().getStatus());
    }

    @Test
    void reportAndIntentRollbackTogetherWhenIntentCannotBePersisted() {
        long before = reports.count();
        properties.getMcp().getEmail().setRecipients(List.of("x".repeat(241)));

        assertThrows(DataIntegrityViolationException.class,
                () -> orchestration.persistReportWithNotificationIntents(report()));

        assertEquals(before, reports.count());
        assertEquals(0, intents.count());
    }

    @Test
    void afterCommitDispatchObservesDurableReportAndIntent() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        AtomicBoolean durableBeforeDelivery = new AtomicBoolean();
        properties.getMcp().getEmail().setRecipients(List.of("after-commit@example.com"));
        doAnswer(invocation -> {
            AlertRecord alert = invocation.getArgument(0);
            durableBeforeDelivery.set(
                    reports.existsById(alert.getReport().getId())
                            && intents.findByReportId(alert.getReport().getId()).size() == 1
            );
            called.countDown();
            return null;
        }).when(notifier).notify(any(), any());

        PsychologicalReport saved = orchestration.persistReportWithNotificationIntents(report());

        org.junit.jupiter.api.Assertions.assertTrue(called.await(10, TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertTrue(durableBeforeDelivery.get());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline
                && intents.findByReportId(saved.getId()).get(0).getStatus()
                != HighRiskNotificationStatus.DELIVERED) {
            Thread.onSpinWait();
        }
        assertEquals(HighRiskNotificationStatus.DELIVERED,
                intents.findByReportId(saved.getId()).get(0).getStatus());
    }

    @Test
    void pendingIntentSurvivesSchedulingGapAndLaterScannerCompletesIt() {
        HighRiskNotificationIntent intent = pendingIntent(3);

        int handled = dispatcher.drainOnce();

        assertEquals(1, handled);
        assertEquals(HighRiskNotificationStatus.DELIVERED,
                intents.findById(intent.getNotificationId()).orElseThrow().getStatus());
    }

    @Test
    void excelFailureDoesNotBlockHighRiskNotification() throws Exception {
        doThrow(new IllegalStateException("excel unavailable")).when(excel).write(any());
        PsychologicalReport saved = orchestration.persistReportWithNotificationIntents(report());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var intent = intents.findByReportId(saved.getId());
            var report = reports.findById(saved.getId()).orElseThrow();
            if (!intent.isEmpty()
                    && intent.get(0).getStatus() == HighRiskNotificationStatus.DELIVERED
                    && report.getExcelStatus() == ToolStatus.FAILED) {
                break;
            }
            Thread.onSpinWait();
        }
        assertEquals(HighRiskNotificationStatus.DELIVERED,
                intents.findByReportId(saved.getId()).get(0).getStatus());
        assertEquals(ToolStatus.FAILED,
                reports.findById(saved.getId()).orElseThrow().getExcelStatus());
    }

    @Test
    void multipleRecipientsPreservePartialSuccessAndUncertainOutcome() {
        PsychologicalReport report = reports.save(report());
        HighRiskNotificationIntent first = pendingIntent(report, "a@example.com", 3);
        HighRiskNotificationIntent second = pendingIntent(report, "b@example.com", 3);
        doAnswer(invocation -> {
            AlertRecord alert = invocation.getArgument(0);
            if ("b@example.com".equals(alert.getRecipient())) {
                throw new IllegalStateException("ambiguous provider outcome");
            }
            return null;
        }).when(notifier).notify(any(), any());

        dispatcher.drainOnce();

        assertEquals(HighRiskNotificationStatus.DELIVERED,
                intents.findById(first.getNotificationId()).orElseThrow().getStatus());
        assertEquals(HighRiskNotificationStatus.UNCERTAIN,
                intents.findById(second.getNotificationId()).orElseThrow().getStatus());
        assertEquals(ToolStatus.FAILED,
                reports.findById(report.getId()).orElseThrow().getEmailStatus());
    }

    private HighRiskNotificationIntent pendingIntent(int maxAttempts) {
        PsychologicalReport report = reports.save(report());
        return pendingIntent(report, "counselor@example.com", maxAttempts);
    }

    private HighRiskNotificationIntent pendingIntent(
            PsychologicalReport report, String recipient, int maxAttempts
    ) {
        AlertRecord alert = new AlertRecord();
        alert.setReport(report);
        alert.setRecipient(recipient);
        alert = alerts.save(alert);
        Instant now = Instant.now();
        HighRiskNotificationIntent intent = new HighRiskNotificationIntent();
        intent.setNotificationId("test-" + UUID.randomUUID());
        intent.setReportId(report.getId());
        intent.setAlertRecordId(alert.getId());
        intent.setRecipient(alert.getRecipient());
        intent.setStatus(HighRiskNotificationStatus.PENDING);
        intent.setAttemptCount(0);
        intent.setMaxAttempts(maxAttempts);
        intent.setNextAttemptAt(now.minusMillis(1));
        intent.setCreatedAt(now);
        intent.setUpdatedAt(now);
        return intents.save(intent);
    }

    private PsychologicalReport report() {
        PsychologicalReport report = new PsychologicalReport();
        report.setUser(users.findByUsername("student").orElseThrow());
        report.setContent("high-risk report");
        report.setIntent(IntentType.RISK);
        report.setEmotion(EmotionLabel.HIGH_RISK);
        report.setEmotionScore(4.8);
        report.setRiskLevel(RiskLevel.HIGH);
        report.setConfidence(0.95);
        report.setSummary("requires human review");
        report.setEmailStatus(ToolStatus.PENDING);
        return report;
    }
}
