package com.ilko.tournament.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void dataIntegrityViolationBecomesACleanConflictResponseNotARawSqlError() {
        // Simulates the database unique constraint (tournament_id, name_lower) firing under a race
        // condition - two concurrent requests both passed the application-level check.
        DataIntegrityViolationException dbError = new DataIntegrityViolationException(
                "Duplicate entry 'Alpha' for key 'participants.uk_participant_tournament_name'");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tournaments/1/participants");

        var response = handler.conflict(dbError, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(409, response.getBody().status());
        assertEquals("Conflict", response.getBody().error());
        assertEquals("/api/tournaments/1/participants", response.getBody().path());
        assertNotNull(response.getBody().timestamp());
        String message = response.getBody().message();
        assertFalse(message.contains("SQLIntegrityConstraintViolationException"), "Must not leak the raw SQL exception type");
        assertFalse(message.contains("uk_participant_tournament_name"), "Must not leak internal constraint/table names");
        assertFalse(message.toLowerCase().contains("sql"), "Must not leak SQL details to the client");
    }
}
