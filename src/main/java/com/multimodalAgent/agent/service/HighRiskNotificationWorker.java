package com.multimodalAgent.agent.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "multimodal-agent.mcp.email",
        name = "outbox-worker-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class HighRiskNotificationWorker implements ApplicationRunner {
    private final HighRiskNotificationDispatcher dispatcher;

    public HighRiskNotificationWorker(HighRiskNotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void run(ApplicationArguments args) {
        dispatcher.dispatchAsync();
    }

    @Scheduled(fixedDelayString = "${multimodal-agent.mcp.email.outbox-poll-interval-ms:30000}")
    public void scan() {
        dispatcher.dispatchAsync();
    }
}
