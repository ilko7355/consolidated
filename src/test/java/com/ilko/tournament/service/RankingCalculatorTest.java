package com.ilko.tournament.service;

import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.MatchStatus;
import com.ilko.tournament.enums.TournamentFormat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingCalculatorTest {
    private final RankingCalculator calculator = new RankingCalculator();

    @Test
    void returnsEmptyRankingWithoutParticipants() {
        Tournament tournament = new Tournament();
        tournament.setParticipants(new ArrayList<>());

        assertEquals(List.of(), calculator.calculate(tournament, List.of()));
    }

    @Test
    void ordersCompletedMatchesByWinsThenLossesThenId() {
        Participant first = participant(1L, "First");
        Participant second = participant(2L, "Second");
        Tournament tournament = new Tournament();
        tournament.setParticipants(new ArrayList<>(List.of(first, second)));
        TournamentMatch match = new TournamentMatch();
        match.setParticipant1(first);
        match.setParticipant2(second);
        match.setWinner(first);
        match.setScore1(2);
        match.setScore2(1);
        match.setStatus(MatchStatus.COMPLETED);

        var ranking = calculator.calculate(tournament, List.of(match));

        assertEquals("First", ranking.get(0).participant());
        assertEquals(1, ranking.get(0).wins());
        assertEquals(3, ranking.get(0).points());
        assertEquals(2, ranking.get(1).placement());
    }

    @Test
    void recalculatesRankingAfterAdditionalMatchesAndDeterministicTieBreaks() {
        Participant alpha = participant(1L, "Alpha");
        Participant beta = participant(2L, "Beta");
        Participant gamma = participant(3L, "Gamma");
        Tournament tournament = new Tournament();
        tournament.setParticipants(new ArrayList<>(List.of(alpha, beta, gamma)));

        TournamentMatch first = new TournamentMatch();
        first.setParticipant1(alpha);
        first.setParticipant2(beta);
        first.setWinner(alpha);
        first.setScore1(3);
        first.setScore2(0);
        first.setStatus(MatchStatus.COMPLETED);

        TournamentMatch second = new TournamentMatch();
        second.setParticipant1(beta);
        second.setParticipant2(gamma);
        second.setWinner(gamma);
        second.setScore1(1);
        second.setScore2(2);
        second.setStatus(MatchStatus.COMPLETED);

        TournamentMatch third = new TournamentMatch();
        third.setParticipant1(alpha);
        third.setParticipant2(gamma);
        third.setWinner(gamma);
        third.setScore1(1);
        third.setScore2(2);
        third.setStatus(MatchStatus.COMPLETED);

        var ranking = calculator.calculate(tournament, List.of(first, second, third));

        assertEquals("Gamma", ranking.get(0).participant());
        assertEquals(2, ranking.get(0).wins());
        assertEquals(6, ranking.get(0).points());
        assertEquals("Alpha", ranking.get(1).participant());
        assertEquals("Beta", ranking.get(2).participant());
        assertEquals(3, ranking.get(2).placement());
    }

    @Test
    void breaksTiedWinsUsingScoreDifference() {
        // Alpha and Beta both finish with exactly 1 win / 1 loss, but Alpha's matches were won/lost by bigger margins.
        Participant alpha = participant(1L, "Alpha");
        Participant beta = participant(2L, "Beta");
        Participant gamma = participant(3L, "Gamma");
        Tournament tournament = new Tournament();
        tournament.setParticipants(new ArrayList<>(List.of(alpha, beta, gamma)));

        TournamentMatch alphaBeatsGamma = completedMatch(alpha, gamma, alpha, 5, 0); // Alpha diff +5
        TournamentMatch betaBeatsGamma = completedMatch(beta, gamma, beta, 2, 1);    // Beta diff +1
        TournamentMatch gammaBeatsAlpha = completedMatch(gamma, alpha, gamma, 3, 1); // Alpha diff -2, so Alpha total = +3
        TournamentMatch gammaBeatsBeta = completedMatch(gamma, beta, gamma, 3, 0);   // Beta diff -3, so Beta total = -2

        var ranking = calculator.calculate(tournament, List.of(alphaBeatsGamma, betaBeatsGamma, gammaBeatsAlpha, gammaBeatsBeta));

        // Gamma has 2 wins (best), then Alpha (1 win, +3 diff) must rank above Beta (1 win, -2 diff).
        assertEquals("Gamma", ranking.get(0).participant());
        assertEquals("Alpha", ranking.get(1).participant());
        assertEquals(3, ranking.get(1).scoreDifference());
        assertEquals("Beta", ranking.get(2).participant());
        assertEquals(-2, ranking.get(2).scoreDifference());
    }

    @Test
    void fallsBackToParticipantIdWhenWinsAndScoreDifferenceAreBothTied() {
        Participant lowerId = participant(1L, "LowerId");
        Participant higherId = participant(2L, "HigherId");
        Tournament tournament = new Tournament();
        tournament.setParticipants(new ArrayList<>(List.of(lowerId, higherId)));
        // Both have 0 wins, 0 losses, 0 score difference - nothing has been played, total tie.

        var ranking = calculator.calculate(tournament, List.of());

        assertEquals("LowerId", ranking.get(0).participant());
        assertEquals(1, ranking.get(0).placement());
        assertEquals("HigherId", ranking.get(1).participant());
        assertEquals(2, ranking.get(1).placement());
    }

    @Test
    void byeMatchesDoNotCountTowardsWinLossOrScoreDifference() {
        // A bye is auto-completed with a winner but no scores - it must not inflate anyone's record.
        Participant alpha = participant(1L, "Alpha");
        Participant beta = participant(2L, "Beta");
        Tournament tournament = new Tournament();
        tournament.setParticipants(new ArrayList<>(List.of(alpha, beta)));

        TournamentMatch bye = new TournamentMatch();
        bye.setParticipant1(alpha);
        bye.setParticipant2(null);
        bye.setWinner(alpha);
        bye.setStatus(MatchStatus.COMPLETED);
        // no scores set, exactly like BracketGenerator.resolveReadyAndByes() leaves it

        var ranking = calculator.calculate(tournament, List.of(bye));

        var alphaRanking = ranking.stream().filter(r -> r.participant().equals("Alpha")).findFirst().orElseThrow();
        assertEquals(0, alphaRanking.matchesPlayed());
        assertEquals(0, alphaRanking.wins());
        assertEquals(0, alphaRanking.scoreDifference());
    }

    @Test
    void recalculatingWithTheSameDataProducesTheSameRankingEveryTime() {
        // The calculator has no internal state/cache, so recomputing from the same real match data (e.g. after
        // a page refresh or a fresh DB query) must always yield an identical, correct result.
        Participant alpha = participant(1L, "Alpha");
        Participant beta = participant(2L, "Beta");
        Tournament tournament = new Tournament();
        tournament.setParticipants(new ArrayList<>(List.of(alpha, beta)));
        TournamentMatch match = completedMatch(alpha, beta, alpha, 2, 1);

        var first = calculator.calculate(tournament, List.of(match));
        var second = calculator.calculate(tournament, List.of(match));

        assertEquals(first, second);
    }

    @Test
    void rankingUpdatesImmediatelyWhenANewResultIsAddedToTheInput() {
        Participant alpha = participant(1L, "Alpha");
        Participant beta = participant(2L, "Beta");
        Tournament tournament = new Tournament();
        tournament.setParticipants(new ArrayList<>(List.of(alpha, beta)));

        var beforeAnyResults = calculator.calculate(tournament, List.of());
        assertEquals("Alpha", beforeAnyResults.get(0).participant()); // tied 0-0, id fallback

        TournamentMatch betaWins = completedMatch(alpha, beta, beta, 0, 3);
        var afterResult = calculator.calculate(tournament, List.of(betaWins));

        assertEquals("Beta", afterResult.get(0).participant());
        assertEquals(1, afterResult.get(0).wins());
    }

    private TournamentMatch completedMatch(Participant p1, Participant p2, Participant winner, int score1, int score2) {
        TournamentMatch match = new TournamentMatch();
        match.setParticipant1(p1);
        match.setParticipant2(p2);
        match.setWinner(winner);
        match.setScore1(score1);
        match.setScore2(score2);
        match.setStatus(MatchStatus.COMPLETED);
        return match;
    }

    // ===================== TASK 1: elimination progression tests =====================

    private TournamentMatch completedRoundMatch(int round, Participant p1, Participant p2, Participant winner, int score1, int score2) {
        TournamentMatch match = completedMatch(p1, p2, winner, score1, score2);
        match.setRoundNumber(round);
        return match;
    }

    @Test
    void championRanksAboveRunnerUpAboveEarlyElimination() {
        // 4-participant single-elimination bracket: A beats B (round 1), C beats D (round 1), C beats A (final/round 2).
        Participant a = participant(1L, "A"), b = participant(2L, "B"), c = participant(3L, "C"), d = participant(4L, "D");
        Tournament tournament = new Tournament();
        tournament.setFormat(TournamentFormat.ELIMINATION);
        tournament.setParticipants(new ArrayList<>(List.of(a, b, c, d)));

        TournamentMatch r1m1 = completedRoundMatch(1, a, b, a, 3, 1);
        TournamentMatch r1m2 = completedRoundMatch(1, c, d, c, 3, 0);
        TournamentMatch finalMatch = completedRoundMatch(2, c, a, c, 2, 1);

        var ranking = calculator.calculate(tournament, List.of(r1m1, r1m2, finalMatch));
        Map<String, Integer> placement = placementsByName(ranking);

        assertEquals(1, placement.get("C"), "Tournament winner (champion) must be ranked first");
        assertEquals(2, placement.get("A"), "Finalist who lost the final must rank above earlier losers");
        // B and D were both eliminated in round 1 - they must both rank below the finalist and champion.
        assertTrue(placement.get("B") > placement.get("A"));
        assertTrue(placement.get("D") > placement.get("A"));
    }

    @Test
    void semifinalistsRankAboveQuarterfinalLosersAndBelowFinalists() {
        // 8-participant bracket: quarterfinals (round 1) -> semifinals (round 2) -> final (round 3).
        Participant p1 = participant(1L, "P1"), p2 = participant(2L, "P2"), p3 = participant(3L, "P3"), p4 = participant(4L, "P4");
        Participant p5 = participant(5L, "P5"), p6 = participant(6L, "P6"), p7 = participant(7L, "P7"), p8 = participant(8L, "P8");
        Tournament tournament = new Tournament();
        tournament.setFormat(TournamentFormat.ELIMINATION);
        tournament.setParticipants(new ArrayList<>(List.of(p1, p2, p3, p4, p5, p6, p7, p8)));

        List<TournamentMatch> matches = List.of(
                completedRoundMatch(1, p1, p2, p1, 3, 0), // quarterfinal: P2 eliminated
                completedRoundMatch(1, p3, p4, p3, 3, 1), // quarterfinal: P4 eliminated
                completedRoundMatch(1, p5, p6, p5, 3, 2), // quarterfinal: P6 eliminated
                completedRoundMatch(1, p7, p8, p7, 3, 0), // quarterfinal: P8 eliminated
                completedRoundMatch(2, p1, p3, p1, 2, 1),  // semifinal: P3 eliminated (semifinalist)
                completedRoundMatch(2, p5, p7, p5, 2, 0),  // semifinal: P7 eliminated (semifinalist)
                completedRoundMatch(3, p1, p5, p1, 1, 0)   // final: P5 is the runner-up
        );

        var ranking = calculator.calculate(tournament, matches);
        Map<String, Integer> placement = placementsByName(ranking);

        assertEquals(1, placement.get("P1"), "Champion");
        assertEquals(2, placement.get("P5"), "Runner-up must rank second");
        // Both semifinalists (P3, P7) must rank strictly above every quarterfinal loser (P2, P4, P6, P8).
        int worstSemifinalist = Math.max(placement.get("P3"), placement.get("P7"));
        int bestQuarterfinalLoser = List.of(placement.get("P2"), placement.get("P4"), placement.get("P6"), placement.get("P8"))
                .stream().min(Integer::compareTo).orElseThrow();
        assertTrue(worstSemifinalist < bestQuarterfinalLoser,
                "Every semifinalist must outrank every quarterfinal-round loser");
    }

    @Test
    void byeAdvancementCountsAsRealProgressionEvenThoughItGeneratesNoWinLossStat() {
        // A gets a bye into round 2 (as BracketGenerator.resolveReadyAndByes leaves it: winner set, no scores),
        // then loses the final. B actually won and lost real matches in rounds 1 and 2 respectively.
        Participant a = participant(1L, "A"), b = participant(2L, "B"), c = participant(3L, "C");
        Tournament tournament = new Tournament();
        tournament.setFormat(TournamentFormat.ELIMINATION);
        tournament.setParticipants(new ArrayList<>(List.of(a, b, c)));

        TournamentMatch bye = new TournamentMatch();
        bye.setRoundNumber(1);
        bye.setParticipant1(a);
        bye.setParticipant2(null);
        bye.setWinner(a);
        bye.setStatus(MatchStatus.COMPLETED); // no scores - exactly like a real bye

        TournamentMatch r1 = completedRoundMatch(1, b, c, b, 3, 1); // C eliminated round 1
        TournamentMatch finalMatch = completedRoundMatch(2, b, a, b, 2, 0); // A (the bye recipient) loses the final

        var ranking = calculator.calculate(tournament, List.of(bye, r1, finalMatch));
        Map<String, Integer> placement = placementsByName(ranking);

        assertEquals(1, placement.get("B"), "Champion");
        assertEquals(2, placement.get("A"), "Bye recipient still reached and lost the final, so ranks as runner-up");
        assertEquals(3, placement.get("C"), "Eliminated in round 1");

        // The bye must not have added a phantom win/loss to A's real record.
        var aRanking = ranking.stream().filter(r -> r.participant().equals("A")).findFirst().orElseThrow();
        assertEquals(0, aRanking.wins());
        assertEquals(1, aRanking.losses());
    }

    @Test
    void groupsTournamentRankingIsUnaffectedByEliminationProgressionLogic() {
        // Same win/loss/score-difference scenario as the pre-existing tie-break test, but now with
        // format explicitly set to GROUPS, to confirm the new elimination-only logic never engages.
        Participant alpha = participant(1L, "Alpha");
        Participant beta = participant(2L, "Beta");
        Participant gamma = participant(3L, "Gamma");
        Tournament tournament = new Tournament();
        tournament.setFormat(TournamentFormat.GROUPS);
        tournament.setParticipants(new ArrayList<>(List.of(alpha, beta, gamma)));

        TournamentMatch alphaBeatsGamma = completedMatch(alpha, gamma, alpha, 5, 0);
        TournamentMatch betaBeatsGamma = completedMatch(beta, gamma, beta, 2, 1);
        TournamentMatch gammaBeatsAlpha = completedMatch(gamma, alpha, gamma, 3, 1);
        TournamentMatch gammaBeatsBeta = completedMatch(gamma, beta, gamma, 3, 0);

        var ranking = calculator.calculate(tournament, List.of(alphaBeatsGamma, betaBeatsGamma, gammaBeatsAlpha, gammaBeatsBeta));

        assertEquals("Gamma", ranking.get(0).participant());
        assertEquals("Alpha", ranking.get(1).participant());
        assertEquals("Beta", ranking.get(2).participant());
    }

    private Map<String, Integer> placementsByName(List<com.ilko.tournament.dto.RankingResponse> ranking) {
        Map<String, Integer> map = new HashMap<>();
        for (var r : ranking) map.put(r.participant(), r.placement());
        return map;
    }

    private Participant participant(Long id, String name) {
        Participant participant = new Participant();
        participant.setId(id);
        participant.setName(name);
        return participant;
    }
}