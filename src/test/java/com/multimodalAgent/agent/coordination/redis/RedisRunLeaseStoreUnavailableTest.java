package com.multimodalAgent.agent.coordination.redis;

import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseRenewResult;
import com.multimodalAgent.agent.coordination.config.RedisCoordinationProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RedisRunLeaseStoreUnavailableTest {

    private static LettuceConnectionFactory connectionFactory;
    private static RedisRunLeaseStore store;

    @BeforeAll
    static void createUnavailableStore() {
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(200))
                .build();
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .build();
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofMillis(250))
                .shutdownTimeout(Duration.ZERO)
                .build();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                client
        );
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        store = new RedisRunLeaseStore(template, new RedisCoordinationProperties(
                true,
                "test:unavailable",
                Duration.ofSeconds(3),
                Duration.ofSeconds(1)
        ));
    }

    @AfterAll
    static void closeClient() {
        connectionFactory.destroy();
    }

    @Test
    void shouldMapAcquireConnectionFailureToUnavailableResult() {
        assertInstanceOf(RunLeaseAcquireResult.Unavailable.class, store.tryAcquire("R1"));
    }

    @Test
    void shouldMapRenewConnectionFailureToUnavailableResult() {
        assertEquals(
                RunLeaseRenewResult.COORDINATION_UNAVAILABLE,
                store.renew(new RunLease("R1", "token-1"))
        );
    }

    @Test
    void shouldMapReleaseConnectionFailureToUnavailableResult() {
        assertEquals(
                RunLeaseReleaseResult.COORDINATION_UNAVAILABLE,
                store.release(new RunLease("R1", "token-1"))
        );
    }
}
