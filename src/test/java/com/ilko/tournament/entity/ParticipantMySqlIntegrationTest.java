package com.ilko.tournament.entity;

import com.ilko.tournament.enums.Role;
import com.ilko.tournament.enums.TournamentFormat;
import com.ilko.tournament.repository.AppUserRepository;
import com.ilko.tournament.repository.ParticipantRepository;
import com.ilko.tournament.repository.TournamentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real MySQL verification for the participant uniqueness contract.
 *
 * <p>Enable explicitly by supplying TEST_DB_USERNAME and TEST_DB_PASSWORD. The
 * test uses a dedicated database configured through TEST_DB_NAME and creates a
 * fresh schema for the test context.</p>
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@EnabledIf("mysqlTestDatabaseConfigured")
class ParticipantMySqlIntegrationTest {
    @Autowired ParticipantRepository participants;
    @Autowired TournamentRepository tournaments;
    @Autowired AppUserRepository users;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://"
                + env("TEST_DB_HOST", "localhost") + ":"
                + env("TEST_DB_PORT", "3306") + "/"
                + env("TEST_DB_NAME", "tournament_platform_test")
                + "?createDatabaseIfNotExist=true&serverTimezone=UTC");
        registry.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", ""));
        registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", ""));
    }

    static boolean mysqlTestDatabaseConfigured() {
        return !env("TEST_DB_USERNAME", "").isBlank()
                && System.getenv("TEST_DB_PASSWORD") != null;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void mysqlRejectsCaseInsensitiveDuplicateWithinOneTournamentButAllowsAnotherTournament() {
        AppUser organizer = new AppUser();
        organizer.setUsername("mysql-test-organizer");
        organizer.setEmail("mysql-test-organizer@example.com");
        organizer.setPassword("not-used");
        organizer.setRole(Role.ORGANIZER);
        users.saveAndFlush(organizer);

        Tournament tournamentA = tournament("Tournament A", organizer);
        Tournament tournamentB = tournament("Tournament B", organizer);
        tournaments.saveAndFlush(tournamentA);
        tournaments.saveAndFlush(tournamentB);

        Participant first = participant("Team Alpha", tournamentA);
        participants.saveAndFlush(first);

        Participant differentCase = participant("team alpha", tournamentA);
        assertThrows(DataIntegrityViolationException.class,
                () -> participants.saveAndFlush(differentCase),
                "MySQL must enforce case-insensitive uniqueness within one tournament");

        Participant sameNameElsewhere = participant("Team Alpha", tournamentB);
        participants.saveAndFlush(sameNameElsewhere);

        assertEquals("team alpha", first.getNameLower());
        assertEquals(tournamentA.getId(), first.getTournament().getId());
        assertEquals(tournamentB.getId(), sameNameElsewhere.getTournament().getId());
    }

    private Tournament tournament(String name, AppUser organizer) {
        Tournament tournament = new Tournament();
        tournament.setName(name);
        tournament.setDescription("MySQL integration test");
        tournament.setFormat(TournamentFormat.ELIMINATION);
        tournament.setStartDate(LocalDate.of(2099, 1, 1));
        tournament.setEndDate(LocalDate.of(2099, 1, 2));
        tournament.setOrganizer(organizer);
        return tournament;
    }

    private Participant participant(String name, Tournament tournament) {
        Participant participant = new Participant();
        participant.setName(name);
        participant.setTournament(tournament);
        return participant;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
