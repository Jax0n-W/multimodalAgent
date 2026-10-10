package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.ToolStatus;
import com.multimodalAgent.agent.repository.AlertRecordRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.service.mcp.AlertNotifier;
import com.multimodalAgent.agent.service.mcp.ExcelReportWriter;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolOrchestrationSafetyTest {

    @Test
    void excelFailureDoesNotMasqueradeAsOrBlockHighRiskNotification() {
        ExcelReportWriter excel = mock(ExcelReportWriter.class);
        AlertNotifier notifier = mock(AlertNotifier.class);
        PsychologicalReportRepository reports = mock(PsychologicalReportRepository.class);
        AlertRecordRepository alerts = mock(AlertRecordRepository.class);
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getMcp().getEmail().setRecipients(List.of("counselor@example.com"));
        PsychologicalReport report = new PsychologicalReport();
        report.setRiskLevel(RiskLevel.HIGH);
        when(reports.findById(7L)).thenReturn(Optional.of(report));
        when(alerts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException("excel unavailable")).when(excel).write(report);

        ToolOrchestrationService service = new ToolOrchestrationService(
                excel, notifier, reports, alerts, properties,
                new SyncTaskExecutor(), mock(TransactionTemplate.class)
        );
        service.handle(7L);

        assertEquals(ToolStatus.FAILED, report.getExcelStatus());
        assertEquals(ToolStatus.SUCCESS, report.getEmailStatus());
        verify(notifier).notify(any(), org.mockito.Mockito.eq(report));
        verify(reports).save(report);
    }

    @Test
    void missingRecipientsCannotBeRecordedAsSuccessfulNotification() {
        ExcelReportWriter excel = mock(ExcelReportWriter.class);
        AlertNotifier notifier = mock(AlertNotifier.class);
        PsychologicalReportRepository reports = mock(PsychologicalReportRepository.class);
        AlertRecordRepository alerts = mock(AlertRecordRepository.class);
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getMcp().getEmail().setRecipients(List.of());
        PsychologicalReport report = new PsychologicalReport();
        report.setRiskLevel(RiskLevel.HIGH);
        when(reports.findById(8L)).thenReturn(Optional.of(report));

        ToolOrchestrationService service = new ToolOrchestrationService(
                excel, notifier, reports, alerts, properties,
                new SyncTaskExecutor(), mock(TransactionTemplate.class)
        );
        service.handle(8L);

        assertEquals(ToolStatus.SUCCESS, report.getExcelStatus());
        assertEquals(ToolStatus.FAILED, report.getEmailStatus());
        verify(notifier, never()).notify(any(), any());
        verify(alerts, never()).save(any());
        verify(reports).save(report);
    }
}
