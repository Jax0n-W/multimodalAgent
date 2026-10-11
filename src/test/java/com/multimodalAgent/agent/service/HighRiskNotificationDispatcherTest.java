package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.service.HighRiskNotificationStateStore.ClaimedNotification;
import com.multimodalAgent.agent.service.HighRiskNotificationStateStore.DeliveryMaterial;
import com.multimodalAgent.agent.service.mcp.AlertNotifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HighRiskNotificationDispatcherTest {

    @Test
    void externalSuccessFollowedByPersistenceFailureIsClassifiedUncertain() {
        HighRiskNotificationStateStore store = mock(HighRiskNotificationStateStore.class);
        AlertNotifier notifier = mock(AlertNotifier.class);
        multimodalAgentProperties properties = new multimodalAgentProperties();
        ClaimedNotification claim = new ClaimedNotification(
                "notification", 3L, 4L, "counselor@example.com", 1, 3, "owner", "token"
        );
        when(store.dueIds(any(), any(Integer.class))).thenReturn(List.of("notification"));
        when(store.claim(eq("notification"), any(), any(), any()))
                .thenReturn(Optional.of(claim));
        when(store.load(claim)).thenReturn(new DeliveryMaterial(
                new AlertRecord(), new PsychologicalReport()
        ));
        doThrow(new IllegalStateException("database unavailable"))
                .when(store).delivered(eq(claim), any(Instant.class));
        HighRiskNotificationDispatcher dispatcher = new HighRiskNotificationDispatcher(
                store, notifier, properties, new SyncTaskExecutor()
        );

        dispatcher.drainOnce();

        verify(notifier).notify(any(), any());
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(store).uncertain(eq(claim), reason.capture(), any(Instant.class));
        assertTrue(reason.getValue().contains("durable completion failed"));
    }
}
