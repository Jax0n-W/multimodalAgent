package com.multimodalAgent.agent.coordination.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

/**
 * Future Redis coordination settings. Declaring this contract does not enable or connect Redis.
 */
@ConfigurationProperties(prefix = "multimodal-agent.coordination.redis")
public record RedisCoordinationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("mma:coord:v1:run") String keyPrefix,
        @DefaultValue("60s") Duration leaseTtl,
        @DefaultValue("20s") Duration renewInterval
) {

    public RedisCoordinationProperties {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        requirePositive(leaseTtl, "leaseTtl");
        requirePositive(renewInterval, "renewInterval");
        if (renewInterval.compareTo(leaseTtl.dividedBy(3)) > 0) {
            throw new IllegalArgumentException(
                    "renewInterval must be at most one third of leaseTtl"
            );
        }
    }

    public static RedisCoordinationProperties defaults() {
        return new RedisCoordinationProperties(
                false,
                "mma:coord:v1:run",
                Duration.ofSeconds(60),
                Duration.ofSeconds(20)
        );
    }

    private static void requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
