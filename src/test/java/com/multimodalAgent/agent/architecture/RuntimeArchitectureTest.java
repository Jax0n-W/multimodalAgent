package com.multimodalAgent.agent.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class RuntimeArchitectureTest {

    private static final String RUNTIME = "com.multimodalAgent.agent.runtime..";
    private final JavaClasses classes = new ClassFileImporter()
            .importPackages("com.multimodalAgent.agent.runtime");

    @Test
    void runtimeMustNotDependOnApplicationOrInfrastructurePackages() {
        noClasses().that().resideInAPackage(RUNTIME)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.multimodalAgent.agent.controller..",
                        "com.multimodalAgent.agent.service..",
                        "com.multimodalAgent.agent.repository..",
                        "com.multimodalAgent.agent.persistence..",
                        "com.multimodalAgent.agent.adapter.."
                )
                .check(classes);
    }

    @Test
    void runtimeMustNotDependOnJpaOrHibernate() {
        noClasses().that().resideInAPackage(RUNTIME)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..",
                        "org.hibernate.."
                )
                .check(classes);
    }

    @Test
    void runtimeMustNotDependOnSpringDataSpringAiOrRedis() {
        noClasses().that().resideInAPackage(RUNTIME)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.data..",
                        "org.springframework.ai..",
                        "org.springframework.data.redis.."
                )
                .check(classes);
    }
}
