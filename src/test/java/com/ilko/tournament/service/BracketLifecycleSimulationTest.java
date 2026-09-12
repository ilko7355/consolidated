package com.ilko.tournament.service;

import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.MatchStatus;
import com.ilko.tournament.enums.TournamentFormat;
import com.ilko.tournament.enums.TournamentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plays complete random tournaments through {@link BracketGenerator} and checks the invariants any
 * correct single-elimination bracket must keep, whatever the participant count and whatever order the
 * results arrive in.
 */
class BracketLifecycleSimulationTest {
    private final BracketGenerator bracketGenerator = new BracketGenerator();

    @ParameterizedTest(name = "{0} participants")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
    @DisplayName("Freshly generated bracket: every match is READY, a round-one BYE, or waiting for a feeder")
    void freshBracketHasNoPrematurelyDecidedMatches(int count) {
        List<TournamentMatch> matches = bracketGenerator.generate(tournament(), participants(count));

        for (TournamentMatch m : matches) {
            String where = "round " + m.getRoundNumber() + " match " + m.getMatchNumber();
            switch (m.getStatus()) {
                case READY -> assertTrue(m.getParticipant1() != null && m.getParticipant2() != null, where + " is READY without two participants");
                case COMPLETED -> assertTrue(m.getRoundNumber() == 1 && m.getWinner() != null && m.getScore1() == null, where + " completed without being a BYE");
                case PENDING -> assertTrue(m.getRoundNumber() > 1 && (m.getParticipant1() == null || m.getParticipant2() == null), where + " is PENDING for no reason");
            }
        }
    }

    @ParameterizedTest(name = "{0} participants")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 16, 17, 24, 31, 33, 64})
    @DisplayName("Random full tournament: everyone except the champion is eliminated exactly once")
    void randomTournamentEliminatesEveryoneButTheChampionExactlyOnce(int count) {
        Random random = new Random(count * 7919L);
        List<Participant> players = participants(count);
        List<TournamentMatch> matches = bracketGenerator.generate(tournament(), players);
        Map<Participant, Integer> losses = new IdentityHashMap<>();
        Map<Participant, Integer> gamesPlayed = new IdentityHashMap<>();

        List<TournamentMatch> ready = matches.stream().filter(m -> m.getStatus() == MatchStatus.READY).collect(Collectors.toCollection(ArrayList::new));
        int games = 0;
        while (!ready.isEmpty()) {
            TournamentMatch match = ready.remove(random.nextInt(ready.size())); // results may arrive in any order
            Participant winner = random.nextBoolean() ? match.getParticipant1() : match.getParticipant2();
            Participant loser = winner == match.getParticipant1() ? match.getParticipant2() : match.getParticipant1();
            match.setScore1(winner == match.getParticipant1() ? 3 : 1);
            match.setScore2(winner == match.getParticipant1() ? 1 : 3);
            match.setWinner(winner);
            match.setStatus(MatchStatus.COMPLETED);
            losses.merge(loser, 1, Integer::sum);
            gamesPlayed.merge(winner, 1, Integer::sum);
            gamesPlayed.merge(loser, 1, Integer::sum);

            ready.addAll(bracketGenerator.advance(matches, match));
            games++;
        }

        assertEquals(count - 1, games);
        assertTrue(matches.stream().allMatch(m -> m.getStatus() == MatchStatus.COMPLETED), "No match may be left pending");

        TournamentMatch finalMatch = matches.stream().max(Comparator.comparingInt(TournamentMatch::getRoundNumber)).orElseThrow();
        Participant champion = finalMatch.getWinner();
        assertNotNull(champion);
        assertFalse(losses.containsKey(champion), "The champion never lost");
        assertEquals(count - 1, losses.size(), "Every other participant was eliminated");
        assertTrue(losses.values().stream().allMatch(l -> l == 1), "Nobody is eliminated twice");

        int rounds = 32 - Integer.numberOfLeadingZeros(count - 1);
        int championGames = gamesPlayed.get(champion);
        assertTrue(championGames == rounds || championGames == rounds - 1,
                "The champion plays every round, or every round but one after a BYE (played " + championGames + " of " + rounds + ")");
    }

    private Tournament tournament() {
        Tournament t = new Tournament();
        t.setId(100L);
        t.setName("Championship");
        t.setFormat(TournamentFormat.ELIMINATION);
        t.setStatus(TournamentStatus.IN_PROGRESS);
        return t;
    }

    private List<Participant> participants(int count) {
        List<Participant> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Participant p = new Participant();
            p.setId((long) i);
            p.setName("Participant " + i);
            list.add(p);
        }
        return list;
    }
}
