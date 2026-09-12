package com.ilko.tournament.service;

import com.ilko.tournament.dto.TournamentStatisticsResponse;
import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.MatchStatus;
import com.ilko.tournament.enums.TournamentFormat;
import com.ilko.tournament.enums.TournamentStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TournamentStatisticsCalculatorTest {
    private final BracketGenerator generator = new BracketGenerator();
    private final RankingCalculator rankings = new RankingCalculator();
    private final TournamentStatisticsCalculator calculator = new TournamentStatisticsCalculator();

    @Test
    void completedKnockoutReportsChampionRunnerUpAndScoringFacts() {
        Tournament tournament = tournament(TournamentFormat.ELIMINATION, 3);
        List<TournamentMatch> bracket = generator.generate(tournament, tournament.getParticipants());
        TournamentMatch semiFinal = match(bracket, 1, 2); // P2 v P3
        decide(semiFinal, 1, 4);
        generator.advance(bracket, semiFinal);
        TournamentMatch finalMatch = match(bracket, 2, 1); // P1 (BYE) v P3
        decide(finalMatch, 2, 1);
        generator.advance(bracket, finalMatch);
        tournament.setStatus(TournamentStatus.COMPLETED);

        TournamentStatisticsResponse stats = calculator.calculate(tournament, bracket, rankings.calculate(tournament, bracket));

        assertEquals(3, stats.totalMatches());
        assertEquals(3, stats.completedMatches());
        assertEquals(2, stats.playedMatches());
        assertEquals(1, stats.byes());
        assertEquals(8, stats.totalScore());
        assertEquals(4.0, stats.averageScorePerMatch());
        assertEquals(100, stats.progressPercent());
        assertEquals("P1", stats.champion());
        assertEquals("P3", stats.runnerUp());
        assertEquals("P3", stats.topScorer());
        assertEquals(5, stats.topScorerPoints());
        assertEquals(3, stats.biggestWinMargin());
        assertEquals("P2 1:4 P3 (Round 1)", stats.biggestWin());
    }

    @Test
    void championIsNotPublishedWhileTheTournamentIsStillRunning() {
        Tournament tournament = tournament(TournamentFormat.ELIMINATION, 4);
        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        List<TournamentMatch> bracket = generator.generate(tournament, tournament.getParticipants());
        TournamentMatch first = match(bracket, 1, 1);
        decide(first, 3, 0);
        generator.advance(bracket, first);

        TournamentStatisticsResponse stats = calculator.calculate(tournament, bracket, rankings.calculate(tournament, bracket));

        assertNull(stats.champion());
        assertNull(stats.runnerUp());
        assertEquals(2, stats.remainingMatches());
        assertEquals(33, stats.progressPercent());
    }

    @Test
    void groupStageCountsDrawsAndTakesTheChampionFromTheStandings() {
        Tournament tournament = tournament(TournamentFormat.GROUPS, 3);
        List<Participant> p = tournament.getParticipants();
        List<TournamentMatch> matches = List.of(
                played(tournament, 1, p.get(0), p.get(1), 2, 2),
                played(tournament, 2, p.get(0), p.get(2), 3, 1),
                played(tournament, 3, p.get(1), p.get(2), 0, 1));
        tournament.setStatus(TournamentStatus.COMPLETED);

        TournamentStatisticsResponse stats = calculator.calculate(tournament, matches, rankings.calculate(tournament, matches));

        assertEquals(1, stats.draws());
        assertEquals(0, stats.byes());
        assertEquals("P1", stats.champion());
        assertEquals("P3", stats.runnerUp());
    }

    private TournamentMatch played(Tournament tournament, int round, Participant first, Participant second, int score1, int score2) {
        TournamentMatch match = new TournamentMatch();
        match.setTournament(tournament);
        match.setRoundNumber(round);
        match.setMatchNumber(1);
        match.setParticipant1(first);
        match.setParticipant2(second);
        decide(match, score1, score2);
        return match;
    }

    private void decide(TournamentMatch match, int score1, int score2) {
        match.setScore1(score1);
        match.setScore2(score2);
        match.setWinner(score1 == score2 ? null : score1 > score2 ? match.getParticipant1() : match.getParticipant2());
        match.setStatus(MatchStatus.COMPLETED);
    }

    private TournamentMatch match(List<TournamentMatch> matches, int round, int number) {
        return matches.stream().filter(m -> m.getRoundNumber() == round && m.getMatchNumber() == number).findFirst().orElseThrow();
    }

    private Tournament tournament(TournamentFormat format, int count) {
        Tournament tournament = new Tournament();
        tournament.setId(1L);
        tournament.setName("Cup");
        tournament.setFormat(format);
        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        for (int i = 1; i <= count; i++) {
            Participant participant = new Participant();
            participant.setId((long) i);
            participant.setName("P" + i);
            participant.setTournament(tournament);
            tournament.getParticipants().add(participant);
        }
        return tournament;
    }
}
