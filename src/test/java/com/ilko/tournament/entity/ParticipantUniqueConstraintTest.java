package com.ilko.tournament.entity;

import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 3 regression guard: the case-insensitive per-tournament unique constraint on Participant is the
 * database-level second line of defense behind TournamentService's application-level equalsIgnoreCase
 * check. This test does not (and cannot, without a real database in this project) prove the constraint
 * is enforced by MySQL - it proves the mapping that creates it has not been silently removed or
 * misconfigured, and that name/nameLower stay in sync the way the constraint depends on.
 */
class ParticipantUniqueConstraintTest {

    @Test
    void participantTableDeclaresACaseInsensitiveUniqueConstraintPerTournament() {
        Table table = Participant.class.getAnnotation(Table.class);
        assertNotNull(table, "Participant must declare @Table");

        UniqueConstraint constraint = Arrays.stream(table.uniqueConstraints())
                .filter(uc -> uc.name().equals("uk_participant_tournament_name"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected a uk_participant_tournament_name unique constraint on Participant - " +
                        "without it, concurrent requests can create duplicate participant names in the same tournament"));

        assertArrayEquals(new String[]{"tournament_id", "name_lower"}, constraint.columnNames(),
                "Constraint must be scoped to (tournament_id, name_lower), not just name, " +
                "or the same participant name would be blocked across different tournaments too");
    }

    @Test
    void settingNameKeepsTheLowercaseShadowColumnInSync() {
        Participant participant = new Participant();
        participant.setName("MixedCase Name");

        assertEquals("mixedcase name", participant.getNameLower());
        assertEquals("MixedCase Name", participant.getName(), "The original casing must still be preserved for display");
    }

    @Test
    void twoNamesDifferingOnlyByCaseProduceTheSameLowercaseValue() {
        Participant a = new Participant();
        a.setName("Alpha");
        Participant b = new Participant();
        b.setName("ALPHA");

        assertEquals(a.getNameLower(), b.getNameLower(),
                "This equality is exactly what the database unique constraint relies on to catch case-insensitive duplicates");
    }
}
