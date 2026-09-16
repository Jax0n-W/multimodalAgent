package com.multimodalAgent.agent.coordination.redis;

import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseRenewResult;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.config.RedisCoordinationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Spring Data Redis adapter for the three atomic run lease primitives.
 * It does not manage sessions, renewal scheduling, or execution lifecycle.
 */
public final class RedisRunLeaseStore implements RunLeaseStore {

    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            else
                return 0
            end
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisCoordinationKeyFactory keyFactory;
    private final Duration leaseTtl;
    private final Supplier<String> tokenSupplier;

    public RedisRunLeaseStore(
            StringRedisTemplate redisTemplate,
            RedisCoordinationProperties properties
    ) {
        this(redisTemplate, properties, () -> UUID.randomUUID().toString());
    }

    RedisRunLeaseStore(
            StringRedisTemplate redisTemplate,
            RedisCoordinationProperties properties,
            Supplier<String> tokenSupplier
    ) {
        this.redisTemplate = Objects.requireNonNull(
                redisTemplate,
                "redisTemplate must not be null"
        );
        RedisCoordinationProperties checkedProperties = Objects.requireNonNull(
                properties,
                "properties must not be null"
        );
        this.keyFactory = new RedisCoordinationKeyFactory(checkedProperties);
        this.leaseTtl = checkedProperties.leaseTtl();
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier must not be null");
    }

    @Override
    public RunLeaseAcquireResult tryAcquire(String runId) {
        String key = keyFactory.leaseKey(runId);
        RunLease lease = new RunLease(
                runId,
                Objects.requireNonNull(tokenSupplier.get(), "tokenSupplier returned null")
        );
        try {
            Boolean acquired = redisTemplate.execute((RedisCallback<Boolean>) connection ->
                    connection.stringCommands().set(
                            serialize(key),
                            serialize(lease.leaseToken()),
                            Expiration.milliseconds(leaseTtl.toMillis()),
                            SetOption.SET_IF_ABSENT
                    )
            );
            if (acquired == null) {
                return new RunLeaseAcquireResult.Unavailable(runId);
            }
            return acquired
                    ? new RunLeaseAcquireResult.Acquired(lease)
                    : new RunLeaseAcquireResult.AlreadyActive(runId);
        } catch (DataAccessException exception) {
            return new RunLeaseAcquireResult.Unavailable(runId);
        }
    }

    @Override
    public RunLeaseRenewResult renew(RunLease lease) {
        Objects.requireNonNull(lease, "lease must not be null");
        String key = keyFactory.leaseKey(lease.runId());
        try {
            Long result = redisTemplate.execute(
                    RENEW_SCRIPT,
                    List.of(key),
                    lease.leaseToken(),
                    Long.toString(leaseTtl.toMillis())
            );
            if (result == null) {
                return RunLeaseRenewResult.COORDINATION_UNAVAILABLE;
            }
            return result == 1L
                    ? RunLeaseRenewResult.RENEWED
                    : RunLeaseRenewResult.EXPLICIT_LEASE_LOSS;
        } catch (DataAccessException exception) {
            return RunLeaseRenewResult.COORDINATION_UNAVAILABLE;
        }
    }

    @Override
    public RunLeaseReleaseResult release(RunLease lease) {
        Objects.requireNonNull(lease, "lease must not be null");
        String key = keyFactory.leaseKey(lease.runId());
        try {
            Long result = redisTemplate.execute(
                    RELEASE_SCRIPT,
                    List.of(key),
                    lease.leaseToken()
            );
            if (result == null) {
                return RunLeaseReleaseResult.COORDINATION_UNAVAILABLE;
            }
            return result == 1L
                    ? RunLeaseReleaseResult.RELEASED
                    : RunLeaseReleaseResult.NO_LONGER_OWNER;
        } catch (DataAccessException exception) {
            return RunLeaseReleaseResult.COORDINATION_UNAVAILABLE;
        }
    }

    private byte[] serialize(String value) {
        RedisSerializer<String> serializer = redisTemplate.getStringSerializer();
        byte[] serialized = serializer.serialize(value);
        return Objects.requireNonNull(serialized, "StringRedisSerializer returned null");
    }
}
