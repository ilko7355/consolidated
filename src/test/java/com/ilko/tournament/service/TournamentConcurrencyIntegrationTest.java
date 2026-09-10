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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TournamentConcurrencyIntegrationTest {

    @Autowired
    private TournamentService tournamentService;

    @Autowired
    private ParticipantRepository participantRepository;

    @Autowired
    private TournamentGroupRepository groupRepository;

    @Autowired
    private TournamentRepository tournamentRepository;

    @Autowired
    private AppUserRepository userRepository;

    private Long tournamentId;
    private Long groupId1;
    private Long groupId2;
    private Long participantId;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        // Създаваме потребител-организатор за автентикация
        AppUser organizer = new AppUser();
        organizer.setUsername("testOrganizer");
        organizer.setPassword("password");
        organizer = userRepository.save(organizer);

        auth = new UsernamePasswordAuthenticationToken("testOrganizer", "password", Collections.emptyList());

        // Подготовка на турнир във формат GROUPS и статус REGISTRATION
        Tournament tournament = new Tournament();
        tournament.setName("Test Tournament");
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
        group1 = groupRepository.save(group1);
        groupId1 = group1.getId();

        TournamentGroup group2 = new TournamentGroup();
        group2.setName("Group B");
        group2.setTournament(tournament);
        group2 = groupRepository.save(group2);
        groupId2 = group2.getId();

        Participant participant = new Participant();
        participant.setName("John Doe");
        participant.setTournament(tournament);
        participant = participantRepository.save(participant);
        participantId = participant.getId();
    }

    @Test
    void concurrentAssignmentToDifferentGroups_ShouldHandleRaceConditions() throws InterruptedException {
        int numberOfThreads = 2;
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        // Нишка 1: опитва да добави участника към Група 1
        executorService.submit(() -> {
            try {
                latch.await();
                tournamentService.assignParticipant(tournamentId, groupId1, participantId, auth);
                successCount.incrementAndGet();
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            }
        });

        // Нишка 2: опитва да добави същия участник към Група 2 едновременно
        executorService.submit(() -> {
            try {
                latch.await();
                tournamentService.assignParticipant(tournamentId, groupId2, participantId, auth);
                successCount.incrementAndGet();
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            }
        });

        latch.countDown();
        executorService.shutdown();
        boolean finished = executorService.awaitTermination(5, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        // Проверяваме финалното състояние на базата данни
        Participant updatedParticipant = participantRepository.findById(participantId).orElseThrow();
        assertThat(updatedParticipant.getGroup()).isNotNull();
    }
}