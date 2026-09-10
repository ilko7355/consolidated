package com.ilko.tournament.service;

import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.MatchStatus;
import com.ilko.tournament.enums.TournamentFormat;
import com.ilko.tournament.enums.TournamentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BracketLifecycleSimulationTest {

    private BracketGenerator bracketGenerator;

    @BeforeEach
    void setUp() {
        bracketGenerator = new BracketGenerator();
    }

    private Tournament createTournament() {
        Tournament t = new Tournament();
        t.setId(100L);
        t.setName("Championship");
        t.setFormat(TournamentFormat.ELIMINATION);
        t.setStatus(TournamentStatus.IN_PROGRESS);
        return t;
    }

    private List<Participant> createParticipants(int count) {
        List<Participant> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Participant p = new Participant();
            p.setId((long) i);
            p.setName("Participant " + i);
            list.add(p);
        }
        return list;
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
    @DisplayName("Structural Invariant: No null-vs-null match remains PENDING for any participant count 2..16")
    void testStructuralInvariants(int count) {
        Tournament tournament = createTournament();
        List<Participant> participants = createParticipants(count);

        List<TournamentMatch> matches = bracketGenerator.generate(tournament, participants, m -> {});

        assertNotNull(matches);
        for (TournamentMatch m : matches) {
            if (m.getParticipant1() == null && m.getParticipant2() == null) {
                assertEquals(MatchStatus.COMPLETED, m.getStatus(),
                        "Match in round " + m.getRoundNumber() + " order " + m.getMatchNumber() + " is null vs null but not COMPLETED");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 5, 6, 7, 10, 16})
    @DisplayName("Complete Lifecycle Simulation: All required matches reach COMPLETED, producing one champion")
    void testCompleteTournamentLifecycle(int count) {
        Tournament tournament = createTournament();
        List<Participant> participants = createParticipants(count);

        List<TournamentMatch> matches = bracketGenerator.generate(tournament, participants, m -> {});
        int maxRounds = matches.stream().mapToInt(TournamentMatch::getRoundNumber).max().orElse(1);

        for (int round = 1; round <= maxRounds; round++) {
            final int r = round;
            List<TournamentMatch> roundMatches = matches.stream()
                    .filter(m -> m.getRoundNumber() == r)
                    .collect(Collectors.toList());

            for (TournamentMatch m : roundMatches) {
                if (m.getStatus() == MatchStatus.READY) {
                    Participant winner = m.getParticipant1();
                    m.setScore1(2);
                    m.setScore2(0);
                    m.setWinner(winner);
                    m.setStatus(MatchStatus.COMPLETED);

                    bracketGenerator.advance(m, winner);

                    TournamentMatch next = m.getNextMatch();
                    if (next != null) {
                        propagateNextMatch(matches, next);
                    }
                }
            }
        }

        boolean allCompleted = matches.stream().allMatch(m -> m.getStatus() == MatchStatus.COMPLETED);
        assertTrue(allCompleted, "All bracket matches must reach COMPLETED status");

        TournamentMatch finalMatch = matches.stream()
                .filter(m -> m.getRoundNumber() == maxRounds)
                .findFirst()
                .orElseThrow();

        assertNotNull(finalMatch.getWinner(), "Final match must have exactly one champion");
        assertTrue(participants.contains(finalMatch.getWinner()), "Champion must be one of the original participants");
    }

    private void propagateNextMatch(List<TournamentMatch> allMatches, TournamentMatch match) {
        if (match.getStatus() == MatchStatus.COMPLETED) {
            return;
        }
        Participant p1 = match.getParticipant1();
        Participant p2 = match.getParticipant2();

        if (p1 != null && p2 != null) {
            match.setStatus(MatchStatus.READY);
        } else if (p1 != null && p2 == null) {
            TournamentMatch feeder2 = findFeeder(allMatches, match, false);
            if (feeder2 != null && feeder2.getStatus() == MatchStatus.COMPLETED && feeder2.getWinner() == null) {
                match.setStatus(MatchStatus.COMPLETED);
                match.setWinner(p1);
                bracketGenerator.advance(match, p1);
                if (match.getNextMatch() != null) {
                    propagateNextMatch(allMatches, match.getNextMatch());
                }
            }
        } else if (p1 == null && p2 != null) {
            TournamentMatch feeder1 = findFeeder(allMatches, match, true);
            if (feeder1 != null && feeder1.getStatus() == MatchStatus.COMPLETED && feeder1.getWinner() == null) {
                match.setStatus(MatchStatus.COMPLETED);
                match.setWinner(p2);
                bracketGenerator.advance(match, p2);
                if (match.getNextMatch() != null) {
                    propagateNextMatch(allMatches, match.getNextMatch());
                }
            }
        }
    }

    private TournamentMatch findFeeder(List<TournamentMatch> matches, TournamentMatch parent, boolean left) {
        return matches.stream()
                .filter(m -> m.getNextMatch() != null && m.getNextMatch().equals(parent))
                .filter(m -> left ? (m.getMatchNumber() % 2 != 0) : (m.getMatchNumber() % 2 == 0))
                .findFirst()
                .orElse(null);
    }
}