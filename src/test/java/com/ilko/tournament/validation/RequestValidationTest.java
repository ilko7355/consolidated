package com.ilko.tournament.validation;

import com.ilko.tournament.dto.CreateTournamentRequest;
import com.ilko.tournament.dto.MatchResultRequest;
import com.ilko.tournament.dto.RegisterRequest;
import com.ilko.tournament.enums.TournamentFormat;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestValidationTest {
    private static Validator validator;

    @BeforeAll
    static void setUp() { validator = Validation.buildDefaultValidatorFactory().getValidator(); }

    @Test
    void acceptsValidRegistration() {
        var request = new RegisterRequest("organizer", "organizer@example.com", "password", true);

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsMissingAndInvalidRegistrationFields() {
        var request = new RegisterRequest("", "not-an-email", "short", false);
        var fields = validator.validate(request).stream().map(violation -> violation.getPropertyPath().toString()).toList();

        assertTrue(fields.contains("username"));
        assertTrue(fields.contains("email"));
        assertTrue(fields.contains("password"));
    }

    @Test
    void acceptsInclusiveFieldBoundaries() {
        var request = new RegisterRequest("u".repeat(50), "a@example.com", "p".repeat(8), false);

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsInvalidTournamentDatesAndScores() {
        var tournament = new CreateTournamentRequest("Tournament", null, TournamentFormat.ELIMINATION,
                LocalDate.now().minusDays(1), LocalDate.now(), false);
        var score = new MatchResultRequest(-1, null);

        assertFalse(validator.validate(tournament).isEmpty());
        assertEquals(2, validator.validate(score).size());
    }
}