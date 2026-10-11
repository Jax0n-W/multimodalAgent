package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.service.HighRiskNotificationStateStore.ClaimedNotification;
import com.multimodalAgent.agent.service.HighRiskNotificationStateStore.DeliveryMaterial;
import com.multimodalAgent.agent.service.mcp.AlertNotifier;
import com.multimodalAgent.agent.service.mcp.DefinitelyNotDeliveredException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class HighRiskNotificationDispatcher {
    private final HighRiskNotificationStateStore store;
    private final AlertNotifier notifier;
    private final multimodalAgentProperties properties;
    private final TaskExecutor executor;
    private final String workerId = "notification-" + UUID.randomUUID();

    public HighRiskNotificationDispatcher(
            HighRiskNotificationStateStore store,
            AlertNotifier notifier,
            multimodalAgentProperties properties,
            @Qualifier("mcpTaskExecutor") TaskExecutor executor
    ) {
        this.store = store;
        this.notifier = notifier;
        this.properties = properties;
        this.executor = executor;
    }

    public void dispatchAsync() {
        executor.execute(this::drainOnce);
    }

    public int drainOnce() {
        Instant now = Instant.now();
        store.quarantineExpiredLeases(now);
        int deliveredOrClassified = 0;
        for (String id : store.dueIds(now, properties.getMcp().getEmail().getOutboxBatchSize())) {
            var claim = store.claim(
                    id, workerId, Instant.now(),
                    properties.getMcp().getEmail().getOutboxLeaseDuration()
            );
            if (claim.isPresent()) {
                deliver(claim.orElseThrow());
                deliveredOrClassified++;
            }
        }
        return deliveredOrClassified;
    }

    private void deliver(ClaimedNotification claim) {
        boolean externalCallReturned = false;
        try {
            DeliveryMaterial material = store.load(claim);
            store.beginDelivery(claim, Instant.now());
            notifier.notify(material.alert(), material.report());
            externalCallReturned = true;
            store.delivered(claim, Instant.now());
        } catch (DefinitelyNotDeliveredException failure) {
            try {
                store.definitelyNotDelivered(
                        claim, failure.getMessage(), Instant.now(),
                        properties.getMcp().getEmail().getOutboxRetryDelay()
                );
            } catch (RuntimeException stateFailure) {
                // A later claim generation may already own the row. Never let this stale
                // execution classify or otherwise mutate the current delivery attempt.
                failure.addSuppressed(stateFailure);
            }
        } catch (RuntimeException failure) {
            String reason = externalCallReturned
                    ? "Delivery returned successfully but durable completion failed"
                    : failure.getMessage();
            try {
                store.uncertain(
                        claim,
                        reason == null ? failure.getClass().getSimpleName() : reason,
                        Instant.now()
                );
            } catch (RuntimeException stateFailure) {
                // Keep the durable PROCESSING claim. The lease scanner will quarantine it as
                // UNCERTAIN; it must never become eligible for blind redelivery.
                failure.addSuppressed(stateFailure);
            }
        }
    }
}
