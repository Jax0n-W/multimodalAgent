package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.ToolStatus;
import com.multimodalAgent.agent.repository.AlertRecordRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.service.mcp.AlertNotifier;
import com.multimodalAgent.agent.service.mcp.ExcelReportWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
/**
 * 后台工具编排服务。
 *
 * <p>心理报告生成后独立记录 Excel 与高风险预警结果，避免报表故障阻断安全通知。</p>
 */
public class ToolOrchestrationService {

    private final ExcelReportWriter excelReportWriter;
    private final AlertNotifier alertNotifier;
    private final PsychologicalReportRepository reportRepository;
    private final AlertRecordRepository alertRecordRepository;
    private final multimodalAgentProperties properties;
    private final TaskExecutor mcpTaskExecutor;
    private final TransactionTemplate transactionTemplate;

    public ToolOrchestrationService(
            ExcelReportWriter excelReportWriter,
            AlertNotifier alertNotifier,
            PsychologicalReportRepository reportRepository,
            AlertRecordRepository alertRecordRepository,
            multimodalAgentProperties properties,
            @Qualifier("mcpTaskExecutor")
            TaskExecutor mcpTaskExecutor,
            TransactionTemplate transactionTemplate
    ) {
        this.excelReportWriter = excelReportWriter;
        this.alertNotifier = alertNotifier;
        this.reportRepository = reportRepository;
        this.alertRecordRepository = alertRecordRepository;
        this.properties = properties;
        this.mcpTaskExecutor = mcpTaskExecutor;
        this.transactionTemplate = transactionTemplate;
    }

    public void handleAsync(Long reportId) {
        mcpTaskExecutor.execute(() -> {
            try {
                transactionTemplate.executeWithoutResult(status -> handleInTransaction(reportId));
            } catch (Exception ignored) {
                // 工具执行失败会写入报告状态，这里吞掉异常，避免后台任务影响聊天主流程。
            }
        });
    }

    @Transactional
    public void handle(Long reportId) {
        handleInTransaction(reportId);
    }

    private void handleInTransaction(Long reportId) {
        PsychologicalReport managedReport = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));
        writeExcel(managedReport);
        // Excel is reporting infrastructure, not proof that a human received a crisis alert.
        if (managedReport.getRiskLevel() == RiskLevel.HIGH) {
            sendAlerts(managedReport);
        }
        reportRepository.save(managedReport);
    }

    private void writeExcel(PsychologicalReport report) {
        try {
            excelReportWriter.write(report);
            report.setExcelStatus(ToolStatus.SUCCESS);
        } catch (Exception exception) {
            report.setExcelStatus(ToolStatus.FAILED);
            report.setToolError(shorten(exception.getMessage()));
        }
    }

    private void sendAlerts(PsychologicalReport report) {
        if (properties.getMcp().getEmail().getRecipients().isEmpty()) {
            report.setEmailStatus(ToolStatus.FAILED);
            report.setToolError(appendError(report.getToolError(), "No alert recipient configured"));
            return;
        }
        boolean allSuccess = true;
        for (String recipient : properties.getMcp().getEmail().getRecipients()) {
            AlertRecord alertRecord = new AlertRecord();
            alertRecord.setReport(report);
            alertRecord.setRecipient(recipient);
            alertRecordRepository.save(alertRecord);

            boolean sent = false;
            int maxAttempts = Math.max(1, properties.getMcp().getEmail().getMaxRetries() + 1);
            // 每个收件人独立重试和落库，管理员后台可以看到每封通知的最终状态。
            for (int attempt = 0; attempt < maxAttempts && !sent; attempt++) {
                try {
                    alertRecord.incrementAttempts();
                    alertNotifier.notify(alertRecord, report);
                    alertRecord.setStatus(ToolStatus.SUCCESS);
                    sent = true;
                } catch (Exception exception) {
                    alertRecord.setStatus(ToolStatus.FAILED);
                    alertRecord.setErrorMessage(shorten(exception.getMessage()));
                }
            }
            alertRecordRepository.save(alertRecord);
            allSuccess = allSuccess && sent;
        }
        report.setEmailStatus(allSuccess ? ToolStatus.SUCCESS : ToolStatus.FAILED);
    }

    private String appendError(String current, String next) {
        String combined = current == null || current.isBlank() ? next : current + "; " + next;
        return shorten(combined);
    }

    private String shorten(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
