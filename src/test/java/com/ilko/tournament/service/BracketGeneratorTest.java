package com.ilko.tournament.service;

import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.MatchStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BracketGeneratorTest {
    private final BracketGenerator generator = new BracketGenerator();

    @Test
    void standardSeedOrderKeepsTheTopSeedsApartUntilTheFinal() {
        assertArrayEquals(new int[]{1, 2}, BracketGenerator.seedOrder(2));
        assertArrayEquals(new int[]{1, 4, 2, 3}, BracketGenerator.seedOrder(4));
        assertArrayEquals(new int[]{1, 8, 4, 5, 2, 7, 3, 6}, BracketGenerator.seedOrder(8));
    }

    @Test
    void threeParticipantsGiveTheTopSeedAByeAndTheFinalWaitsForTheSemiFinal() {
        List<Participant> players = participants(3);
        List<TournamentMatch> bracket = generator.generate(new Tournament(), players);

        assertEquals(3, bracket.size());
        TournamentMatch bye = match(bracket, 1, 1);
        assertEquals(MatchStatus.COMPLETED, bye.getStatus());
        assertSame(players.get(0), bye.getWinner());
        assertNull(bye.getScore1(), "A BYE is not a played match");

        TournamentMatch semiFinal = match(bracket, 1, 2);
        assertEquals(MatchStatus.READY, semiFinal.getStatus());
        assertSame(players.get(1), semiFinal.getParticipant1());
        assertSame(players.get(2), semiFinal.getParticipant2());

        TournamentMatch finalMatch = match(bracket, 2, 1);
        assertEquals(MatchStatus.PENDING, finalMatch.getStatus(), "The final must wait for the semi-final, not be awarded to the BYE winner");
        assertSame(players.get(0), finalMatch.getParticipant1());
        assertNull(finalMatch.getParticipant2());
        assertNull(finalMatch.getWinner());
    }

    @Test
    void createsALinkedSeededTreeForEightParticipants() {
        List<Participant> players = participants(8);
        List<TournamentMatch> bracket = generator.generate(new Tournament(), players);

        assertEquals(7, bracket.size());
        assertEquals(List.of(4L, 2L, 1L), List.of(
                bracket.stream().filter(m -> m.getRoundNumber() == 1).count(),
                bracket.stream().filter(m -> m.getRoundNumber() == 2).count(),
                bracket.stream().filter(m -> m.getRoundNumber() == 3).count()));
        assertTrue(bracket.stream().filter(m -> m.getRoundNumber() == 1).allMatch(m -> m.getStatus() == MatchStatus.READY));

        assertEquals(List.of("P1-P8", "P4-P5", "P2-P7", "P3-P6"), bracket.stream()
                .filter(m -> m.getRoundNumber() == 1)
                .map(m -> m.getParticipant1().getName() + "-" + m.getParticipant2().getName())
                .toList());

        assertSame(match(bracket, 2, 1), match(bracket, 1, 1).getNextMatch());
        assertSame(match(bracket, 2, 1), match(bracket, 1, 2).getNextMatch());
        assertSame(match(bracket, 2, 2), match(bracket, 1, 3).getNextMatch());
        assertSame(match(bracket, 3, 1), match(bracket, 2, 2).getNextMatch());
        assertNull(match(bracket, 3, 1).getNextMatch());
    }

    @Test
    void advancingAWinnerFillsTheRightSlotAndUnlocksTheNextMatchOnlyWhenBothSidesAreKnown() {
        List<Participant> players = participants(4);
        List<TournamentMatch> bracket = generator.generate(new Tournament(), players);
        TournamentMatch first = match(bracket, 1, 1);  // P1 v P4
        TournamentMatch second = match(bracket, 1, 2); // P2 v P3
        TournamentMatch finalMatch = match(bracket, 2, 1);

        decide(first, first.getParticipant2());
        assertTrue(generator.advance(bracket, first).isEmpty());
        assertSame(players.get(3), finalMatch.getParticipant1());
        assertEquals(MatchStatus.PENDING, finalMatch.getStatus());

        decide(second, second.getParticipant1());
        assertEquals(List.of(finalMatch), generator.advance(bracket, second));
        assertSame(players.get(1), finalMatch.getParticipant2());
        assertEquals(MatchStatus.READY, finalMatch.getStatus());
    }

    @ParameterizedTest(name = "{0} participants")
    @ValueSource(ints = {2, 3, 5, 6, 7, 9, 12, 13, 16, 17, 31, 32})
    void byesAreOnlyGivenInRoundOneAndOnlyToTheTopSeeds(int count) {
        List<Participant> players = participants(count);
        List<TournamentMatch> bracket = generator.generate(new Tournament(), players);
        int size = Integer.bitCount(count) == 1 ? count : Integer.highestOneBit(count) << 1;

        assertEquals(size - 1, bracket.size());
        List<TournamentMatch> byes = bracket.stream().filter(m -> m.getStatus() == MatchStatus.COMPLETED).toList();
        assertEquals(size - count, byes.size());
        assertTrue(byes.stream().allMatch(m -> m.getRoundNumber() == 1 && m.getWinner() != null));
        Set<Participant> byeWinners = byes.stream().map(TournamentMatch::getWinner).collect(Collectors.toSet());
        assertEquals(new HashSet<>(players.subList(0, size - count)), byeWinners);
        assertTrue(bracket.stream().filter(m -> m.getRoundNumber() > 1).noneMatch(m -> m.getStatus() == MatchStatus.COMPLETED),
                "No later-round match may be decided before anyone has played");
    }

    @ParameterizedTest(name = "{0} participants")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 11, 16, 23})
    void playingEveryReadyMatchCrownsOneChampionAfterExactlyNMinusOneGames(int count) {
        List<TournamentMatch> bracket = generator.generate(new Tournament(), participants(count));

        int played = 0;
        Optional<TournamentMatch> ready;
        while ((ready = bracket.stream().filter(m -> m.getStatus() == MatchStatus.READY).findFirst()).isPresent()) {
            TournamentMatch match = ready.get();
            assertNotNull(match.getParticipant1());
            assertNotNull(match.getParticipant2());
            decide(match, match.getParticipant2()); // the lower seed wins every time
            generator.advance(bracket, match);
            played++;
        }

        assertEquals(count - 1, played, "A knockout needs exactly one game per eliminated participant");
        assertTrue(bracket.stream().allMatch(m -> m.getStatus() == MatchStatus.COMPLETED));
        TournamentMatch finalMatch = bracket.stream().max(Comparator.comparingInt(TournamentMatch::getRoundNumber)).orElseThrow();
        assertNotNull(finalMatch.getWinner());
        assertNotNull(finalMatch.getScore1(), "The title must be decided by a played final, never by a BYE");
    }

    @Test
    void rejectsFewerThanTwoParticipants() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(new Tournament(), participants(1)));
    }

    private void decide(TournamentMatch match, Participant winner) {
        boolean firstWins = winner == match.getParticipant1();
        match.setScore1(firstWins ? 2 : 0);
        match.setScore2(firstWins ? 0 : 2);
        match.setWinner(winner);
        match.setStatus(MatchStatus.COMPLETED);
    }

    private TournamentMatch match(List<TournamentMatch> matches, int round, int matchNumber) {
        return matches.stream()
                .filter(match -> match.getRoundNumber() == round && match.getMatchNumber() == matchNumber)
                .findFirst()
                .orElseThrow();
    }

    private List<Participant> participants(int count) {
        List<Participant> participants = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            Participant participant = new Participant();
            participant.setId((long) index);
            participant.setName("P" + index);
            participants.add(participant);
        }
        return participants;
    }
}
