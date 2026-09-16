package com.multimodalAgent.agent.coordination.redis;

import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.config.RedisCoordinationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Optional Spring composition for the Redis primitive adapter only.
 * P7.4 watchdog composition may consume this port, but no production Agent entry point does yet.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisCoordinationProperties.class)
@ConditionalOnProperty(
        prefix = "multimodal-agent.coordination.redis",
        name = "enabled",
        havingValue = "true"
)
public class RedisCoordinationConfiguration {

    @Bean
    public RunLeaseStore redisRunLeaseStore(
            StringRedisTemplate redisTemplate,
            RedisCoordinationProperties properties
    ) {
        return new RedisRunLeaseStore(redisTemplate, properties);
    }
}
