package com.ilko.tournament.service;

import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.BracketSide;
import com.ilko.tournament.enums.MatchStatus;
import com.ilko.tournament.enums.TournamentFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DoubleEliminationGeneratorTest {
    private final DoubleEliminationGenerator generator = new DoubleEliminationGenerator();

    // --- structure ---

    @Test
    void buildsWinnersLosersAndGrandFinalForEightParticipants() {
        List<TournamentMatch> bracket = generator.generate(tournament(false), participants(8));

        assertEquals(14, bracket.size(), "A double-elimination bracket holds 2n-2 matches");
        assertEquals(7, count(bracket, BracketSide.WINNERS));
        assertEquals(6, count(bracket, BracketSide.LOSERS));
        assertEquals(1, count(bracket, BracketSide.GRAND_FINAL));

        // Winners rounds 1-3, losers rounds 4-7, grand final 8: every round number stays unique.
        assertEquals(List.of(4L, 2L, 1L), roundSizes(bracket, 1, 2, 3));
        assertEquals(List.of(2L, 2L, 1L, 1L), roundSizes(bracket, 4, 5, 6, 7));
        assertEquals(bracket.size(), bracket.stream().map(m -> m.getRoundNumber() + "/" + m.getMatchNumber()).distinct().count());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 11, 16})
    void everyMatchPointsForwardSoTheBracketCanBePersisted(int count) {
        List<TournamentMatch> bracket = generator.generate(tournament(true), participants(count));

        for (TournamentMatch match : bracket) {
            if (match.getNextMatch() != null) {
                assertTrue(match.getNextMatch().getRoundNumber() > match.getRoundNumber(),
                        "Winner link must point at a later round so foreign keys resolve when saving");
            }
            if (match.getNextLoserMatch() != null) {
                assertTrue(match.getNextLoserMatch().getRoundNumber() > match.getRoundNumber(),
                        "Loser link must point at a later round");
                assertNotNull(match.getNextLoserSlot());
            }
        }
    }

    @Test
    void losersOfTheFirstWinnersRoundMeetInTheLosersBracket() {
        List<TournamentMatch> bracket = generator.generate(tournament(false), participants(8));

        TournamentMatch first = match(bracket, 1, 1);
        TournamentMatch second = match(bracket, 1, 2);
        assertSame(match(bracket, 4, 1), first.getNextLoserMatch());
        assertSame(match(bracket, 4, 1), second.getNextLoserMatch());
        assertEquals(1, first.getNextLoserSlot());
        assertEquals(2, second.getNextLoserSlot());
    }

    @Test
    void theWinnersFinalLoserEntersTheLastLosersRoundAndCanStillReachTheGrandFinal() {
        List<TournamentMatch> bracket = generator.generate(tournament(false), participants(8));

        TournamentMatch winnersFinal = match(bracket, 3, 1);
        TournamentMatch losersFinal = match(bracket, 7, 1);
        TournamentMatch grandFinal = match(bracket, 8, 1);

        assertSame(grandFinal, winnersFinal.getNextMatch());
        assertEquals(1, winnersFinal.getNextMatchSlot());
        assertSame(losersFinal, winnersFinal.getNextLoserMatch());
        assertSame(grandFinal, losersFinal.getNextMatch());
        assertEquals(2, losersFinal.getNextMatchSlot());
        assertNull(losersFinal.getNextLoserMatch(), "A second loss eliminates");
    }

    @Test
    void twoParticipantsSendTheLoserStraightToTheGrandFinal() {
        List<Participant> players = participants(2);
        List<TournamentMatch> bracket = generator.generate(tournament(false), players);

        assertEquals(2, bracket.size());
        TournamentMatch opener = match(bracket, 1, 1);
        TournamentMatch grandFinal = match(bracket, 2, 1);
        assertEquals(MatchStatus.READY, opener.getStatus());
        assertSame(grandFinal, opener.getNextMatch());
        assertSame(grandFinal, opener.getNextLoserMatch());
        assertEquals(2, opener.getNextLoserSlot());
    }

    @Test
    void aByeInTheWinnersBracketLeavesNoOneToDropIntoTheLosersBracket() {
        List<Participant> players = participants(3);
        List<TournamentMatch> bracket = generator.generate(tournament(false), players);

        TournamentMatch bye = match(bracket, 1, 1);
        assertEquals(MatchStatus.COMPLETED, bye.getStatus());
        assertSame(players.get(0), bye.getWinner());
        assertNull(bye.getScore1(), "A BYE is not a played match");

        // Losers round 1 is fed by both first-round matches; the BYE contributes nobody, so that side
        // stays empty and the real loser advances once the other match is played - not before.
        TournamentMatch losersOpener = match(bracket, 3, 1);
        assertEquals(MatchStatus.PENDING, losersOpener.getStatus());
        assertNull(losersOpener.getParticipant1());
        assertNull(losersOpener.getParticipant2());
    }

    @Test
    void rejectsFewerThanTwoParticipants() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(tournament(false), participants(1)));
    }

    // --- playing the bracket out ---

    @Test
    void aBeatenPlayerCanComeBackThroughTheLosersBracket() {
        List<Participant> players = participants(4);
        List<TournamentMatch> bracket = generator.generate(tournament(false), players);

        // P1 beats P4, P2 beats P3, then P1 beats P2 in the winners final.
        win(bracket, match(bracket, 1, 1), players.get(0));
        win(bracket, match(bracket, 1, 2), players.get(1));
        assertEquals(MatchStatus.READY, match(bracket, 3, 1).getStatus(), "The two first-round losers meet");
        win(bracket, match(bracket, 3, 1), players.get(3));   // P4 beats P3 in the losers bracket
        win(bracket, match(bracket, 2, 1), players.get(0));   // winners final

        TournamentMatch losersFinal = match(bracket, 4, 1);
        assertEquals(MatchStatus.READY, losersFinal.getStatus());
        assertSame(players.get(3), losersFinal.getParticipant1(), "Losers-bracket survivor");
        assertSame(players.get(1), losersFinal.getParticipant2(), "Just knocked out of the winners bracket");

        win(bracket, losersFinal, players.get(1));
        TournamentMatch grandFinal = match(bracket, 5, 1);
        assertEquals(MatchStatus.READY, grandFinal.getStatus());
        assertSame(players.get(0), grandFinal.getParticipant1(), "Unbeaten winners-bracket finalist");
        assertSame(players.get(1), grandFinal.getParticipant2(), "Back from one loss");
    }

    @Test
    void theDecidingRematchIsPlayedOnlyWhenTheLosersFinalistWinsTheGrandFinal() {
        List<Participant> players = participants(4);
        List<TournamentMatch> bracket = generator.generate(tournament(true), players);
        TournamentMatch grandFinal = match(bracket, 5, 1);
        TournamentMatch decider = match(bracket, 6, 1);
        assertEquals(MatchStatus.PENDING, decider.getStatus());

        playToGrandFinal(bracket, players);
        win(bracket, grandFinal, grandFinal.getParticipant2()); // the player with one loss wins

        assertEquals(MatchStatus.READY, decider.getStatus(), "Both finalists now have one loss - play it off");
        assertSame(grandFinal.getParticipant1(), decider.getParticipant1());
        assertSame(grandFinal.getParticipant2(), decider.getParticipant2());
    }

    @Test
    void theDecidingRematchIsSkippedWhenTheUnbeatenFinalistWinsTheGrandFinal() {
        List<Participant> players = participants(4);
        List<TournamentMatch> bracket = generator.generate(tournament(true), players);
        TournamentMatch grandFinal = match(bracket, 5, 1);
        TournamentMatch decider = match(bracket, 6, 1);

        playToGrandFinal(bracket, players);
        win(bracket, grandFinal, grandFinal.getParticipant1()); // the unbeaten player wins

        assertEquals(MatchStatus.COMPLETED, decider.getStatus(), "The tournament is over, so it must not block completion");
        assertNull(decider.getWinner(), "A match that was never needed has no winner");
        assertNull(decider.getParticipant1());
        assertNull(decider.getParticipant2());
        assertTrue(bracket.stream().allMatch(m -> m.getStatus() == MatchStatus.COMPLETED));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 12, 16})
    void everyEliminatedParticipantLosesExactlyTwiceAndOneChampionRemains(int count) {
        List<Participant> players = participants(count);
        List<TournamentMatch> bracket = generator.generate(tournament(true), players);

        Participant champion = playEverything(bracket, new Random(count * 31L));

        assertTrue(bracket.stream().allMatch(m -> m.getStatus() == MatchStatus.COMPLETED), "Nothing may stay unfinished");
        assertNotNull(champion);

        Map<Long, Integer> losses = new HashMap<>();
        for (TournamentMatch match : bracket) {
            if (match.getScore1() == null || match.getWinner() == null) continue; // BYE or skipped rematch
            Participant beaten = match.getWinner() == match.getParticipant1() ? match.getParticipant2() : match.getParticipant1();
            losses.merge(beaten.getId(), 1, Integer::sum);
        }
        for (Participant player : players) {
            int lost = losses.getOrDefault(player.getId(), 0);
            if (player == champion) {
                assertTrue(lost <= 1, "The champion may lose at most once, in the grand final");
            } else {
                assertEquals(2, lost, "%s must be eliminated by a second loss, not the first".formatted(player.getName()));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 8, 16})
    void behavesTheSameOnADatabaseLoadedBracket(int count) {
        List<Participant> players = participants(count);
        List<TournamentMatch> bracket = generator.generate(tournament(true), players);
        assignIds(bracket);
        // Re-create the list the way the repository would return it: same rows, different order.
        List<TournamentMatch> loaded = new ArrayList<>(bracket);
        loaded.sort(Comparator.comparing(TournamentMatch::getId).reversed());

        Participant champion = playEverything(loaded, new Random(7));

        assertNotNull(champion);
        assertTrue(loaded.stream().allMatch(m -> m.getStatus() == MatchStatus.COMPLETED));
    }

    // --- helpers ---

    /** Plays every READY match until none is left, and returns the winner of the last decided match. */
    private Participant playEverything(List<TournamentMatch> bracket, Random random) {
        for (int guard = 0; guard < 500; guard++) {
            TournamentMatch next = bracket.stream()
                    .filter(m -> m.getStatus() == MatchStatus.READY)
                    .min(DoubleEliminationGenerator.BRACKET_ORDER)
                    .orElse(null);
            if (next == null) {
                return bracket.stream()
                        .filter(m -> m.getWinner() != null)
                        .max(Comparator.comparingInt(TournamentMatch::getRoundNumber))
                        .map(TournamentMatch::getWinner)
                        .orElse(null);
            }
            win(bracket, next, random.nextBoolean() ? next.getParticipant1() : next.getParticipant2());
        }
        return fail("The bracket never finished");
    }

    /** Plays a four-player bracket up to, but not including, the grand final. */
    private void playToGrandFinal(List<TournamentMatch> bracket, List<Participant> players) {
        win(bracket, match(bracket, 1, 1), players.get(0));
        win(bracket, match(bracket, 1, 2), players.get(1));
        win(bracket, match(bracket, 3, 1), players.get(3));
        win(bracket, match(bracket, 2, 1), players.get(0));
        win(bracket, match(bracket, 4, 1), players.get(1));
    }

    private void win(List<TournamentMatch> bracket, TournamentMatch match, Participant winner) {
        assertEquals(MatchStatus.READY, match.getStatus(),
                "Round %d match %d is not ready".formatted(match.getRoundNumber(), match.getMatchNumber()));
        boolean firstWins = winner == match.getParticipant1();
        match.setScore1(firstWins ? 2 : 0);
        match.setScore2(firstWins ? 0 : 2);
        match.setWinner(winner);
        match.setStatus(MatchStatus.COMPLETED);
        generator.advance(bracket, match);
    }

    private void assignIds(List<TournamentMatch> bracket) {
        long id = 1;
        for (TournamentMatch match : bracket) {
            match.setId(id++);
        }
    }

    private long count(List<TournamentMatch> bracket, BracketSide side) {
        return bracket.stream().filter(m -> m.getBracket() == side).count();
    }

    private List<Long> roundSizes(List<TournamentMatch> bracket, int... rounds) {
        List<Long> sizes = new ArrayList<>();
        for (int round : rounds) {
            sizes.add(bracket.stream().filter(m -> m.getRoundNumber() == round).count());
        }
        return sizes;
    }

    private TournamentMatch match(List<TournamentMatch> matches, int round, int matchNumber) {
        return matches.stream()
                .filter(match -> match.getRoundNumber() == round && match.getMatchNumber() == matchNumber)
                .findFirst()
                .orElseThrow();
    }

    private Tournament tournament(boolean grandFinalReset) {
        Tournament tournament = new Tournament();
        tournament.setFormat(TournamentFormat.DOUBLE_ELIMINATION);
        tournament.setGrandFinalReset(grandFinalReset);
        return tournament;
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
