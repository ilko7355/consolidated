package com.ilko.tournament.entity;

import com.ilko.tournament.enums.Role;
import com.ilko.tournament.enums.TournamentFormat;
import com.ilko.tournament.repository.AppUserRepository;
import com.ilko.tournament.repository.ParticipantRepository;
import com.ilko.tournament.repository.TournamentGroupRepository;
import com.ilko.tournament.repository.TournamentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real MySQL verification that deleting a GROUPS tournament also removes its
 * {@link TournamentGroup} rows and the corresponding {@code group_participants}
 * join rows - without deleting {@link Participant} rows, and without touching
 * groups/participants belonging to a different tournament.
 *
 * <p>This is the scenario that previously failed with a foreign-key constraint
 * violation: TournamentGroup.tournament had no corresponding collection on the
 * Tournament side, so deleting a Tournament left orphaned TournamentGroup rows
 * behind and MySQL rejected the delete.</p>
 *
 * <p>Enable explicitly by supplying TEST_DB_USERNAME and TEST_DB_PASSWORD (see
 * {@link ParticipantMySqlIntegrationTest} for the same pattern). Skipped
 * otherwise - never reported as passed unless it actually ran against a real
 * MySQL instance.</p>
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@EnabledIf("mysqlTestDatabaseConfigured")
class TournamentGroupDeletionMySqlIntegrationTest {
    @Autowired TournamentRepository tournaments;
    @Autowired TournamentGroupRepository groups;
    @Autowired ParticipantRepository participants;
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
    void deletingGroupsTournamentRemovesGroupsAndJoinRowsButKeepsParticipantsAndOtherTournamentIntact() {
        AppUser organizer = user("group-delete-organizer");

        Tournament tournamentA = tournament("Tournament A", organizer);
        tournamentA.setFormat(TournamentFormat.GROUPS);
        tournaments.saveAndFlush(tournamentA);

        Tournament tournamentB = tournament("Tournament B", organizer);
        tournamentB.setFormat(TournamentFormat.GROUPS);
        tournaments.saveAndFlush(tournamentB);

        Participant a1 = participant("A-One", tournamentA);
        Participant a2 = participant("A-Two", tournamentA);
        participants.saveAndFlush(a1);
        participants.saveAndFlush(a2);

        Participant b1 = participant("B-One", tournamentB);
        participants.saveAndFlush(b1);

        TournamentGroup groupA1 = new TournamentGroup();
        groupA1.setTournament(tournamentA);
        groupA1.setName("Group A1");
        groupA1.getParticipants().add(a1);
        groupA1.getParticipants().add(a2);
        groups.saveAndFlush(groupA1);

        TournamentGroup groupA2 = new TournamentGroup();
        groupA2.setTournament(tournamentA);
        groupA2.setName("Group A2");
        groups.saveAndFlush(groupA2);

        TournamentGroup groupB = new TournamentGroup();
        groupB.setTournament(tournamentB);
        groupB.setName("Group B1");
        groupB.getParticipants().add(b1);
        groups.saveAndFlush(groupB);

        Long tournamentAId = tournamentA.getId();
        Long tournamentBId = tournamentB.getId();
        Long groupBId = groupB.getId();
        Long a1Id = a1.getId();
        Long a2Id = a2.getId();
        Long b1Id = b1.getId();

        // This delete previously threw a foreign-key constraint violation because
        // TournamentGroup rows for tournamentA were left behind.
        tournaments.delete(tournamentA);
        tournaments.flush();

        assertTrue(tournaments.findById(tournamentAId).isEmpty(), "Tournament A should be deleted");
        List<TournamentGroup> remainingGroupsForA = groups.findByTournamentIdOrderByNameAsc(tournamentAId);
        assertTrue(remainingGroupsForA.isEmpty(), "All groups for Tournament A should be deleted");

        // Participants are NOT deleted just because they belonged to a deleted group.
        assertTrue(participants.findById(a1Id).isPresent(), "Participant A1 must survive group deletion");
        assertTrue(participants.findById(a2Id).isPresent(), "Participant A2 must survive group deletion");

        // Tournament B and everything under it must be completely untouched.
        assertTrue(tournaments.findById(tournamentBId).isPresent(), "Tournament B must not be affected");
        List<TournamentGroup> groupsForB = groups.findByTournamentIdOrderByNameAsc(tournamentBId);
        assertEquals(1, groupsForB.size(), "Tournament B's group must still exist");
        assertEquals(groupBId, groupsForB.get(0).getId());
        assertEquals(1, groupsForB.get(0).getParticipants().size(), "Tournament B's group_participants row must survive");
        assertTrue(participants.findById(b1Id).isPresent(), "Participant B1 must still exist");
    }

    private Tournament tournament(String name, AppUser organizer) {
        Tournament tournament = new Tournament();
        tournament.setName(name);
        tournament.setDescription("MySQL group-deletion integration test");
        tournament.setFormat(TournamentFormat.GROUPS);
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

    private AppUser user(String username) {
        AppUser organizer = new AppUser();
        organizer.setUsername(username);
        organizer.setEmail(username + "@example.com");
        organizer.setPassword("not-used");
        organizer.setRole(Role.ORGANIZER);
        return users.saveAndFlush(organizer);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
