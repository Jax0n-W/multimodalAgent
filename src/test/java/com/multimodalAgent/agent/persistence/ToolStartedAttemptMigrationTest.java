package com.multimodalAgent.agent.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolStartedAttemptMigrationTest {

    @Test
    void v8BackfillsOnlyStatusesThatProveAtLeastOneStartedAttempt() throws Exception {
        String url = "jdbc:h2:mem:tool-attempt-v8;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("7"))
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO agent_runs (
                        run_id, request_id, session_id, status, phase,
                        created_at, updated_at, user_id, current_iteration, version
                    ) VALUES (
                        'migration-run', 'migration-request', 'migration-session',
                        'RUNNING', 'TOOL_RUNNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        1, 1, 0
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent_steps (
                        step_id, run_id, iteration, step_type, status,
                        created_at, step_index
                    ) VALUES (
                        'migration-step', 'migration-run', 1, 'TOOL', 'RUNNING',
                        CURRENT_TIMESTAMP, 1
                    )
                    """);
            for (String status : new String[]{
                    "PLANNED", "STARTED", "UNKNOWN", "SUCCEEDED",
                    "FAILED", "BLOCKED", "CANCELLED"
            }) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO tool_executions (
                            execution_id, run_id, step_id, tool_call_id, tool_name,
                            status, created_at, updated_at, version
                        ) VALUES (?, 'migration-run', 'migration-step', ?, 'tool', ?,
                                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                        """)) {
                    insert.setString(1, "execution-" + status.toLowerCase());
                    insert.setString(2, "call-" + status.toLowerCase());
                    insert.setString(3, status);
                    insert.executeUpdate();
                }
            }
        }

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement query = connection.prepareStatement("""
                     SELECT status, started_attempt_count
                     FROM tool_executions
                     ORDER BY id
                     """);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                counts.put(rows.getString("status"), rows.getLong("started_attempt_count"));
            }
        }

        assertEquals(0L, counts.get("PLANNED"));
        assertEquals(1L, counts.get("STARTED"));
        assertEquals(1L, counts.get("UNKNOWN"));
        assertEquals(1L, counts.get("SUCCEEDED"));
        assertEquals(1L, counts.get("FAILED"));
        assertEquals(0L, counts.get("BLOCKED"));
        assertEquals(0L, counts.get("CANCELLED"));
    }
}
