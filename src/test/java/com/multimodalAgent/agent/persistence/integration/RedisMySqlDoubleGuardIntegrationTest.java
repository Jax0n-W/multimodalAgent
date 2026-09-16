package com.multimodalAgent.agent.persistence.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.config.RedisCoordinationProperties;
import com.multimodalAgent.agent.coordination.integration.CoordinatedAgentExecutionCoordinator;
import com.multimodalAgent.agent.coordination.redis.RedisCoordinationKeyFactory;
import com.multimodalAgent.agent.coordination.redis.RedisRunLeaseStore;
import com.multimodalAgent.agent.coordination.watchdog.DefaultRunLeaseWatchdogFactory;
import com.multimodalAgent.agent.coordination.watchdog.LeaseRenewalScheduler;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaExecutionHistoryStore.class)
@Testcontainers(disabledWithoutDocker = true)
class RedisMySqlDoubleGuardIntegrationTest {

    private static final int REDIS_PORT = 6379;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("coordination_guard_test");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine")
    ).withExposedPorts(REDIS_PORT);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @Autowired
    private JpaExecutionHistoryStore historyStore;

    @Autowired
    private AgentRunRepository runRepository;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @BeforeAll
    static void connectToRedis() {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(
                REDIS.getHost(),
                REDIS.getMappedPort(REDIS_PORT)
        );
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ZERO)
                .build();
        connectionFactory = new LettuceConnectionFactory(server, client);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void closeRedisClient() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void flushRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void redisAcquireMustNotBypassTheDurableMySqlRunIdentityGuard() {
        AgentExecutionRequest request = request();
        historyStore.admit(request);
        assertEquals(1, runRepository.count());
        RedisCoordinationProperties properties = new RedisCoordinationProperties(
                true,
                "test:double-guard",
                Duration.ofSeconds(5),
                Duration.ofSeconds(1)
        );
        RedisRunLeaseStore leaseStore = new RedisRunLeaseStore(redisTemplate, properties);
        RunLease oldLease = assertInstanceOf(
                RunLeaseAcquireResult.Acquired.class,
                leaseStore.tryAcquire(request.runSpec().runId())
        ).lease();
        assertEquals(RunLeaseReleaseResult.RELEASED, leaseStore.release(oldLease));

        AtomicInteger modelCalls = new AtomicInteger();
        ExecutionPersistenceComposition persistence =
                new ExecutionPersistenceComposition(historyStore);
        AgentExecutionCoordinator harness = harness(modelCalls, persistence);
        PersistentAgentExecutionCoordinator persistent = persistence.persistentCoordinator(harness);
        LeaseRenewalScheduler scheduler = (task, interval) -> () -> {
        };
        CoordinatedAgentExecutionCoordinator coordinator =
                new CoordinatedAgentExecutionCoordinator(
                        leaseStore,
                        new DefaultRunLeaseWatchdogFactory(
                                leaseStore,
                                scheduler,
                                properties.renewInterval()
                        ),
                        persistent
                );

        assertThrows(ExecutionPersistenceException.class, () -> coordinator.execute(request));

        assertEquals(0, modelCalls.get());
        assertEquals(1, runRepository.count());
        assertNull(redisTemplate.opsForValue().get(
                new RedisCoordinationKeyFactory(properties).leaseKey(request.runSpec().runId())
        ));
    }

    private AgentExecutionCoordinator harness(
            AtomicInteger modelCalls,
            ExecutionPersistenceComposition persistence
    ) {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
                new ToolRegistry(List.of()),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        return new AgentExecutionCoordinator(
                new AgentRunner(
                        ignored -> {
                            modelCalls.incrementAndGet();
                            return ModelTurn.finalAnswer("must not run");
                        },
                        toolExecutor,
                        TestModelToolDefinitionProjector.INSTANCE,
                        persistence.eventPublisher()
                ),
                new RuntimeMiddlewareChain(List.of(persistence.boundaryMiddleware()))
        );
    }

    private AgentExecutionRequest request() {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        "run-double-guard",
                        "session-double-guard",
                        List.of(AgentMessage.user("run")),
                        2,
                        Set.of(),
                        Set.of()
                ),
                "request-double-guard",
                77L
        );
    }
}
