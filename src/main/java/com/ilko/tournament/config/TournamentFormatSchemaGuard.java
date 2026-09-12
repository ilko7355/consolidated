package com.ilko.tournament.config;

import com.ilko.tournament.enums.TournamentFormat;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Keeps the {@code tournaments.format} column able to store every {@link TournamentFormat} value.
 *
 * <p>Why this exists: Hibernate maps a {@code @Enumerated(STRING)} field to a native MySQL
 * {@code ENUM(...)} column. {@code spring.jpa.hibernate.ddl-auto=update} adds missing columns, but it
 * never widens an existing one - so a database created before a new format was introduced keeps a column
 * that physically cannot hold the new value. The application starts up perfectly happily and then fails
 * at the first attempt to create a tournament in that format, with a raw {@code Data truncated for column
 * 'format'} error. That is exactly what happened when DOUBLE_ELIMINATION was added.</p>
 *
 * <p>Widening an enum is a non-destructive change - no existing row can become invalid - so where the
 * configuration already lets Hibernate change the schema ({@code ddl-auto=update} or {@code create*}),
 * this runs the {@code ALTER TABLE} itself and logs it. Under {@code validate} (production) nothing is
 * altered behind the operator's back: startup fails with a message pointing at the migration script.</p>
 *
 * <p>Like {@link ParticipantSchemaGuard} this reads the real schema over plain JDBC, so it is unaffected
 * by the mapping problem it is checking for. A brand-new database needs nothing: Hibernate creates the
 * column with all the values already in it.</p>
 */
@Component
@RequiredArgsConstructor
@Order(1) // after ParticipantSchemaGuard, before demo data and anything else that writes tournaments
public class TournamentFormatSchemaGuard implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(TournamentFormatSchemaGuard.class);
    private static final String MIGRATION = "docs/migrations/2026-09-double-elimination.sql";

    private final DataSource dataSource;
    private final Environment environment;

    @Override
    public void run(String... args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String schema = connection.getCatalog();
            if (!tableExists(connection, schema)) {
                return; // fresh database - Hibernate creates the column with every value
            }
            String columnType = columnType(connection, schema);
            if (columnType == null || !columnType.toLowerCase(Locale.ROOT).startsWith("enum(")) {
                return; // not a native enum (another database, or already a plain string column)
            }

            List<String> missing = new ArrayList<>();
            for (TournamentFormat format : TournamentFormat.values()) {
                if (!columnType.contains("'" + format.name() + "'")) {
                    missing.add(format.name());
                }
            }
            if (missing.isEmpty()) {
                return;
            }
            if (!schemaChangesAllowed()) {
                throw new IllegalStateException(
                        "The 'tournaments.format' column cannot store " + String.join(", ", missing)
                                + " (it is " + columnType + "). Run " + MIGRATION
                                + " against this database before starting the application again.");
            }
            widen(connection);
            log.info("Widened tournaments.format so it can store {} - see {} for the same change as SQL.",
                    String.join(", ", missing), MIGRATION);
        }
    }

    private void widen(Connection connection) throws Exception {
        // Sorted, because that is the order Hibernate itself generates: an upgraded column then has
        // exactly the same definition as one created from scratch, which ddl-auto=validate relies on.
        String values = java.util.Arrays.stream(TournamentFormat.values())
                .map(format -> "'" + format.name() + "'")
                .sorted()
                .collect(Collectors.joining(","));
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE tournaments MODIFY COLUMN format ENUM(" + values + ") NOT NULL");
        }
    }

    /** Only where the configuration already allows Hibernate to change the schema. */
    private boolean schemaChangesAllowed() {
        String ddlAuto = environment.getProperty("spring.jpa.hibernate.ddl-auto", "none");
        return ddlAuto.equals("update") || ddlAuto.startsWith("create");
    }

    private boolean tableExists(Connection connection, String schema) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getTables(schema, null, "tournaments", new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private String columnType(Connection connection, String schema) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT column_type FROM information_schema.columns WHERE table_schema = '" + schema
                             + "' AND table_name = 'tournaments' AND column_name = 'format'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
