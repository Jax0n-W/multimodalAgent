package com.multimodalAgent.agent.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentRuntimeMySqlMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("agent_runtime_test");

    @Test
    @Order(1)
    void shouldMigrateAnEmptyMySqlDatabase() throws Exception {
        MigrateResult result = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertEquals(14, result.migrationsExecuted);

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
                           'tool_reconciliation_attempts',
                           'tool_execution_outcomes',
                           'agent_context_snapshots',
                           'chat_runtime_sessions',
                           'chat_history_projections',
                           'high_risk_notification_intents',
                           'knowledge_chunks'
                       )
                     """)) {
            statement.setString(1, MYSQL.getDatabaseName());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertEquals(12, resultSet.getInt(1));
            }
        }

        try (Connection connection = MYSQL.createConnection("")) {
            assertColumns(connection, "agent_runs", List.of(
                    "user_id", "current_iteration", "version", "runtime_config_snapshot_id",
                    "context_snapshot_id"
            ));
            assertColumns(connection, "agent_steps", List.of("step_index"));
            assertColumns(connection, "tool_executions", List.of(
                    "version",
                    "recovery_contract_id",
                    "recovery_contract_schema_version",
                    "recovery_contract_version",
                    "replay_semantics",
                    "reconciliation_supported",
                    "reconciliation_strategy_id",
                    "started_attempt_count"
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
            assertColumns(connection, "tool_execution_outcomes", List.of(
                    "execution_id", "run_id", "tool_call_id", "tool_name",
                    "result_payload", "payload_hash", "recorded_at", "schema_version"
            ));
            assertColumns(connection, "agent_context_snapshots", List.of(
                    "snapshot_id", "schema_version", "run_id", "session_id", "user_id",
                    "context_hash", "context_json", "created_at"
            ));
            assertColumns(connection, "chat_runtime_sessions", List.of(
                    "session_id", "user_id", "created_at"
            ));
            assertColumns(connection, "chat_history_projections", List.of(
                    "run_id", "session_id", "user_id", "status", "chat_message_id",
                    "error_message", "created_at", "updated_at"
            ));
            assertColumns(connection, "high_risk_notification_intents", List.of(
                    "notification_id", "report_id", "alert_record_id", "recipient", "status",
                    "attempt_count", "max_attempts", "next_attempt_at", "lease_owner",
                    "claim_token", "lease_expires_at", "last_failure_reason", "created_at",
                    "updated_at", "version"
            ));
            assertColumns(connection, "knowledge_chunks", List.of(
                    "id", "source", "source_index", "content", "embedding_json", "created_at"
            ));
            assertColumnType(connection, "knowledge_chunks", "content", "text");
            assertColumnType(connection, "agent_runtime_config_snapshots", "config_json", "longtext");
            assertColumnType(connection, "agent_recovery_checkpoints", "state_json", "longtext");
            assertColumnType(connection, "tool_execution_outcomes", "result_payload", "longtext");
            assertColumnType(connection, "agent_context_snapshots", "context_json", "longtext");

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
                    "high_risk_notification_intents",
                    "uk_high_risk_notification_report_recipient",
                    List.of("report_id", "recipient")
            );
            assertForeignKey(
                    connection,
                    "chat_history_projections",
                    "fk_chat_history_projection_run",
                    "run_id",
                    "agent_runs",
                    "run_id"
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
            assertUniqueIndex(
                    connection,
                    "tool_execution_outcomes",
                    "uk_tool_execution_outcomes_execution",
                    List.of("execution_id")
            );
            assertForeignKey(
                    connection,
                    "tool_execution_outcomes",
                    "fk_tool_execution_outcomes_execution",
                    "execution_id",
                    "tool_executions",
                    "execution_id"
            );
            assertForeignKey(
                    connection,
                    "agent_runs",
                    "fk_agent_runs_context_snapshot",
                    "context_snapshot_id",
                    "agent_context_snapshots",
                    "snapshot_id"
            );
        }
    }

    @Test
    @Order(2)
    void notificationOutboxHasMySqlUniquenessAndAtomicClaimSemantics() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement clear = connection.prepareStatement(
                     "DELETE FROM high_risk_notification_intents")) {
            clear.executeUpdate();
        }
        insertNotification("mysql-claim", 991L, "counselor@example.com");

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(
                () -> claimNotification("mysql-claim", "worker-a", "token-a"));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(
                () -> claimNotification("mysql-claim", "worker-b", "token-b"));
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);

        assertEquals(1, first.get() + second.get());
        assertThrows(SQLException.class,
                () -> insertNotification("mysql-duplicate", 991L, "counselor@example.com"));
    }

    @Test
    @Order(3)
    void staleClaimTokenCannotCrossIntoReclaimedGenerationWithSameOwner() throws Exception {
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement clear = connection.prepareStatement(
                     "DELETE FROM high_risk_notification_intents")) {
            clear.executeUpdate();
        }
        insertNotification("mysql-fenced", 992L, "fenced@example.com");
        assertEquals(1, claimNotification("mysql-fenced", "same-owner", "old-token"));
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement expire = connection.prepareStatement("""
                     UPDATE high_risk_notification_intents
                     SET status = 'FAILED_RETRYABLE', next_attempt_at = ?,
                         lease_owner = NULL, lease_expires_at = NULL, claim_token = NULL
                     WHERE notification_id = ? AND status = 'CLAIMED' AND claim_token = ?
                     """)) {
            expire.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(1)));
            expire.setString(2, "mysql-fenced");
            expire.setString(3, "old-token");
            assertEquals(1, expire.executeUpdate());
        }
        assertEquals(1, claimNotification("mysql-fenced", "same-owner", "new-token"));

        assertEquals(0, beginDelivery("mysql-fenced", "same-owner", "old-token"));
        assertEquals(1, beginDelivery("mysql-fenced", "same-owner", "new-token"));
    }

    private void insertNotification(String id, long reportId, String recipient) throws SQLException {
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO high_risk_notification_intents (
                         notification_id, report_id, recipient, status, attempt_count,
                         max_attempts, next_attempt_at, created_at, updated_at, version
                     ) VALUES (?, ?, ?, 'PENDING', 0, 3, ?, ?, ?, 0)
                     """)) {
            Timestamp now = Timestamp.from(Instant.now());
            statement.setString(1, id);
            statement.setLong(2, reportId);
            statement.setString(3, recipient);
            statement.setTimestamp(4, now);
            statement.setTimestamp(5, now);
            statement.setTimestamp(6, now);
            statement.executeUpdate();
        }
    }

    private int claimNotification(String id, String owner, String claimToken) {
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE high_risk_notification_intents
                     SET status = 'CLAIMED', lease_owner = ?, claim_token = ?,
                         lease_expires_at = ?, attempt_count = attempt_count + 1
                     WHERE notification_id = ? AND status IN ('PENDING', 'FAILED_RETRYABLE')
                       AND next_attempt_at <= ?
                     """)) {
            statement.setString(1, owner);
            statement.setString(2, claimToken);
            statement.setTimestamp(3, Timestamp.from(Instant.now().plusSeconds(60)));
            statement.setString(4, id);
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int beginDelivery(String id, String owner, String claimToken) throws SQLException {
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE high_risk_notification_intents
                     SET status = 'PROCESSING'
                     WHERE notification_id = ? AND status = 'CLAIMED'
                       AND lease_owner = ? AND claim_token = ?
                     """)) {
            statement.setString(1, id);
            statement.setString(2, owner);
            statement.setString(3, claimToken);
            return statement.executeUpdate();
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

    private void assertColumnType(
            Connection connection,
            String tableName,
            String columnName,
            String expectedType
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                  AND column_name = ?
                """)) {
            statement.setString(1, MYSQL.getDatabaseName());
            statement.setString(2, tableName);
            statement.setString(3, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(expectedType, resultSet.getString("data_type"));
            }
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
