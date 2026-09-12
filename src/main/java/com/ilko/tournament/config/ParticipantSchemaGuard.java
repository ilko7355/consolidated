package com.ilko.tournament.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Verifies, on startup, that the {@code participants} table actually has the
 * {@code tournament_id} / {@code name_lower} columns this application requires.
 *
 * <p>Why this exists: {@code spring.jpa.hibernate.ddl-auto=update} does NOT halt application
 * startup when one of its generated ALTER TABLE statements fails (Hibernate's
 * {@code hbm2ddl.halt_on_error} defaults to false - the failure is only logged). Against an
 * older local database that still has the pre-migration {@code participants} schema and existing
 * rows, adding these columns as {@code NOT NULL} would be rejected by MySQL (no default value for
 * existing rows) - but the application would still finish starting up normally, and the failure
 * would only surface later as a confusing raw SQL error the first time someone tries to register a
 * participant.</p>
 *
 * <p>This check runs immediately after startup, queries the real schema directly via JDBC
 * (bypassing Hibernate/JPA entirely, so it is unaffected by the very mapping problem it is
 * checking for), and fails fast with a clear, actionable message pointing at the authoritative
 * migration, {@code docs/migrations/V001__participants_tournament_relationship.sql}, if the
 * columns are missing on a non-empty table. A brand-new/empty database is unaffected - Hibernate
 * creates the table correctly from scratch, so this check passes immediately.</p>
 */
@Component
@RequiredArgsConstructor
@Order(0) // run before anything else that might touch the participants table
public class ParticipantSchemaGuard implements CommandLineRunner {
    private final DataSource dataSource;

    @Override
    public void run(String... args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String schema = connection.getCatalog();
            DatabaseMetaData metaData = connection.getMetaData();

            if (!tableExists(metaData, schema, "participants")) {
                return; // fresh database - Hibernate will create the table correctly on its own
            }

            boolean hasTournamentId = columnExists(metaData, schema, "participants", "tournament_id");
            boolean hasNameLower = columnExists(metaData, schema, "participants", "name_lower");
            if (hasTournamentId && hasNameLower) {
                long incompleteRows = countIncompleteRows(connection, "participants");
                if (incompleteRows == 0) {
                    return; // already migrated (or freshly created) - nothing to do
                }
                throw migrationRequired(incompleteRows,
                        "the required columns exist but contain NULL values");
            }

            long existingRows = countRows(connection, "participants");
            if (existingRows == 0) {
                // Table exists but is empty (e.g. mid-migration on a database that never had data).
                // Hibernate can safely add NOT NULL columns to an empty table, so this is fine.
                return;
            }

                throw migrationRequired(existingRows,
                    "the required 'tournament_id' and/or 'name_lower' column(s) are missing " +
                    "(tournament_id present: " + hasTournamentId + ", name_lower present: " + hasNameLower + ")");
        }
    }

    private boolean tableExists(DatabaseMetaData metaData, String schema, String tableName) throws Exception {
        try (ResultSet rs = metaData.getTables(schema, null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private boolean columnExists(DatabaseMetaData metaData, String schema, String tableName, String columnName) throws Exception {
        try (ResultSet rs = metaData.getColumns(schema, null, tableName, columnName)) {
            return rs.next();
        }
    }

    private long countRows(Connection connection, String tableName) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countIncompleteRows(Connection connection, String tableName) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + tableName
                     + " WHERE tournament_id IS NULL OR name_lower IS NULL")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private IllegalStateException migrationRequired(long rows, String reason) {
        return new IllegalStateException(
            "Your 'participants' table has " + rows + " existing row(s) where " + reason + ". " +
                "Run docs/migrations/V001__participants_tournament_relationship.sql against this " +
                "database BEFORE starting the application again. If you're migrating from an older " +
                "tournament_participants join table and want to inspect legacy rows first, the " +
                "read-only docs/migrate-participants-table.sql diagnostic can help you find them - " +
                "see README.md, section 1 'Configure the database'."
        );
    }
}
