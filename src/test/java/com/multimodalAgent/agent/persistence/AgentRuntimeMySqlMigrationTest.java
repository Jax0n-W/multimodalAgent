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

        assertEquals(6, result.migrationsExecuted);

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM information_schema.tables
                     WHERE table_schema = ?
                       AND table_name IN (
                           'agent_runs',
                           'agent_steps',
                           'tool_executions',
                           'agent_runtime_config_snapshots',
                           'agent_recovery_checkpoints',
                           'tool_reconciliation_attempts'
                       )
                     """)) {
            statement.setString(1, MYSQL.getDatabaseName());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertEquals(6, resultSet.getInt(1));
            }
        }

        try (Connection connection = MYSQL.createConnection("")) {
            assertColumns(connection, "agent_runs", List.of(
                    "user_id", "current_iteration", "version", "runtime_config_snapshot_id"
            ));
            assertColumns(connection, "agent_steps", List.of("step_index"));
            assertColumns(connection, "tool_executions", List.of(
                    "version",
                    "recovery_contract_id",
                    "recovery_contract_schema_version",
                    "recovery_contract_version",
                    "replay_semantics",
                    "reconciliation_supported",
                    "reconciliation_strategy_id"
            ));
            assertColumns(connection, "agent_runtime_config_snapshots", List.of(
                    "snapshot_id", "schema_version", "config_hash", "config_json", "created_at"
            ));
            assertColumns(connection, "agent_recovery_checkpoints", List.of(
                    "id", "checkpoint_id", "run_id", "checkpoint_sequence",
                    "schema_version", "iteration", "boundary", "state_json",
                    "runtime_config_snapshot_id", "created_at"
            ));
            assertColumns(connection, "tool_reconciliation_attempts", List.of(
                    "reconciliation_id", "run_id", "tool_execution_id", "tool_call_id",
                    "attempt_no", "contract_id", "strategy_id", "status", "outcome",
                    "external_reference", "evidence_summary", "error_code", "error_message",
                    "started_at", "completed_at", "created_at"
            ));

            assertUniqueIndex(connection, "agent_runs", "uk_agent_runs_request_id", List.of("request_id"));
            assertUniqueIndex(connection, "agent_steps", "uk_agent_steps_run_step_index",
                    List.of("run_id", "step_index"));
            assertUniqueIndex(connection, "tool_executions", "uk_tool_executions_run_call",
                    List.of("run_id", "tool_call_id"));
            assertUniqueIndex(
                    connection,
                    "agent_runtime_config_snapshots",
                    "uk_agent_runtime_config_snapshots_hash",
                    List.of("config_hash")
            );
            assertForeignKey(
                    connection,
                    "agent_runs",
                    "fk_agent_runs_runtime_config_snapshot",
                    "runtime_config_snapshot_id",
                    "agent_runtime_config_snapshots",
                    "snapshot_id"
            );
            assertUniqueIndex(
                    connection,
                    "agent_recovery_checkpoints",
                    "uk_agent_recovery_checkpoints_identity",
                    List.of("checkpoint_id")
            );
            assertForeignKey(
                    connection,
                    "agent_recovery_checkpoints",
                    "fk_agent_recovery_checkpoints_run",
                    "run_id",
                    "agent_runs",
                    "run_id"
            );
            assertForeignKey(
                    connection,
                    "agent_recovery_checkpoints",
                    "fk_agent_recovery_checkpoints_runtime_config_snapshot",
                    "runtime_config_snapshot_id",
                    "agent_runtime_config_snapshots",
                    "snapshot_id"
            );
            assertUniqueIndex(
                    connection,
                    "tool_reconciliation_attempts",
                    "uk_tool_reconciliation_attempts_sequence",
                    List.of("tool_execution_id", "attempt_no")
            );
            assertForeignKey(
                    connection,
                    "tool_reconciliation_attempts",
                    "fk_tool_reconciliation_attempts_execution",
                    "tool_execution_id",
                    "tool_executions",
                    "execution_id"
            );
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

    private void assertForeignKey(
            Connection connection,
            String tableName,
            String constraintName,
            String columnName,
            String referencedTable,
            String referencedColumn
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name, referenced_table_name, referenced_column_name
                FROM information_schema.key_column_usage
                WHERE table_schema = ?
                  AND table_name = ?
                  AND constraint_name = ?
                """)) {
            statement.setString(1, MYSQL.getDatabaseName());
            statement.setString(2, tableName);
            statement.setString(3, constraintName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(columnName, resultSet.getString("column_name"));
                assertEquals(referencedTable, resultSet.getString("referenced_table_name"));
                assertEquals(referencedColumn, resultSet.getString("referenced_column_name"));
            }
        }
    }
}
