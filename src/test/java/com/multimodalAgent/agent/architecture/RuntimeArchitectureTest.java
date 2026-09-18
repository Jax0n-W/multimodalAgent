package com.multimodalAgent.agent.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class RuntimeArchitectureTest {

    private static final String RUNTIME = "com.multimodalAgent.agent.runtime..";
    private static final String STREAM = "com.multimodalAgent.agent.stream..";
    private static final String COORDINATION = "com.multimodalAgent.agent.coordination..";
    private static final String COORDINATION_DOMAIN = "com.multimodalAgent.agent.coordination";
    private static final String COORDINATION_REDIS =
            "com.multimodalAgent.agent.coordination.redis..";
    private static final String COORDINATION_INTEGRATION =
            "com.multimodalAgent.agent.coordination.integration..";
    private static final String COORDINATION_WATCHDOG =
            "com.multimodalAgent.agent.coordination.watchdog..";
    private final JavaClasses runtimeClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.multimodalAgent.agent.runtime");
    private final JavaClasses coordinationClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.multimodalAgent.agent.coordination");
    private final JavaClasses streamClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.multimodalAgent.agent.stream");

    @Test
    void runtimeMustNotDependOnApplicationOrInfrastructurePackages() {
        noClasses().that().resideInAPackage(RUNTIME)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.multimodalAgent.agent.controller..",
                        "com.multimodalAgent.agent.service..",
                        "com.multimodalAgent.agent.repository..",
                        "com.multimodalAgent.agent.persistence..",
                        "com.multimodalAgent.agent.adapter..",
                        COORDINATION
                )
                .check(runtimeClasses);
    }

    @Test
    void runtimeMustNotDependOnJpaOrHibernate() {
        noClasses().that().resideInAPackage(RUNTIME)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..",
                        "org.hibernate.."
                )
                .check(runtimeClasses);
    }

    @Test
    void runtimeMustNotDependOnSpringDataSpringAiOrRedis() {
        noClasses().that().resideInAPackage(RUNTIME)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.data..",
                        "org.springframework.ai..",
                        "org.springframework.data.redis..",
                        "io.lettuce..",
                        "redis.clients.jedis.."
                )
                .check(runtimeClasses);
    }

    @Test
    void runtimeMustNotDependOnLiveStreamingOrTransportTypes() {
        noClasses().that().resideInAPackage(RUNTIME)
                .should().dependOnClassesThat().resideInAnyPackage(
                        STREAM,
                        "org.springframework.web..",
                        "org.springframework.http..",
                        "reactor.."
                )
                .check(runtimeClasses);
    }

    @Test
    void liveStreamContractMustRemainTransportAndInfrastructureNeutral() {
        noClasses().that().resideInAPackage(STREAM)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.multimodalAgent.agent.controller..",
                        "com.multimodalAgent.agent.persistence..",
                        COORDINATION,
                        "org.springframework..",
                        "reactor..",
                        "io.lettuce..",
                        "redis.clients.jedis.."
                )
                .check(streamClasses);
    }

    @Test
    void coordinationContractsMustNotDependOnRedisClientTypes() {
        noClasses().that().resideInAPackage(COORDINATION_DOMAIN)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.data.redis..",
                        "io.lettuce..",
                        "redis.clients.jedis.."
                )
                .check(coordinationClasses);
    }

    @Test
    void coordinationDomainMustNotDependOnRedisAdapter() {
        noClasses().that().resideInAPackage(COORDINATION_DOMAIN)
                .should().dependOnClassesThat().resideInAPackage(COORDINATION_REDIS)
                .check(coordinationClasses);
    }

    @Test
    void coordinationIntegrationMustNotDependOnRedisClientOrAdapterTypes() {
        noClasses().that().resideInAPackage(COORDINATION_INTEGRATION)
                .should().dependOnClassesThat().resideInAnyPackage(
                        COORDINATION_REDIS,
                        "org.springframework.data.redis..",
                        "io.lettuce..",
                        "redis.clients.jedis.."
                )
                .check(coordinationClasses);
    }

    @Test
    void redisAdapterMustNotDependOnCoordinationIntegrationOrPersistence() {
        noClasses().that().resideInAPackage(COORDINATION_REDIS)
                .should().dependOnClassesThat().resideInAnyPackage(
                        COORDINATION_INTEGRATION,
                        "com.multimodalAgent.agent.persistence.."
                )
                .check(coordinationClasses);
    }

    @Test
    void watchdogMustNotDependOnRedisIntegrationPersistenceOrRuntimeCore() {
        noClasses().that().resideInAPackage(COORDINATION_WATCHDOG)
                .should().dependOnClassesThat().resideInAnyPackage(
                        COORDINATION_REDIS,
                        COORDINATION_INTEGRATION,
                        "com.multimodalAgent.agent.persistence..",
                        RUNTIME
                )
                .check(coordinationClasses);
    }
}
