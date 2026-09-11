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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals(2, result.migrationsExecuted);

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

        try (Connection connection = MYSQL.createConnection("")) {
            assertColumns(connection, "agent_runs", List.of("user_id", "current_iteration", "version"));
            assertColumns(connection, "agent_steps", List.of("step_index"));
            assertColumns(connection, "tool_executions", List.of("version"));

            assertUniqueIndex(connection, "agent_runs", "uk_agent_runs_request_id", List.of("request_id"));
            assertUniqueIndex(connection, "agent_steps", "uk_agent_steps_run_step_index",
                    List.of("run_id", "step_index"));
            assertUniqueIndex(connection, "tool_executions", "uk_tool_executions_run_call",
                    List.of("run_id", "tool_call_id"));
        }
    }

    private void assertColumns(Connection connection, String tableName, List<String> expectedColumns)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                """)) {
            statement.setString(1, MYSQL.getDatabaseName());
            statement.setString(2, tableName);
            List<String> actualColumns = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    actualColumns.add(resultSet.getString(1));
                }
            }
            assertTrue(actualColumns.containsAll(expectedColumns));
        }
    }

    private void assertUniqueIndex(
            Connection connection,
            String tableName,
            String indexName,
            List<String> expectedColumns
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name
                FROM information_schema.statistics
                WHERE table_schema = ?
                  AND table_name = ?
                  AND index_name = ?
                  AND non_unique = 0
                ORDER BY seq_in_index
                """)) {
            statement.setString(1, MYSQL.getDatabaseName());
            statement.setString(2, tableName);
            statement.setString(3, indexName);
            List<String> actualColumns = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    actualColumns.add(resultSet.getString(1));
                }
            }
            assertEquals(expectedColumns, actualColumns);
        }
    }
}
