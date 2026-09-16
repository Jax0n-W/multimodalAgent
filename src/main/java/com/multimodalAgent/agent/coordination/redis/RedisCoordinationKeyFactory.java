package com.multimodalAgent.agent.coordination.redis;

import com.multimodalAgent.agent.coordination.config.RedisCoordinationProperties;

import java.util.Objects;

/**
 * Builds the single Redis key schema used by run lease primitives.
 */
public final class RedisCoordinationKeyFactory {

    private final String keyPrefix;

    public RedisCoordinationKeyFactory(RedisCoordinationProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.keyPrefix = properties.keyPrefix();
    }

    public String leaseKey(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        return keyPrefix + ":" + runId + ":lease";
    }
}
