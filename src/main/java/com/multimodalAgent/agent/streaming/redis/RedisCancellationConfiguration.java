package com.multimodalAgent.agent.streaming.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.streaming.integration.LocalExecutionControlRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.Objects;

/** Opt-in P8.5 delivery; independent of the P7 lease configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisCancellationProperties.class)
@ConditionalOnProperty(
        prefix = "multimodal-agent.cancellation.redis",
        name = "enabled",
        havingValue = "true"
)
public class RedisCancellationConfiguration {

    @Bean
    public RedisDistributedCancellationTransport redisDistributedCancellationTransport(
            StringRedisTemplate redis,
            ObjectMapper mapper,
            LocalExecutionControlRegistry controls,
            RedisCancellationProperties properties
    ) {
        return new RedisDistributedCancellationTransport(redis, mapper, controls, properties);
    }

    @Bean
    public RedisMessageListenerContainer cancellationMessageListenerContainer(
            StringRedisTemplate redis,
            RedisDistributedCancellationTransport transport,
            RedisCancellationProperties properties
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(Objects.requireNonNull(
                redis.getConnectionFactory(), "Redis connection factory must not be null"
        ));
        container.addMessageListener(transport, new ChannelTopic(properties.commandChannel()));
        container.addMessageListener(transport, new ChannelTopic(properties.ackChannel()));
        return container;
    }
}
