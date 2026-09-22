package com.multimodalAgent.agent.streaming.redis;

import com.multimodalAgent.agent.streaming.integration.LocalRunCancellationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p85-wiring;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "multimodal-agent.ai.provider=ollama",
        "multimodal-agent.runtime.enabled=true",
        "multimodal-agent.cancellation.redis.enabled=true",
        "multimodal-agent.knowledge.use-chroma=false"
})
class RedisCancellationProductionWiringTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine")
    ).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private RedisDistributedCancellationTransport transport;
    @Autowired private RedisMessageListenerContainer listener;
    @Autowired private LocalRunCancellationService cancellation;

    @Test
    void optInProductionCompositionStartsCommandAndAckListener() {
        assertNotNull(transport);
        assertNotNull(cancellation);
        assertTrue(listener.isRunning());
    }
}
