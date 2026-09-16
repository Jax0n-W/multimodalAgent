package com.multimodalAgent.agent.coordination.integration;

import com.multimodalAgent.agent.coordination.RunAlreadyActiveException;
import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.config.RedisCoordinationProperties;
import com.multimodalAgent.agent.coordination.redis.RedisCoordinationKeyFactory;
import com.multimodalAgent.agent.coordination.redis.RedisRunLeaseStore;
import com.multimodalAgent.agent.coordination.watchdog.DefaultRunLeaseWatchdogFactory;
import com.multimodalAgent.agent.coordination.watchdog.ScheduledExecutorLeaseRenewalScheduler;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class CoordinatedAgentExecutionRedisIntegrationTest {

    private static final int REDIS_PORT = 6379;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine")
    ).withExposedPorts(REDIS_PORT);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RedisRunLeaseStore leaseStore;
    private RedisCoordinationKeyFactory keyFactory;
    private ScheduledExecutorService renewalExecutor;

    @BeforeAll
    static void connectToRedis() {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(
                REDIS.getHost(),
                REDIS.getMappedPort(REDIS_PORT)
        );
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ZERO)
                .build();
        connectionFactory = new LettuceConnectionFactory(server, client);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void closeRedisClient() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void resetRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        RedisCoordinationProperties properties = new RedisCoordinationProperties(
                true,
                "test:lifecycle",
                Duration.ofSeconds(10),
                Duration.ofSeconds(3)
        );
        leaseStore = new RedisRunLeaseStore(redisTemplate, properties);
        keyFactory = new RedisCoordinationKeyFactory(properties);
        renewalExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void stopRenewalExecutor() throws InterruptedException {
        if (renewalExecutor != null) {
            renewalExecutor.shutdownNow();
            assertTrue(renewalExecutor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldAcquireExecuteAndReleaseAgainstRealRedis() {
        AgentRunResult expected = completedResult();
        CoordinatedAgentExecutionCoordinator coordinator =
                new CoordinatedAgentExecutionCoordinator(
                        leaseStore,
                        watchdogFactory(),
                        request -> expected,
                        (state, failureKind) -> {
                        }
                );

        AgentRunResult actual = coordinator.execute(request("run-smoke"));

        assertSame(expected, actual);
        assertNull(redisTemplate.opsForValue().get(keyFactory.leaseKey("run-smoke")));
    }

    @Test
    void shouldRejectRealRedisContentionWithoutCallingDelegate() {
        RunLease owner = assertInstanceOf(
                RunLeaseAcquireResult.Acquired.class,
                leaseStore.tryAcquire("run-contention")
        ).lease();
        AtomicInteger delegateCalls = new AtomicInteger();
        CoordinatedAgentExecutionCoordinator coordinator =
                new CoordinatedAgentExecutionCoordinator(
                        leaseStore,
                        watchdogFactory(),
                        request -> {
                            delegateCalls.incrementAndGet();
                            return completedResult();
                        },
                        (state, failureKind) -> {
                        }
                );

        assertThrows(
                RunAlreadyActiveException.class,
                () -> coordinator.execute(request("run-contention"))
        );

        assertEquals(0, delegateCalls.get());
        assertEquals(
                owner.leaseToken(),
                redisTemplate.opsForValue().get(keyFactory.leaseKey("run-contention"))
        );
        leaseStore.release(owner);
    }

    @Test
    void watchdogMustKeepLeaseAliveBeyondOriginalTtl() throws Exception {
        ShortLeaseFixture fixture = shortLeaseFixture();
        CountDownLatch delegateStarted = new CountDownLatch(1);
        CountDownLatch finishDelegate = new CountDownLatch(1);
        AgentRunResult expected = completedResult();
        CoordinatedAgentExecutionCoordinator coordinator =
                new CoordinatedAgentExecutionCoordinator(
                        fixture.store(),
                        realWatchdogFactory(fixture.store(), fixture.properties()),
                        request -> {
                            delegateStarted.countDown();
                            await(finishDelegate);
                            return expected;
                        },
                        (state, failureKind) -> {
                        }
                );
        ExecutorService execution = Executors.newSingleThreadExecutor();
        try {
            Future<AgentRunResult> future = execution.submit(
                    () -> coordinator.execute(request("run-long"))
            );
            assertTrue(delegateStarted.await(2, TimeUnit.SECONDS));

            Thread.sleep(1_300);

            String key = fixture.keyFactory().leaseKey("run-long");
            assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(key)));
            assertTrue(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS) > 0);
            finishDelegate.countDown();
            assertSame(expected, future.get(3, TimeUnit.SECONDS));
            assertNull(redisTemplate.opsForValue().get(key));
        } finally {
            finishDelegate.countDown();
            execution.shutdownNow();
            assertTrue(execution.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void secondOwnerMustRemainBlockedBeyondOriginalTtlWhileFirstRenews() throws Exception {
        ShortLeaseFixture fixture = shortLeaseFixture();
        CountDownLatch delegateStarted = new CountDownLatch(1);
        CountDownLatch finishDelegate = new CountDownLatch(1);
        CoordinatedAgentExecutionCoordinator nodeA =
                new CoordinatedAgentExecutionCoordinator(
                        fixture.store(),
                        realWatchdogFactory(fixture.store(), fixture.properties()),
                        request -> {
                            delegateStarted.countDown();
                            await(finishDelegate);
                            return completedResult();
                        },
                        (state, failureKind) -> {
                        }
                );
        AtomicInteger nodeBDelegateCalls = new AtomicInteger();
        CoordinatedAgentExecutionCoordinator nodeB =
                new CoordinatedAgentExecutionCoordinator(
                        fixture.store(),
                        realWatchdogFactory(fixture.store(), fixture.properties()),
                        request -> {
                            nodeBDelegateCalls.incrementAndGet();
                            return completedResult();
                        },
                        (state, failureKind) -> {
                        }
                );
        ExecutorService execution = Executors.newSingleThreadExecutor();
        try {
            Future<AgentRunResult> owner = execution.submit(
                    () -> nodeA.execute(request("run-multi-owner"))
            );
            assertTrue(delegateStarted.await(2, TimeUnit.SECONDS));

            Thread.sleep(1_300);

            assertThrows(
                    RunAlreadyActiveException.class,
                    () -> nodeB.execute(request("run-multi-owner"))
            );
            assertEquals(0, nodeBDelegateCalls.get());
            finishDelegate.countDown();
            assertEquals(AgentStopReason.COMPLETED,
                    owner.get(3, TimeUnit.SECONDS).stopReason());
        } finally {
            finishDelegate.countDown();
            execution.shutdownNow();
            assertTrue(execution.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private AgentExecutionRequest request(String runId) {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        runId,
                        "session-smoke",
                        List.of(AgentMessage.user("execute")),
                        2,
                        Set.of(),
                        Set.of()
                ),
                "request-" + runId,
                7002L
        );
    }

    private com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdogFactory
            watchdogFactory() {
        return session -> new com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdog(
                leaseStore,
                session,
                (task, interval) -> () -> {
                },
                Duration.ofSeconds(3)
        );
    }

    private com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdogFactory
            realWatchdogFactory(
                    RedisRunLeaseStore store,
                    RedisCoordinationProperties properties
            ) {
        return new DefaultRunLeaseWatchdogFactory(
                store,
                new ScheduledExecutorLeaseRenewalScheduler(renewalExecutor),
                properties.renewInterval()
        );
    }

    private ShortLeaseFixture shortLeaseFixture() {
        RedisCoordinationProperties properties = new RedisCoordinationProperties(
                true,
                "test:watchdog",
                Duration.ofMillis(900),
                Duration.ofMillis(200)
        );
        return new ShortLeaseFixture(
                properties,
                new RedisRunLeaseStore(redisTemplate, properties),
                new RedisCoordinationKeyFactory(properties)
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test delegate release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting in test delegate", exception);
        }
    }

    private record ShortLeaseFixture(
            RedisCoordinationProperties properties,
            RedisRunLeaseStore store,
            RedisCoordinationKeyFactory keyFactory
    ) {
    }

    private AgentRunResult completedResult() {
        return new AgentRunResult(
                "done",
                AgentStopReason.COMPLETED,
                1,
                List.of(),
                List.of(AgentMessage.assistant("done")),
                TokenUsage.ZERO,
                null,
                null,
                null
        );
    }
}
