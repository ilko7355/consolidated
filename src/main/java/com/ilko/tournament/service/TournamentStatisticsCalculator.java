package com.ilko.tournament.service;

import com.ilko.tournament.dto.RankingResponse;
import com.ilko.tournament.dto.TournamentStatisticsResponse;
import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.BracketSide;
import com.ilko.tournament.enums.MatchStatus;
import com.ilko.tournament.enums.TournamentFormat;
import com.ilko.tournament.enums.TournamentStatus;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tournament-level statistics derived from the stored matches - the same data the rankings use.
 * A match decided by a BYE counts as completed but not as played, so it never affects scoring figures.
 * Champion and runner-up are only published once the tournament is COMPLETED.
 */
@Component
public class TournamentStatisticsCalculator {

    public TournamentStatisticsResponse calculate(Tournament tournament, List<TournamentMatch> matches, List<RankingResponse> standings) {
        int total = matches.size();
        int completed = (int) matches.stream().filter(m -> m.getStatus() == MatchStatus.COMPLETED).count();
        List<TournamentMatch> played = matches.stream().filter(this::isPlayed).toList();
        // A grand final that needed no deciding rematch is complete but was never a match, let alone a BYE.
        int skipped = (int) matches.stream().filter(this::isSkipped).count();
        int draws = (int) played.stream().filter(m -> m.getWinner() == null).count();
        int totalScore = played.stream().mapToInt(m -> m.getScore1() + m.getScore2()).sum();
        double average = played.isEmpty() ? 0 : Math.round(totalScore * 10.0 / played.size()) / 10.0;

        Map<Long, Integer> scored = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (TournamentMatch match : played) {
            credit(scored, names, match.getParticipant1(), match.getScore1());
            credit(scored, names, match.getParticipant2(), match.getScore2());
        }
        // Highest total first; on a tie the lower participant id (earlier registration) wins.
        Optional<Map.Entry<Long, Integer>> topScorer = scored.entrySet().stream()
                .max(Map.Entry.<Long, Integer>comparingByValue()
                        .thenComparing(Map.Entry.<Long, Integer>comparingByKey().reversed()));

        Optional<TournamentMatch> biggestWin = played.stream()
                .filter(m -> m.getWinner() != null)
                .min(Comparator.comparingInt((TournamentMatch m) -> -margin(m))
                        .thenComparingInt(TournamentMatch::getRoundNumber)
                        .thenComparingInt(TournamentMatch::getMatchNumber));

        String champion = null;
        String runnerUp = null;
        if (tournament.getStatus() == TournamentStatus.COMPLETED) {
            if (tournament.getFormat() != TournamentFormat.GROUPS) {
                // The last match that produced a winner: the final, or the grand final / its deciding
                // rematch in double elimination, whichever of the two was actually needed.
                Optional<TournamentMatch> finalMatch = matches.stream().filter(m -> m.getWinner() != null)
                        .max(Comparator.comparingInt(TournamentMatch::getRoundNumber));
                if (finalMatch.isPresent()) {
                    TournamentMatch decider = finalMatch.get();
                    champion = decider.getWinner().getName();
                    runnerUp = name(opponentOf(decider, decider.getWinner()));
                }
            } else if (!standings.isEmpty()) {
                champion = standings.get(0).participant();
                runnerUp = standings.size() > 1 ? standings.get(1).participant() : null;
            }
        }

        return new TournamentStatisticsResponse(
                tournament.getId(), tournament.getFormat().name(), tournament.getStatus().name(),
                tournament.getParticipants().size(), total, completed, total - completed, played.size(),
                completed - played.size() - skipped, draws, totalScore, average, total == 0 ? 0 : completed * 100 / total,
                champion, runnerUp,
                topScorer.map(entry -> names.get(entry.getKey())).orElse(null),
                topScorer.map(Map.Entry::getValue).orElse(0),
                biggestWin.map(match -> describe(match, winnersRounds(matches))).orElse(null),
                biggestWin.map(this::margin).orElse(0));
    }

    /** A match completed without ever being contested: no winner and nobody was placed in it. */
    private boolean isSkipped(TournamentMatch match) {
        return match.getStatus() == MatchStatus.COMPLETED && match.getWinner() == null
                && match.getParticipant1() == null && match.getParticipant2() == null;
    }

    private boolean isPlayed(TournamentMatch match) {
        return match.getStatus() == MatchStatus.COMPLETED && match.getScore1() != null && match.getScore2() != null;
    }

    private void credit(Map<Long, Integer> scored, Map<Long, String> names, Participant participant, int score) {
        if (participant == null) {
            return;
        }
        scored.merge(participant.getId(), score, Integer::sum);
        names.putIfAbsent(participant.getId(), participant.getName());
    }

    private int margin(TournamentMatch match) {
        return Math.abs(match.getScore1() - match.getScore2());
    }

    private String describe(TournamentMatch match, int winnersRounds) {
        return "%s %d:%d %s (%s)".formatted(name(match.getParticipant1()), match.getScore1(), match.getScore2(),
                name(match.getParticipant2()), stage(match, winnersRounds));
    }

    /**
     * Where a match was played. Losers rounds are numbered after the winners rounds in the database, so
     * they are renumbered from 1 here - that is how they are labelled in the bracket the reader sees.
     */
    private String stage(TournamentMatch match, int winnersRounds) {
        if (match.getGroup() != null) {
            return match.getGroup().getName() + ", round " + match.getRoundNumber();
        }
        if (match.getBracket() == null) {
            return "Round " + match.getRoundNumber();
        }
        return switch (match.getBracket()) {
            case WINNERS -> "winners round " + match.getRoundNumber();
            case LOSERS -> "losers round " + (match.getRoundNumber() - winnersRounds);
            case GRAND_FINAL -> "grand final";
        };
    }

    private int winnersRounds(List<TournamentMatch> matches) {
        return matches.stream().filter(m -> m.getBracket() == BracketSide.WINNERS)
                .mapToInt(TournamentMatch::getRoundNumber).max().orElse(0);
    }

    private Participant opponentOf(TournamentMatch match, Participant participant) {
        Participant first = match.getParticipant1();
        return first != null && first.getId().equals(participant.getId()) ? match.getParticipant2() : first;
    }

    private String name(Participant participant) {
        return participant == null ? null : participant.getName();
    }
}
