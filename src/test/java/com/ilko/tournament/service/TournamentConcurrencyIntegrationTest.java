package com.ilko.tournament.service;

import com.ilko.tournament.entity.AppUser;
import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentGroup;
import com.ilko.tournament.enums.TournamentFormat;
import com.ilko.tournament.enums.TournamentStatus;
import com.ilko.tournament.repository.AppUserRepository;
import com.ilko.tournament.repository.ParticipantRepository;
import com.ilko.tournament.repository.TournamentGroupRepository;
import com.ilko.tournament.repository.TournamentRepository;
import com.ilko.tournament.service.impl.TournamentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two concurrent requests move the same participant into different groups. Runs only against a real
 * MySQL database configured through TEST_DB_USERNAME / TEST_DB_PASSWORD (same convention as
 * {@link com.ilko.tournament.entity.ParticipantMySqlIntegrationTest}); skipped otherwise.
 */
@SpringBootTest
@EnabledIf("mysqlTestDatabaseConfigured")
class TournamentConcurrencyIntegrationTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://"
                + env("TEST_DB_HOST", "localhost") + ":"
                + env("TEST_DB_PORT", "3306") + "/"
                + env("TEST_DB_NAME", "tournament_platform_test")
                + "?createDatabaseIfNotExist=true&serverTimezone=UTC");
        registry.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", ""));
        registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", ""));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("app.demo-data", () -> "false");
    }

    static boolean mysqlTestDatabaseConfigured() {
        return !env("TEST_DB_USERNAME", "").isBlank() && System.getenv("TEST_DB_PASSWORD") != null;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    @Autowired private TournamentService tournamentService;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private TournamentGroupRepository groupRepository;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private AppUserRepository userRepository;

    private Long tournamentId;
    private Long groupId1;
    private Long groupId2;
    private Long participantId;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        String username = "organizer-" + UUID.randomUUID().toString().substring(0, 8);
        AppUser organizer = new AppUser();
        organizer.setUsername(username);
        organizer.setEmail(username + "@example.com");
        organizer.setPassword("not-used");
        organizer = userRepository.save(organizer);
        auth = new UsernamePasswordAuthenticationToken(username, "not-used", Collections.emptyList());

        Tournament tournament = new Tournament();
        tournament.setName("Concurrency Cup");
        tournament.setFormat(TournamentFormat.GROUPS);
        tournament.setStatus(TournamentStatus.REGISTRATION);
        tournament.setStartDate(LocalDate.now());
        tournament.setEndDate(LocalDate.now().plusDays(5));
        tournament.setOrganizer(organizer);
        tournament = tournamentRepository.save(tournament);
        tournamentId = tournament.getId();

        TournamentGroup group1 = new TournamentGroup();
        group1.setName("Group A");
        group1.setTournament(tournament);
        groupId1 = groupRepository.save(group1).getId();

        TournamentGroup group2 = new TournamentGroup();
        group2.setName("Group B");
        group2.setTournament(tournament);
        groupId2 = groupRepository.save(group2).getId();

        Participant participant = new Participant();
        participant.setName("John Doe");
        participant.setTournament(tournament);
        participantId = participantRepository.save(participant).getId();
    }

    @Test
    void concurrentAssignmentToDifferentGroupsLeavesTheParticipantInExactlyOneGroup() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        for (Long groupId : new Long[]{groupId1, groupId2}) {
            executorService.submit(() -> {
                try {
                    latch.await();
                    tournamentService.assignParticipant(tournamentId, groupId, participantId, auth);
                } catch (Exception ignored) {
                    // one of the two requests may legitimately lose the race
                }
            });
        }

        latch.countDown();
        executorService.shutdown();
        assertThat(executorService.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        Participant updatedParticipant = participantRepository.findById(participantId).orElseThrow();
        assertThat(updatedParticipant.getGroup()).isNotNull();
        assertThat(updatedParticipant.getGroup().getId()).isIn(groupId1, groupId2);
    }
}
