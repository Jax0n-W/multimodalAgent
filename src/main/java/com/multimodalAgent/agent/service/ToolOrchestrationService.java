package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.HighRiskNotificationIntent;
import com.multimodalAgent.agent.domain.HighRiskNotificationStatus;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.ToolStatus;
import com.multimodalAgent.agent.repository.AlertRecordRepository;
import com.multimodalAgent.agent.repository.HighRiskNotificationIntentRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.service.mcp.ExcelReportWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class ToolOrchestrationService {
    static final String NO_RECIPIENT = "__NO_RECIPIENT_CONFIGURED__";

    private final ExcelReportWriter excelReportWriter;
    private final PsychologicalReportRepository reports;
    private final AlertRecordRepository alerts;
    private final HighRiskNotificationIntentRepository intents;
    private final multimodalAgentProperties properties;
    private final TaskExecutor executor;
    private final TransactionTemplate transactions;
    private final HighRiskNotificationDispatcher dispatcher;

    public ToolOrchestrationService(
            ExcelReportWriter excelReportWriter,
            PsychologicalReportRepository reports,
            AlertRecordRepository alerts,
            HighRiskNotificationIntentRepository intents,
            multimodalAgentProperties properties,
            @Qualifier("mcpTaskExecutor") TaskExecutor executor,
            TransactionTemplate transactions,
            HighRiskNotificationDispatcher dispatcher
    ) {
        this.excelReportWriter = excelReportWriter;
        this.reports = reports;
        this.alerts = alerts;
        this.intents = intents;
        this.properties = properties;
        this.executor = executor;
        this.transactions = transactions;
        this.dispatcher = dispatcher;
    }

    /** Persists the report and its complete high-risk notification intent set atomically. */
    @Transactional
    public PsychologicalReport persistReportWithNotificationIntents(PsychologicalReport report) {
        PsychologicalReport saved = reports.save(report);
        if (saved.getRiskLevel() == RiskLevel.HIGH) {
            createNotificationIntents(saved);
        }
        afterCommit(() -> handleAsync(saved.getId()));
        return saved;
    }

    public void handleAsync(Long reportId) {
        executor.execute(() -> writeExcelTransaction(reportId));
        dispatcher.dispatchAsync();
    }

    public void handle(Long reportId) {
        writeExcelTransaction(reportId);
        dispatcher.drainOnce();
    }

    private void createNotificationIntents(PsychologicalReport report) {
        List<String> recipients = properties.getMcp().getEmail().getRecipients().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
        int maxAttempts = Math.max(1, properties.getMcp().getEmail().getMaxRetries() + 1);
        if (recipients.isEmpty()) {
            report.setEmailStatus(ToolStatus.FAILED);
            report.setToolError(appendError(report.getToolError(), "No alert recipient configured"));
            intents.save(intent(report.getId(), null, NO_RECIPIENT,
                    HighRiskNotificationStatus.PERMANENT_FAILURE, maxAttempts,
                    "No alert recipient configured"));
            return;
        }
        report.setEmailStatus(ToolStatus.PENDING);
        for (String recipient : recipients) {
            AlertRecord alert = new AlertRecord();
            alert.setReport(report);
            alert.setRecipient(recipient);
            alert = alerts.save(alert);
            intents.save(intent(report.getId(), alert.getId(), recipient,
                    HighRiskNotificationStatus.PENDING, maxAttempts, null));
        }
    }

    private HighRiskNotificationIntent intent(
            Long reportId, Long alertId, String recipient,
            HighRiskNotificationStatus status, int maxAttempts, String failure
    ) {
        Instant now = Instant.now();
        HighRiskNotificationIntent intent = new HighRiskNotificationIntent();
        intent.setNotificationId("hrn-" + reportId + "-" + digest(recipient));
        intent.setReportId(reportId);
        intent.setAlertRecordId(alertId);
        intent.setRecipient(recipient);
        intent.setStatus(status);
        intent.setAttemptCount(0);
        intent.setMaxAttempts(maxAttempts);
        intent.setNextAttemptAt(now);
        intent.setLastFailureReason(failure);
        intent.setCreatedAt(now);
        intent.setUpdatedAt(now);
        return intent;
    }

    private void writeExcelTransaction(Long reportId) {
        try {
            transactions.executeWithoutResult(ignored -> {
                PsychologicalReport report = reports.findById(reportId)
                        .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));
                try {
                    excelReportWriter.write(report);
                    report.setExcelStatus(ToolStatus.SUCCESS);
                } catch (RuntimeException failure) {
                    report.setExcelStatus(ToolStatus.FAILED);
                    report.setToolError(appendError(report.getToolError(), failure.getMessage()));
                }
            });
        } catch (RuntimeException ignored) {
            // Notification dispatch is independent and was kicked separately.
        }
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 24);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String appendError(String current, String next) {
        String safeNext = next == null ? "Tool execution failed" : next;
        String combined = current == null || current.isBlank() ? safeNext : current + "; " + safeNext;
        return combined.length() > 500 ? combined.substring(0, 500) : combined;
    }
}
