package com.ilko.tournament.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParticipantSchemaGuardTest {
    @Mock DataSource dataSource;
    @Mock Connection connection;
    @Mock DatabaseMetaData metaData;

    private ParticipantSchemaGuard guard;

    private void wireBasicConnection() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("tournament_platform");
        when(connection.getMetaData()).thenReturn(metaData);
        guard = new ParticipantSchemaGuard(dataSource);
    }

    private ResultSet resultSetWithNext(boolean hasNext) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(hasNext);
        return rs;
    }

    @Test
    void freshDatabaseWithNoParticipantsTablePassesImmediately() throws Exception {
        wireBasicConnection();
        doReturn(resultSetWithNext(false)).when(metaData).getTables(any(), any(), eq("participants"), any());

        assertDoesNotThrow(() -> guard.run());

        verify(metaData, never()).getColumns(any(), any(), any(), any());
    }

    @Test
    void alreadyMigratedTablePassesWithoutTouchingRowCount() throws Exception {
        wireBasicConnection();
        doReturn(resultSetWithNext(true)).when(metaData).getTables(any(), any(), eq("participants"), any());
        doReturn(resultSetWithNext(true)).when(metaData).getColumns(any(), any(), eq("participants"), eq("tournament_id"));
        doReturn(resultSetWithNext(true)).when(metaData).getColumns(any(), any(), eq("participants"), eq("name_lower"));
        Statement statement = mock(Statement.class);
        ResultSet countResult = mock(ResultSet.class);
        when(countResult.next()).thenReturn(true);
        when(countResult.getLong(1)).thenReturn(0L);
        when(statement.executeQuery(anyString())).thenReturn(countResult);
        when(connection.createStatement()).thenReturn(statement);

        assertDoesNotThrow(() -> guard.run());

        verify(statement).executeQuery("SELECT COUNT(*) FROM participants WHERE tournament_id IS NULL OR name_lower IS NULL");
    }

    @Test
    void partiallyMigratedTableWithNullRequiredValuesFailsFast() throws Exception {
        wireBasicConnection();
        doReturn(resultSetWithNext(true)).when(metaData).getTables(any(), any(), eq("participants"), any());
        doReturn(resultSetWithNext(true)).when(metaData).getColumns(any(), any(), eq("participants"), eq("tournament_id"));
        doReturn(resultSetWithNext(true)).when(metaData).getColumns(any(), any(), eq("participants"), eq("name_lower"));

        Statement statement = mock(Statement.class);
        ResultSet countResult = mock(ResultSet.class);
        when(countResult.next()).thenReturn(true);
        when(countResult.getLong(1)).thenReturn(2L);
        when(statement.executeQuery(anyString())).thenReturn(countResult);
        when(connection.createStatement()).thenReturn(statement);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard.run());
        assertTrue(ex.getMessage().contains("contain NULL values"));
        assertTrue(ex.getMessage().contains("V001__participants_tournament_relationship.sql"),
                "Must point the developer at the authoritative migration");
    }

    @Test
    void unmigratedTableWithExistingRowsFailsFastWithAnActionableMessage() throws Exception {
        wireBasicConnection();
        doReturn(resultSetWithNext(true)).when(metaData).getTables(any(), any(), eq("participants"), any());
        doReturn(resultSetWithNext(false)).when(metaData).getColumns(any(), any(), eq("participants"), eq("tournament_id"));
        doReturn(resultSetWithNext(false)).when(metaData).getColumns(any(), any(), eq("participants"), eq("name_lower"));

        Statement statement = mock(Statement.class);
        ResultSet countResult = mock(ResultSet.class);
        when(countResult.next()).thenReturn(true);
        when(countResult.getLong(1)).thenReturn(7L);
        when(statement.executeQuery(anyString())).thenReturn(countResult);
        when(connection.createStatement()).thenReturn(statement);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard.run());
        assertTrue(ex.getMessage().contains("7 existing row"));
        assertTrue(ex.getMessage().contains("V001__participants_tournament_relationship.sql"),
                "Must point the developer at the authoritative migration");
    }

    @Test
    void unmigratedButEmptyTablePassesSinceHibernateCanSafelyAlterAnEmptyTable() throws Exception {
        wireBasicConnection();
        doReturn(resultSetWithNext(true)).when(metaData).getTables(any(), any(), eq("participants"), any());
        doReturn(resultSetWithNext(false)).when(metaData).getColumns(any(), any(), eq("participants"), eq("tournament_id"));
        doReturn(resultSetWithNext(false)).when(metaData).getColumns(any(), any(), eq("participants"), eq("name_lower"));

        Statement statement = mock(Statement.class);
        ResultSet countResult = mock(ResultSet.class);
        when(countResult.next()).thenReturn(true);
        when(countResult.getLong(1)).thenReturn(0L);
        when(statement.executeQuery(anyString())).thenReturn(countResult);
        when(connection.createStatement()).thenReturn(statement);

        assertDoesNotThrow(() -> guard.run());
    }
}
