package com.multimodalAgent.agent.coordination.redis;

import com.multimodalAgent.agent.coordination.config.RedisCoordinationProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisCoordinationKeyFactoryTest {

    @Test
    void shouldGenerateTheFrozenDefaultKeySchema() {
        RedisCoordinationKeyFactory factory = new RedisCoordinationKeyFactory(
                RedisCoordinationProperties.defaults()
        );

        assertEquals("mma:coord:v1:run:R1:lease", factory.leaseKey("R1"));
    }

    @Test
    void shouldUseTheConfiguredPrefixWithoutChangingRunId() {
        RedisCoordinationKeyFactory factory = new RedisCoordinationKeyFactory(properties(
                "test:coord",
                Duration.ofSeconds(2)
        ));

        assertEquals("test:coord:Run-ABC_123:lease", factory.leaseKey("Run-ABC_123"));
    }

    @Test
    void shouldRejectNullOrBlankRunId() {
        RedisCoordinationKeyFactory factory = new RedisCoordinationKeyFactory(
                RedisCoordinationProperties.defaults()
        );

        assertThrows(IllegalArgumentException.class, () -> factory.leaseKey(null));
        assertThrows(IllegalArgumentException.class, () -> factory.leaseKey(" "));
    }

    private RedisCoordinationProperties properties(String prefix, Duration ttl) {
        return new RedisCoordinationProperties(true, prefix, ttl, ttl.dividedBy(4));
    }
}
