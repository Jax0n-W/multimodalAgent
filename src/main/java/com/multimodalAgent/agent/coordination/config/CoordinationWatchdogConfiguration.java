package com.multimodalAgent.agent.coordination.config;

import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.integration.ExecutionCoordinationBoundaryMiddleware;
import com.multimodalAgent.agent.coordination.watchdog.DefaultRunLeaseWatchdogFactory;
import com.multimodalAgent.agent.coordination.watchdog.LeaseRenewalScheduler;
import com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdogFactory;
import com.multimodalAgent.agent.coordination.watchdog.ScheduledExecutorLeaseRenewalScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional shared watchdog components. This deliberately does not replace the application's
 * production execution entry point with the coordinated flow.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisCoordinationProperties.class)
@ConditionalOnProperty(
        prefix = "multimodal-agent.coordination.redis",
        name = "enabled",
        havingValue = "true"
)
public class CoordinationWatchdogConfiguration {

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService runLeaseRenewalExecutor(
            RedisCoordinationProperties properties
    ) {
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(
                    task,
                    "run-lease-renewal-" + threadNumber.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newScheduledThreadPool(properties.watchdogThreads(), threadFactory);
    }

    @Bean(destroyMethod = "close")
    public ScheduledExecutorLeaseRenewalScheduler leaseRenewalScheduler(
            ScheduledExecutorService runLeaseRenewalExecutor
    ) {
        return new ScheduledExecutorLeaseRenewalScheduler(runLeaseRenewalExecutor);
    }

    @Bean
    public RunLeaseWatchdogFactory runLeaseWatchdogFactory(
            RunLeaseStore leaseStore,
            LeaseRenewalScheduler scheduler,
            RedisCoordinationProperties properties
    ) {
        return new DefaultRunLeaseWatchdogFactory(
                leaseStore,
                scheduler,
                properties.renewInterval()
        );
    }

    @Bean
    public ExecutionCoordinationBoundaryMiddleware executionCoordinationBoundaryMiddleware() {
        return new ExecutionCoordinationBoundaryMiddleware();
    }
}
