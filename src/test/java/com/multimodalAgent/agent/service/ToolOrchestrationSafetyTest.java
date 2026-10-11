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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolOrchestrationSafetyTest {

    @Test
    void highRiskReportCreatesOneDurableIntentPerNormalizedRecipient() {
        Fixture fixture = fixture(List.of(" b@example.com ", "a@example.com", "a@example.com"));

        PsychologicalReport saved = fixture.service
                .persistReportWithNotificationIntents(highRiskReport());

        assertEquals(ToolStatus.PENDING, saved.getEmailStatus());
        ArgumentCaptor<HighRiskNotificationIntent> captor =
                ArgumentCaptor.forClass(HighRiskNotificationIntent.class);
        verify(fixture.intents, org.mockito.Mockito.times(2)).save(captor.capture());
        assertEquals(List.of("a@example.com", "b@example.com"), captor.getAllValues().stream()
                .map(HighRiskNotificationIntent::getRecipient).toList());
        assertEquals(List.of(
                HighRiskNotificationStatus.PENDING,
                HighRiskNotificationStatus.PENDING
        ), captor.getAllValues().stream().map(HighRiskNotificationIntent::getStatus).toList());
        assertNotNull(captor.getAllValues().get(0).getNotificationId());
        verify(fixture.dispatcher).dispatchAsync();
    }

    @Test
    void missingRecipientsCreatesVisiblePermanentFailureIntent() {
        Fixture fixture = fixture(List.of());

        PsychologicalReport saved = fixture.service
                .persistReportWithNotificationIntents(highRiskReport());

        assertEquals(ToolStatus.FAILED, saved.getEmailStatus());
        ArgumentCaptor<HighRiskNotificationIntent> captor =
                ArgumentCaptor.forClass(HighRiskNotificationIntent.class);
        verify(fixture.intents).save(captor.capture());
        assertEquals(HighRiskNotificationStatus.PERMANENT_FAILURE,
                captor.getValue().getStatus());
        assertEquals(ToolOrchestrationService.NO_RECIPIENT, captor.getValue().getRecipient());
        verify(fixture.alerts, never()).save(any());
    }

    private Fixture fixture(List<String> recipients) {
        ExcelReportWriter excel = mock(ExcelReportWriter.class);
        PsychologicalReportRepository reports = mock(PsychologicalReportRepository.class);
        AlertRecordRepository alerts = mock(AlertRecordRepository.class);
        HighRiskNotificationIntentRepository intents =
                mock(HighRiskNotificationIntentRepository.class);
        HighRiskNotificationDispatcher dispatcher = mock(HighRiskNotificationDispatcher.class);
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getMcp().getEmail().setRecipients(recipients);
        when(reports.save(any())).thenAnswer(invocation -> {
            PsychologicalReport report = invocation.getArgument(0);
            ReflectionTestUtils.setField(report, "id", 41L);
            return report;
        });
        final long[] alertId = {100L};
        when(alerts.save(any())).thenAnswer(invocation -> {
            AlertRecord alert = invocation.getArgument(0);
            ReflectionTestUtils.setField(alert, "id", alertId[0]++);
            return alert;
        });
        ToolOrchestrationService service = new ToolOrchestrationService(
                excel, reports, alerts, intents, properties,
                new SyncTaskExecutor(), mock(TransactionTemplate.class), dispatcher
        );
        return new Fixture(service, alerts, intents, dispatcher);
    }

    private PsychologicalReport highRiskReport() {
        PsychologicalReport report = new PsychologicalReport();
        report.setRiskLevel(RiskLevel.HIGH);
        return report;
    }

    private record Fixture(
            ToolOrchestrationService service,
            AlertRecordRepository alerts,
            HighRiskNotificationIntentRepository intents,
            HighRiskNotificationDispatcher dispatcher
    ) { }
}
