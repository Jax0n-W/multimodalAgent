package com.multimodalAgent.agent.coordination.redis;

import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseFailureKind;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseRenewResult;
import com.multimodalAgent.agent.coordination.RunLeaseSession;
import com.multimodalAgent.agent.coordination.RunLeaseState;
import com.multimodalAgent.agent.coordination.config.RedisCoordinationProperties;
import com.multimodalAgent.agent.coordination.watchdog.LeaseRenewalScheduler;
import com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class RedisRunLeaseStoreIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final Duration STANDARD_TTL = Duration.ofSeconds(2);

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine")
    ).withExposedPorts(REDIS_PORT);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RedisCoordinationProperties properties;
    private RedisCoordinationKeyFactory keyFactory;
    private RedisRunLeaseStore store;

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
        properties = properties("test:coord", STANDARD_TTL);
        keyFactory = new RedisCoordinationKeyFactory(properties);
        store = new RedisRunLeaseStore(redisTemplate, properties);
    }

    @Test
    void shouldAcquireAnEmptyRunLeaseWithTokenAndTtl() {
        RunLease lease = acquire("R1");
        String key = keyFactory.leaseKey("R1");

        assertEquals("R1", lease.runId());
        assertFalse(lease.leaseToken().isBlank());
        assertNotEquals(lease.runId(), lease.leaseToken());
        assertEquals(lease.leaseToken(), redisTemplate.opsForValue().get(key));
        assertPositiveTtlWithin(key, STANDARD_TTL);
    }

    @Test
    void shouldRejectASecondAcquireWithoutOverwritingTheOwner() {
        RunLease first = acquire("R1");

        RunLeaseAcquireResult second = store.tryAcquire("R1");

        assertInstanceOf(RunLeaseAcquireResult.AlreadyActive.class, second);
        assertEquals(first.leaseToken(), redisTemplate.opsForValue().get(keyFactory.leaseKey("R1")));
    }

    @Test
    void shouldAcquireDifferentRunIdsIndependently() {
        RunLease first = acquire("R1");
        RunLease second = acquire("R2");

        assertNotEquals(first.leaseToken(), second.leaseToken());
        assertEquals(first.leaseToken(), redisTemplate.opsForValue().get(keyFactory.leaseKey("R1")));
        assertEquals(second.leaseToken(), redisTemplate.opsForValue().get(keyFactory.leaseKey("R2")));
    }

    @Test
    void shouldNeverCreateAPermanentLeaseKey() {
        acquire("R1");

        Long ttl = redisTemplate.getExpire(keyFactory.leaseKey("R1"), TimeUnit.MILLISECONDS);

        assertNotNull(ttl);
        assertTrue(ttl > 0, "lease must have a positive PTTL");
    }

    @Test
    void shouldRenewOnlyTheCurrentOwnerAndExtendTtl() throws Exception {
        RunLease lease = acquire("R1");
        String key = keyFactory.leaseKey("R1");
        Thread.sleep(300);
        long before = pttl(key);

        RunLeaseRenewResult result = store.renew(lease);
        long after = pttl(key);

        assertEquals(RunLeaseRenewResult.RENEWED, result);
        assertEquals(lease.leaseToken(), redisTemplate.opsForValue().get(key));
        assertTrue(after > before, "renew must extend the existing TTL");
        assertTrue(after > 1_200 && after <= STANDARD_TTL.toMillis());
    }

    @Test
    void shouldRejectAStaleRenewWithoutChangingOwnerOrExtendingTtl() throws Exception {
        RunLease owner = acquire("R1");
        RunLease stale = new RunLease("R1", "stale-token");
        String key = keyFactory.leaseKey("R1");
        Thread.sleep(150);
        long before = pttl(key);

        RunLeaseRenewResult result = store.renew(stale);
        long after = pttl(key);

        assertEquals(RunLeaseRenewResult.EXPLICIT_LEASE_LOSS, result);
        assertEquals(owner.leaseToken(), redisTemplate.opsForValue().get(key));
        assertTrue(after <= before, "stale renew must not extend the owner's TTL");
    }

    @Test
    void shouldTreatRenewOfAMissingKeyAsExplicitLeaseLoss() {
        RunLease lease = acquire("R1");
        redisTemplate.delete(keyFactory.leaseKey("R1"));

        assertEquals(RunLeaseRenewResult.EXPLICIT_LEASE_LOSS, store.renew(lease));
    }

    @Test
    void shouldReleaseTheCurrentOwner() {
        RunLease lease = acquire("R1");
        String key = keyFactory.leaseKey("R1");

        RunLeaseReleaseResult result = store.release(lease);

        assertEquals(RunLeaseReleaseResult.RELEASED, result);
        assertNull(redisTemplate.opsForValue().get(key));
    }

    @Test
    void shouldNotReleaseALeaseWithAStaleToken() {
        RunLease owner = acquire("R1");
        RunLease stale = new RunLease("R1", "stale-token");
        String key = keyFactory.leaseKey("R1");

        RunLeaseReleaseResult result = store.release(stale);

        assertEquals(RunLeaseReleaseResult.NO_LONGER_OWNER, result);
        assertEquals(owner.leaseToken(), redisTemplate.opsForValue().get(key));
    }

    @Test
    void staleOwnerMustNotDeleteANewOwnersLease() {
        RunLease oldOwner = acquire("R1");
        String key = keyFactory.leaseKey("R1");
        redisTemplate.delete(key);
        RunLease newOwner = acquire("R1");

        RunLeaseReleaseResult result = store.release(oldOwner);

        assertEquals(RunLeaseReleaseResult.NO_LONGER_OWNER, result);
        assertEquals(newOwner.leaseToken(), redisTemplate.opsForValue().get(key));
    }

    @Test
    void staleOwnerMustNotRenewANewOwnersLease() throws Exception {
        RunLease oldOwner = acquire("R1");
        String key = keyFactory.leaseKey("R1");
        redisTemplate.delete(key);
        RunLease newOwner = acquire("R1");
        Thread.sleep(150);
        long before = pttl(key);

        RunLeaseRenewResult result = store.renew(oldOwner);
        long after = pttl(key);

        assertEquals(RunLeaseRenewResult.EXPLICIT_LEASE_LOSS, result);
        assertEquals(newOwner.leaseToken(), redisTemplate.opsForValue().get(key));
        assertTrue(after <= before, "stale owner must not extend the new owner's TTL");
    }

    @Test
    void staleWatchdogRenewMustLoseAuthorityWithoutTouchingTheNewOwner() throws Exception {
        RunLease oldOwner = acquire("R1");
        RunLeaseSession oldSession = new RunLeaseSession(oldOwner);
        ControlledScheduler scheduler = new ControlledScheduler();
        RunLeaseWatchdog watchdog = new RunLeaseWatchdog(
                store,
                oldSession,
                scheduler,
                Duration.ofMillis(100)
        );
        watchdog.start();
        String key = keyFactory.leaseKey("R1");
        redisTemplate.delete(key);
        RunLease newOwner = acquire("R1");
        Thread.sleep(100);
        long before = pttl(key);

        scheduler.tick();
        long after = pttl(key);

        assertEquals(RunLeaseState.LOST, oldSession.state());
        assertEquals(
                RunLeaseFailureKind.EXPLICIT_LEASE_LOSS,
                oldSession.firstFailure().orElseThrow()
        );
        assertEquals(newOwner.leaseToken(), redisTemplate.opsForValue().get(key));
        assertTrue(after <= before, "the stale watchdog must not extend the new owner's TTL");
    }

    @Test
    void renewalAtTheTtlEdgeMustRemainOwnershipSafeForEitherAtomicOutcome() throws Exception {
        Duration edgeTtl = Duration.ofMillis(400);
        RedisCoordinationProperties edgeProperties = properties("test:edge", edgeTtl);
        RedisRunLeaseStore edgeStore = new RedisRunLeaseStore(redisTemplate, edgeProperties);
        RedisCoordinationKeyFactory edgeKeys = new RedisCoordinationKeyFactory(edgeProperties);
        RunLease lease = acquired(edgeStore.tryAcquire("R1"));
        RunLeaseSession session = new RunLeaseSession(lease);
        ControlledScheduler scheduler = new ControlledScheduler();
        RunLeaseWatchdog watchdog = new RunLeaseWatchdog(
                edgeStore,
                session,
                scheduler,
                Duration.ofMillis(100)
        );
        watchdog.start();
        Thread.sleep(360);

        scheduler.tick();

        String value = redisTemplate.opsForValue().get(edgeKeys.leaseKey("R1"));
        if (session.state() == RunLeaseState.ACTIVE) {
            assertEquals(lease.leaseToken(), value);
            assertPositiveTtlWithin(edgeKeys.leaseKey("R1"), edgeTtl);
            watchdog.stop();
        } else {
            assertEquals(RunLeaseState.LOST, session.state());
            assertEquals(
                    RunLeaseFailureKind.EXPLICIT_LEASE_LOSS,
                    session.firstFailure().orElseThrow()
            );
            assertNull(value);
        }
    }

    @Test
    void shouldAllowALeaseToExpireNaturally() throws Exception {
        RedisCoordinationProperties shortProperties = properties(
                "test:short",
                Duration.ofMillis(400)
        );
        RedisRunLeaseStore shortStore = new RedisRunLeaseStore(redisTemplate, shortProperties);
        RedisCoordinationKeyFactory shortKeys = new RedisCoordinationKeyFactory(shortProperties);

        shortStore.tryAcquire("R1");
        awaitMissing(shortKeys.leaseKey("R1"), Duration.ofSeconds(2));

        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(shortKeys.leaseKey("R1"))));
    }

    @Test
    void shouldAllowRedisAcquireAfterExpiryWithoutClaimingDatabaseAdmission() throws Exception {
        RedisCoordinationProperties shortProperties = properties(
                "test:expiry",
                Duration.ofMillis(400)
        );
        RedisRunLeaseStore shortStore = new RedisRunLeaseStore(redisTemplate, shortProperties);
        RedisCoordinationKeyFactory shortKeys = new RedisCoordinationKeyFactory(shortProperties);
        RunLease oldOwner = acquired(shortStore.tryAcquire("R1"));
        awaitMissing(shortKeys.leaseKey("R1"), Duration.ofSeconds(2));

        RunLease newOwner = acquired(shortStore.tryAcquire("R1"));

        assertNotEquals(oldOwner.leaseToken(), newOwner.leaseToken());
        assertEquals(newOwner.leaseToken(), redisTemplate.opsForValue().get(
                shortKeys.leaseKey("R1")
        ));
        // This proves only Redis active ownership. P6 durable admission may still reject the run.
    }

    @Test
    void shouldGenerateUniqueTokensForAReasonableSample() {
        Set<String> tokens = new HashSet<>();

        for (int index = 0; index < 20; index++) {
            tokens.add(acquire("run-" + index).leaseToken());
        }

        assertEquals(20, tokens.size());
    }

    @Test
    void shouldUseConfiguredPrefixAndTtlInsteadOfDefaults() {
        Duration configuredTtl = Duration.ofMillis(1_200);
        RedisCoordinationProperties custom = properties("custom:coord", configuredTtl);
        RedisRunLeaseStore customStore = new RedisRunLeaseStore(redisTemplate, custom);

        RunLease lease = acquired(customStore.tryAcquire("R1"));
        String key = "custom:coord:R1:lease";

        assertEquals(lease.leaseToken(), redisTemplate.opsForValue().get(key));
        assertPositiveTtlWithin(key, configuredTtl);
        assertNull(redisTemplate.opsForValue().get("test:coord:R1:lease"));
    }

    private RunLease acquire(String runId) {
        return acquired(store.tryAcquire(runId));
    }

    private RunLease acquired(RunLeaseAcquireResult result) {
        return assertInstanceOf(RunLeaseAcquireResult.Acquired.class, result).lease();
    }

    private long pttl(String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        assertNotNull(ttl);
        return ttl;
    }

    private void assertPositiveTtlWithin(String key, Duration expectedTtl) {
        long ttl = pttl(key);
        assertTrue(ttl > 0, "lease PTTL must be positive");
        assertTrue(ttl <= expectedTtl.toMillis(), "lease PTTL must not exceed configured TTL");
    }

    private void awaitMissing(String key, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (Boolean.TRUE.equals(redisTemplate.hasKey(key)) && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
    }

    private RedisCoordinationProperties properties(String prefix, Duration ttl) {
        return new RedisCoordinationProperties(true, prefix, ttl, ttl.dividedBy(4));
    }

    private static final class ControlledScheduler implements LeaseRenewalScheduler {

        private Runnable task;
        private boolean cancelled;

        @Override
        public ScheduledRenewal scheduleWithFixedDelay(Runnable task, Duration interval) {
            this.task = task;
            return () -> cancelled = true;
        }

        private void tick() {
            if (!cancelled) {
                task.run();
            }
        }
    }
}
