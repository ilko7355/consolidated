package com.ilko.tournament.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TournamentFormatSchemaGuardTest {
    private static final String COMPLETE = "enum('DOUBLE_ELIMINATION','ELIMINATION','GROUPS')";
    private static final String BEFORE_DOUBLE_ELIMINATION = "enum('ELIMINATION','GROUPS')";

    @Mock DataSource dataSource;
    @Mock Connection connection;
    @Mock DatabaseMetaData metaData;
    @Mock Statement statement;

    /** Wires a database whose tournaments.format column has the given type; null means no table at all. */
    private TournamentFormatSchemaGuard guard(String columnType, String ddlAuto) throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.getCatalog()).thenReturn("tournament_platform");
        when(connection.getMetaData()).thenReturn(metaData);

        ResultSet tables = mock(ResultSet.class);
        when(tables.next()).thenReturn(columnType != null);
        doReturn(tables).when(metaData).getTables(any(), any(), eq("tournaments"), any());

        if (columnType != null) {
            lenient().when(connection.createStatement()).thenReturn(statement);
            ResultSet column = mock(ResultSet.class);
            lenient().when(column.next()).thenReturn(true);
            lenient().when(column.getString(1)).thenReturn(columnType);
            lenient().when(statement.executeQuery(contains("information_schema.columns"))).thenReturn(column);
        }
        return new TournamentFormatSchemaGuard(dataSource, new MockEnvironment()
                .withProperty("spring.jpa.hibernate.ddl-auto", ddlAuto));
    }

    @Test
    void freshDatabaseNeedsNothing() throws Exception {
        TournamentFormatSchemaGuard guard = guard(null, "update");

        assertDoesNotThrow(() -> guard.run());
        verify(statement, never()).execute(anyString());
    }

    @Test
    void aColumnThatAlreadyHoldsEveryFormatIsLeftAlone() throws Exception {
        TournamentFormatSchemaGuard guard = guard(COMPLETE, "update");

        assertDoesNotThrow(() -> guard.run());
        verify(statement, never()).execute(anyString());
    }

    @Test
    void widensAnOlderColumnSoTheNewFormatCanBeStored() throws Exception {
        TournamentFormatSchemaGuard guard = guard(BEFORE_DOUBLE_ELIMINATION, "update");

        guard.run();

        verify(statement).execute(contains("MODIFY COLUMN format ENUM('DOUBLE_ELIMINATION','ELIMINATION','GROUPS')"));
    }

    @Test
    void underValidateItRefusesToStartInsteadOfAlteringTheSchema() throws Exception {
        TournamentFormatSchemaGuard guard = guard(BEFORE_DOUBLE_ELIMINATION, "validate");

        IllegalStateException failure = assertThrows(IllegalStateException.class, guard::run);

        assertTrue(failure.getMessage().contains("DOUBLE_ELIMINATION"), failure.getMessage());
        assertTrue(failure.getMessage().contains("2026-09-double-elimination.sql"), failure.getMessage());
        verify(statement, never()).execute(anyString());
    }

    @Test
    void aPlainStringColumnIsNotTouched() throws Exception {
        TournamentFormatSchemaGuard guard = guard("varchar(32)", "update");

        assertDoesNotThrow(() -> guard.run());
        verify(statement, never()).execute(anyString());
    }
}
