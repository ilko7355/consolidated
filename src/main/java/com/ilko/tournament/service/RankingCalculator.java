package com.ilko.tournament.service;

import com.ilko.tournament.dto.RankingResponse;
import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.MatchStatus;
import com.ilko.tournament.enums.TournamentFormat;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes participant rankings/statistics purely from real, persisted match results.
 * This is the single source of ranking logic for both ELIMINATION and GROUPS tournaments -
 * it must never be duplicated or re-implemented elsewhere.
 *
 * GROUPS ranking order: most wins first, then best score difference, then participant id.
 * Points (3 per win, 1 per draw, 0 per loss) are reported alongside the standings but are not
 * themselves the sort key - wins remain the primary ranking criterion, unchanged by this.
 *
 * ELIMINATION ranking order additionally sorts first by how far each participant progressed
 * in the bracket (round reached before elimination, or "champion" for the winner of the final),
 * before falling back to the same wins / score-difference / id tie-break used for GROUPS. This
 * progression tier is derived entirely from the round number and winner already stored on each
 * match - it is not a second, separately-tracked ranking value.
 */
@Component
public class RankingCalculator {

    private static final class Stats {
        String name;
        int played;
        int wins;
        int draws;
        int losses;
        int scoreFor;
        int scoreAgainst;
    }

    public List<RankingResponse> calculate(Tournament tournament, List<TournamentMatch> matches) {
        Map<Long, Stats> stats = new LinkedHashMap<>();
        for (Participant participant : tournament.getParticipants()) {
            Stats s = new Stats();
            s.name = participant.getName();
            stats.put(participant.getId(), s);
        }

        for (TournamentMatch match : matches) {
            // Only fully-recorded, real results count. Byes (no scores) and unfinished matches are correctly excluded.
            // A draw (real scores, no winner) DOES count - see the win/draw/loss tally below.
            if (match.getStatus() != MatchStatus.COMPLETED || match.getScore1() == null || match.getScore2() == null) {
                continue;
            }
            Stats first = stats.get(match.getParticipant1().getId());
            Stats second = stats.get(match.getParticipant2().getId());
            if (first == null || second == null) continue; // defensive: ignore results for participants no longer in the tournament

            first.played++;
            second.played++;
            first.scoreFor += match.getScore1();
            first.scoreAgainst += match.getScore2();
            second.scoreFor += match.getScore2();
            second.scoreAgainst += match.getScore1();

            if (match.getWinner() == null) {
                first.draws++;
                second.draws++;
            } else if (match.getWinner().getId().equals(match.getParticipant1().getId())) {
                first.wins++;
                second.losses++;
            } else {
                second.wins++;
                first.losses++;
            }
        }

        boolean isElimination = tournament.getFormat() == TournamentFormat.ELIMINATION;
        Map<Long, Integer> progression = isElimination ? eliminationProgression(stats.keySet(), matches) : Map.of();

        List<Long> ordered = stats.keySet().stream()
                .sorted(Comparator
                        .comparingInt((Long id) -> isElimination ? progression.getOrDefault(id, 1) : 0).reversed()
                .thenComparing(Comparator.comparingInt((Long id) -> stats.get(id).wins).reversed())
                .thenComparing(Comparator.comparingInt((Long id) -> scoreDifference(stats.get(id))).reversed())
                        .thenComparingLong(id -> id))
                .toList();

        return java.util.stream.IntStream.range(0, ordered.size()).mapToObj(index -> {
            Long participantId = ordered.get(index);
            Stats s = stats.get(participantId);
            int scoreDiff = scoreDifference(s);
            return new RankingResponse(participantId, s.name, s.played, s.wins, s.draws, s.losses, scoreDiff, s.wins * 3 + s.draws, index + 1);
        }).toList();
    }

    /**
     * How far each participant progressed in a knockout bracket, expressed as a single comparable
     * tier: 1 = hasn't played/been eliminated yet, N = reached round N but lost there,
     * (highest round + 1) = won the final (tournament champion). Derived only from each match's
     * existing roundNumber/winner - byes count as real progression (the bye winner genuinely
     * advances), matching how BracketGenerator already treats them.
     */
    private Map<Long, Integer> eliminationProgression(Set<Long> participantIds, List<TournamentMatch> matches) {
        int totalRounds = matches.stream().mapToInt(TournamentMatch::getRoundNumber).max().orElse(0);
        Map<Long, Integer> progression = new HashMap<>();
        for (Long id : participantIds) progression.put(id, 1);

        for (TournamentMatch match : matches) {
            if (match.getStatus() != MatchStatus.COMPLETED || match.getWinner() == null) continue;
            int round = match.getRoundNumber();
            markProgression(progression, participantIds, match.getParticipant1(), match.getWinner(), round, totalRounds);
            markProgression(progression, participantIds, match.getParticipant2(), match.getWinner(), round, totalRounds);
        }
        return progression;
    }

    private void markProgression(Map<Long, Integer> progression, Set<Long> participantIds, Participant participant,
                                  Participant winner, int round, int totalRounds) {
        if (participant == null || !participantIds.contains(participant.getId())) return;
        boolean won = winner.getId().equals(participant.getId());
        int tier = won ? round + 1 : round; // survived into round+1, or eliminated at exactly this round
        progression.merge(participant.getId(), tier, Math::max);
    }

    private int scoreDifference(Stats s) { return s.scoreFor - s.scoreAgainst; }
}