package com.multimodalAgent.agent.coordination.redis;

import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.config.CoordinationWatchdogConfiguration;
import com.multimodalAgent.agent.coordination.config.RedisCoordinationProperties;
import com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdogFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisCoordinationProductionWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    RedisCoordinationConfiguration.class,
                    CoordinationWatchdogConfiguration.class
            )
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withPropertyValues(
                    "multimodal-agent.coordination.redis.enabled=true",
                    "multimodal-agent.coordination.redis.key-prefix=test:coord:run",
                    "multimodal-agent.coordination.redis.lease-ttl=60s",
                    "multimodal-agent.coordination.redis.renew-interval=20s",
                    "multimodal-agent.coordination.redis.watchdog-threads=2"
            );

    @Test
    void enabledProductionCompositionBindsImmutablePropertiesAndCreatesLeaseBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RedisCoordinationProperties.class);
            assertThat(context.getBean(RedisCoordinationProperties.class).watchdogThreads())
                    .isEqualTo(2);
            assertThat(context).hasSingleBean(RunLeaseStore.class);
            assertThat(context.getBean(RunLeaseStore.class))
                    .isInstanceOf(RedisRunLeaseStore.class);
            assertThat(context).hasSingleBean(RunLeaseWatchdogFactory.class);
        });
    }
}
