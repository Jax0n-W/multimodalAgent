package com.multimodalAgent.agent.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class AgentRuntimeMySqlMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("agent_runtime_test");

    @Test
    void shouldMigrateAnEmptyMySqlDatabase() throws Exception {
        MigrateResult result = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertEquals(1, result.migrationsExecuted);

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM information_schema.tables
                     WHERE table_schema = ?
                       AND table_name IN ('agent_runs', 'agent_steps', 'tool_executions')
                     """)) {
            statement.setString(1, MYSQL.getDatabaseName());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertEquals(3, resultSet.getInt(1));
            }
        }
    }
}
