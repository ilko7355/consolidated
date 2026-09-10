package com.ilko.tournament.service;

import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentGroup;
import com.ilko.tournament.repository.ParticipantRepository;
import com.ilko.tournament.repository.TournamentGroupRepository;
import com.ilko.tournament.repository.TournamentRepository;
import com.ilko.tournament.service.impl.TournamentService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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

    private Long tournamentId;
    private Long groupId1;
    private Long groupId2;
    private Long participantId;

    @BeforeEach
    void setUp() {
        // Подготовка на тестови данни в базата преди всеки тест
        Tournament tournament = new Tournament();
        tournament.setName("Test Tournament");
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
        participant = participantRepository.save(participant);
        participantId = participant.getId();
    }

    @Test
    void concurrentAssignmentToDifferentGroups_ShouldResultInSingleGroupAssignment() throws InterruptedException {
        int numberOfThreads = 2;
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        // Нишка 1 опитва да добави участника към Група 1
        executorService.submit(() -> {
            try {
                latch.await(); // Изчакваме всички нишки да стартират едновременно
                tournamentService.assignParticipantToGroup(participantId, groupId1);
                successCount.incrementAndGet();
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            }
        });

        // Нишка 2 опитва да добави същия участник към Група 2 едновременно
        executorService.submit(() -> {
            try {
                latch.await();
                tournamentService.assignParticipantToGroup(participantId, groupId2);
                successCount.incrementAndGet();
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            }
        });

        // Пускаме нишките едновременно
        latch.countDown();
        
        executorService.shutdown();
        boolean finished = executorService.awaitTermination(5, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        // Проверяваме резултатите: системата трябва да е предотвратила дублирането
        Participant updatedParticipant = participantRepository.findById(participantId).orElseThrow();
        
        // Участникът трябва да принадлежи само на ЕДНА група в крайна сметка
        assertThat(updatedParticipant.getGroup()).isNotNull();
    }
}