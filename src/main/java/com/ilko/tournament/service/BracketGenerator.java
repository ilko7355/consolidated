package com.ilko.tournament.service;

import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.MatchStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-elimination bracket algorithm.
 *
 * <p>Participants are seeded in the order they are passed in (seed 1 first) and placed with the standard
 * seeding pattern - for eight slots 1v8, 4v5, 2v7, 3v6 - so the two strongest seeds can only meet in the
 * final. When the participant count is not a power of two, the unused seed positions become BYEs. The
 * pattern pairs every BYE with one of the top seeds, so a BYE never meets another BYE and every
 * participant plays a real opponent from round two onwards.</p>
 *
 * <p>Matches are addressed purely by position (round, match number): the feeders of match k in round r
 * are matches 2k-1 and 2k of round r-1, and the winner of match k moves to match ceil(k/2) of round r+1
 * (slot 1 for odd k, slot 2 for even k). The persisted {@code nextMatch} link mirrors that structure, but
 * the algorithm never relies on it, so it behaves the same on new and on database-loaded matches.</p>
 */
@Component
public class BracketGenerator {

    private static final Comparator<TournamentMatch> BRACKET_ORDER =
            Comparator.comparingInt(TournamentMatch::getRoundNumber).thenComparingInt(TournamentMatch::getMatchNumber);

    /** State of one side of a match while the bracket is being settled. */
    private enum Slot { FILLED, EMPTY, WAITING }

    private record Position(int round, int number) { }

    /** Builds every match of the bracket, links each match to its next match and settles the BYEs. */
    public List<TournamentMatch> generate(Tournament tournament, List<Participant> seeding) {
        if (seeding == null || seeding.size() < 2) {
            throw new IllegalArgumentException("Elimination bracket requires at least 2 participants.");
        }
        int bracketSize = nextPowerOfTwo(seeding.size());
        int totalRounds = Integer.numberOfTrailingZeros(bracketSize);
        int[] seeds = seedOrder(bracketSize);

        List<TournamentMatch> bracket = new ArrayList<>();
        int matchesInRound = bracketSize / 2;
        for (int round = 1; round <= totalRounds; round++, matchesInRound /= 2) {
            for (int number = 1; number <= matchesInRound; number++) {
                TournamentMatch match = new TournamentMatch();
                match.setTournament(tournament);
                match.setRoundNumber(round);
                match.setMatchNumber(number);
                match.setStatus(MatchStatus.PENDING);
                if (round == 1) {
                    match.setParticipant1(seeded(seeding, seeds[2 * number - 2]));
                    match.setParticipant2(seeded(seeding, seeds[2 * number - 1]));
                }
                bracket.add(match);
            }
        }

        Map<Position, TournamentMatch> index = index(bracket);
        for (TournamentMatch match : bracket) {
            match.setNextMatch(index.get(nextPosition(match)));
        }
        propagate(bracket);
        return bracket;
    }

    /**
     * Records that {@code completed} has been decided: moves its winner into the next round and settles
     * whatever that unlocks. Returns the matches that became READY as a result.
     */
    public List<TournamentMatch> advance(List<TournamentMatch> bracket, TournamentMatch completed) {
        Position completedPosition = position(completed);
        List<TournamentMatch> current = new ArrayList<>(bracket.size());
        for (TournamentMatch match : bracket) {
            current.add(position(match).equals(completedPosition) ? completed : match);
        }
        placeWinner(index(current), completed);
        return propagate(current);
    }

    /**
     * Walks the bracket round by round and settles every unfinished match whose two sides are known:
     * two participants make it READY, a participant facing an empty side wins by BYE, and two empty sides
     * complete it without a winner. A side whose feeder match is still undecided is WAITING, and the match
     * stays PENDING until that feeder is played. Returns the matches that became READY during this call.
     */
    public List<TournamentMatch> propagate(List<TournamentMatch> bracket) {
        Map<Position, TournamentMatch> index = index(bracket);
        List<TournamentMatch> ordered = new ArrayList<>(bracket);
        ordered.sort(BRACKET_ORDER);

        List<TournamentMatch> becameReady = new ArrayList<>();
        for (TournamentMatch match : ordered) {
            if (match.getStatus() == MatchStatus.COMPLETED) {
                continue;
            }
            Slot first = slot(match.getParticipant1(), feeder(index, match, 1));
            Slot second = slot(match.getParticipant2(), feeder(index, match, 2));
            if (first == Slot.WAITING || second == Slot.WAITING) {
                continue;
            }
            if (first == Slot.FILLED && second == Slot.FILLED) {
                if (match.getStatus() != MatchStatus.READY) {
                    match.setStatus(MatchStatus.READY);
                    becameReady.add(match);
                }
                continue;
            }
            match.setWinner(first == Slot.FILLED ? match.getParticipant1() : match.getParticipant2());
            match.setStatus(MatchStatus.COMPLETED);
            placeWinner(index, match);
        }
        return becameReady;
    }

    /**
     * Standard seeding order for a bracket of the given power-of-two size, listed by bracket position.
     * Built by doubling: every seed s of the smaller bracket is followed by its opponent size + 1 - s.
     */
    static int[] seedOrder(int bracketSize) {
        int[] order = {1};
        while (order.length < bracketSize) {
            int size = order.length * 2;
            int[] expanded = new int[size];
            for (int i = 0; i < order.length; i++) {
                expanded[2 * i] = order[i];
                expanded[2 * i + 1] = size + 1 - order[i];
            }
            order = expanded;
        }
        return order;
    }

    private Slot slot(Participant participant, TournamentMatch feeder) {
        if (participant != null) {
            return Slot.FILLED;
        }
        // Round one has no feeder, so an empty side there is an unused seed position (a BYE).
        if (feeder == null || feeder.getStatus() == MatchStatus.COMPLETED) {
            return Slot.EMPTY;
        }
        return Slot.WAITING;
    }

    private TournamentMatch feeder(Map<Position, TournamentMatch> index, TournamentMatch match, int side) {
        if (match.getRoundNumber() == 1) {
            return null;
        }
        int feederNumber = side == 1 ? 2 * match.getMatchNumber() - 1 : 2 * match.getMatchNumber();
        return index.get(new Position(match.getRoundNumber() - 1, feederNumber));
    }

    private void placeWinner(Map<Position, TournamentMatch> index, TournamentMatch match) {
        TournamentMatch next = index.get(nextPosition(match));
        if (next == null) {
            return;
        }
        if (match.getMatchNumber() % 2 == 1) {
            next.setParticipant1(match.getWinner());
        } else {
            next.setParticipant2(match.getWinner());
        }
    }

    private Position nextPosition(TournamentMatch match) {
        return new Position(match.getRoundNumber() + 1, (match.getMatchNumber() + 1) / 2);
    }

    private Position position(TournamentMatch match) {
        return new Position(match.getRoundNumber(), match.getMatchNumber());
    }

    private Map<Position, TournamentMatch> index(List<TournamentMatch> bracket) {
        Map<Position, TournamentMatch> index = new HashMap<>();
        for (TournamentMatch match : bracket) {
            index.put(position(match), match);
        }
        return index;
    }

    private Participant seeded(List<Participant> seeding, int seed) {
        return seed <= seeding.size() ? seeding.get(seed - 1) : null;
    }

    private int nextPowerOfTwo(int n) {
        int highest = Integer.highestOneBit(n);
        return highest == n ? n : highest << 1;
    }
}
