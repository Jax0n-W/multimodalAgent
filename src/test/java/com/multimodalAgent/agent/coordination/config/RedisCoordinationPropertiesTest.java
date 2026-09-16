package com.multimodalAgent.agent.coordination.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisCoordinationPropertiesTest {

    @Test
    void shouldProvideSaneDefaultsWithoutEnablingInfrastructure() {
        RedisCoordinationProperties properties = RedisCoordinationProperties.defaults();

        assertFalse(properties.enabled());
        assertEquals("mma:coord:v1:run", properties.keyPrefix());
        assertEquals(Duration.ofSeconds(60), properties.leaseTtl());
        assertEquals(Duration.ofSeconds(20), properties.renewInterval());
        assertEquals(4, properties.watchdogThreads());
    }

    @Test
    void shouldAcceptRenewalAtExactlyOneThirdOfTtl() {
        RedisCoordinationProperties properties = new RedisCoordinationProperties(
                true,
                "custom:coord:run",
                Duration.ofSeconds(60),
                Duration.ofSeconds(20)
        );

        assertEquals(Duration.ofSeconds(20), properties.renewInterval());
    }

    @Test
    void shouldRejectRenewalGreaterThanOneThirdOfTtl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisCoordinationProperties(
                        true,
                        "custom:coord:run",
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(21)
                )
        );
    }

    @Test
    void shouldRejectNonPositiveDurations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisCoordinationProperties(
                        true,
                        "custom:coord:run",
                        Duration.ZERO,
                        Duration.ofSeconds(1)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisCoordinationProperties(
                        true,
                        "custom:coord:run",
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(-1)
                )
        );
    }

    @Test
    void shouldRejectBlankKeyPrefix() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisCoordinationProperties(
                        true,
                        " ",
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(20)
                )
        );
    }

    @Test
    void shouldRejectNonPositiveWatchdogCapacity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisCoordinationProperties(
                        true,
                        "custom:coord:run",
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(20),
                        0
                )
        );
    }

    @Test
    void watchdogConfigurationMustUseTheConfiguredSharedCapacity() {
        RedisCoordinationProperties properties = new RedisCoordinationProperties(
                true,
                "custom:coord:run",
                Duration.ofSeconds(60),
                Duration.ofSeconds(20),
                6
        );
        ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor)
                new CoordinationWatchdogConfiguration().runLeaseRenewalExecutor(properties);
        try {
            assertEquals(6, executor.getCorePoolSize());
        } finally {
            executor.shutdown();
        }
    }
}
