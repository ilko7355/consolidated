package com.ilko.tournament.service;

import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.BracketSide;
import com.ilko.tournament.enums.MatchStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Double-elimination bracket algorithm: a participant is out only after losing twice.
 *
 * <p>The winners bracket is seeded exactly like the single-elimination one (see
 * {@link BracketGenerator#seedOrder(int)}), but its losers are not eliminated - they drop into a
 * losers bracket and can fight their way back to the grand final. For a bracket of size 2^n the
 * structure is: n winners rounds, 2n-2 losers rounds and the grand final, which is 2 * 2^n - 2
 * matches in total.</p>
 *
 * <p>The losers bracket alternates between two kinds of round. Odd rounds pair survivors of the
 * losers bracket against each other; even rounds put those survivors against the players just
 * knocked out of the winners bracket, so nobody meets a fresh winners-bracket loser twice in a
 * row. Losers of winners round 1 start in losers round 1, and losers of winners round r &gt; 1
 * enter at losers round 2(r-1).</p>
 *
 * <p>Round numbers are allocated so that they stay unique per tournament and every link points
 * forward: winners rounds are 1..n, losers rounds n+1..3n-2, the grand final is 3n-1 and the
 * optional deciding rematch 3n. Because of that, settling the bracket is a single pass in
 * ascending round order, and matches can be persisted in descending round order while the
 * {@code next_match_id} / {@code next_loser_match_id} foreign keys still resolve.</p>
 *
 * <p>Unlike the single-elimination generator, which derives everything from positions, this one
 * follows the stored {@code nextMatch}/{@code nextLoserMatch} links: the losers bracket has two
 * different feeding patterns, so an explicit link is clearer than positional arithmetic. Links are
 * resolved by id against the passed-in list, so a lazily-loaded proxy is never initialised and the
 * algorithm behaves the same on freshly generated and on database-loaded matches.</p>
 */
@Component
public class DoubleEliminationGenerator {

    static final Comparator<TournamentMatch> BRACKET_ORDER =
            Comparator.comparingInt(TournamentMatch::getRoundNumber).thenComparingInt(TournamentMatch::getMatchNumber);

    /** State of one side of a match while the bracket is being settled. */
    private enum Slot { FILLED, EMPTY, WAITING }

    private record Position(int round, int number) { }

    /** One side (1 or 2) of one match. */
    private record SideKey(int round, int number, int slot) { }

    /** The match a side is waiting for, and whether it is that match's winner or its loser. */
    private record Source(TournamentMatch match, boolean loser) { }

    /** Builds every match, links winners and losers to where they go next and settles the BYEs. */
    public List<TournamentMatch> generate(Tournament tournament, List<Participant> seeding) {
        if (seeding == null || seeding.size() < 2) {
            throw new IllegalArgumentException("Double-elimination bracket requires at least 2 participants.");
        }
        int bracketSize = nextPowerOfTwo(seeding.size());
        int winnersRounds = Integer.numberOfTrailingZeros(bracketSize);
        int losersRounds = losersRounds(winnersRounds);
        int[] seeds = BracketGenerator.seedOrder(bracketSize);

        Map<Position, TournamentMatch> index = new HashMap<>();
        List<TournamentMatch> bracket = new ArrayList<>();

        for (int round = 1; round <= winnersRounds; round++) {
            for (int number = 1, count = 1 << (winnersRounds - round); number <= count; number++) {
                TournamentMatch match = add(bracket, index, tournament, BracketSide.WINNERS, round, number);
                if (round == 1) {
                    match.setParticipant1(seeded(seeding, seeds[2 * number - 2]));
                    match.setParticipant2(seeded(seeding, seeds[2 * number - 1]));
                }
            }
        }
        for (int round = 1; round <= losersRounds; round++) {
            for (int number = 1, count = losersMatches(winnersRounds, round); number <= count; number++) {
                add(bracket, index, tournament, BracketSide.LOSERS, winnersRounds + round, number);
            }
        }
        int grandFinalRound = winnersRounds + losersRounds + 1;
        add(bracket, index, tournament, BracketSide.GRAND_FINAL, grandFinalRound, 1);
        if (tournament.isGrandFinalReset()) {
            add(bracket, index, tournament, BracketSide.GRAND_FINAL, grandFinalRound + 1, 1);
        }

        link(index, winnersRounds, losersRounds, grandFinalRound);
        propagate(bracket);
        return bracket;
    }

    /**
     * Records that {@code completed} has been decided: moves its winner and its loser to wherever
     * they go next and settles whatever that unlocks. Returns the matches that became READY.
     */
    public List<TournamentMatch> advance(List<TournamentMatch> bracket, TournamentMatch completed) {
        Position completedPosition = position(completed);
        List<TournamentMatch> current = new ArrayList<>(bracket.size());
        for (TournamentMatch match : bracket) {
            current.add(position(match).equals(completedPosition) ? completed : match);
        }
        placeOutcome(byId(current), completed);
        return propagate(current);
    }

    /**
     * Walks the bracket in ascending round order and settles every unfinished match whose two sides
     * are known: two participants make it READY, a participant facing an empty side wins by BYE, and
     * two empty sides complete it without a winner. A side still waiting for a feeder keeps the match
     * PENDING. Returns the matches that became READY during this call.
     */
    public List<TournamentMatch> propagate(List<TournamentMatch> bracket) {
        List<TournamentMatch> ordered = new ArrayList<>(bracket);
        ordered.sort(BRACKET_ORDER);
        Map<SideKey, Source> sources = sources(bracket);
        Map<Long, TournamentMatch> byId = byId(bracket);
        TournamentMatch decider = decider(ordered);

        List<TournamentMatch> becameReady = new ArrayList<>();
        for (TournamentMatch match : ordered) {
            if (match.getStatus() == MatchStatus.COMPLETED) {
                continue;
            }
            if (match == decider) {
                settleDecider(decider, grandFinal(ordered), becameReady);
                continue;
            }
            Slot first = slot(match.getParticipant1(), sources.get(sideKey(match, 1)));
            Slot second = slot(match.getParticipant2(), sources.get(sideKey(match, 2)));
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
            placeOutcome(byId, match);
        }
        return becameReady;
    }

    /** Number of losers rounds: none for a two-player bracket, otherwise 2n-2. */
    static int losersRounds(int winnersRounds) {
        return winnersRounds < 2 ? 0 : 2 * winnersRounds - 2;
    }

    /** Losers round r holds 2^(n-1-ceil(r/2)) matches, halving after every pairing round. */
    static int losersMatches(int winnersRounds, int losersRound) {
        return 1 << (winnersRounds - 1 - (losersRound + 1) / 2);
    }

    // --- structure ---

    private void link(Map<Position, TournamentMatch> index, int winnersRounds, int losersRounds, int grandFinalRound) {
        TournamentMatch grandFinal = index.get(new Position(grandFinalRound, 1));

        for (int round = 1; round <= winnersRounds; round++) {
            for (int number = 1, count = 1 << (winnersRounds - round); number <= count; number++) {
                TournamentMatch match = index.get(new Position(round, number));
                if (round < winnersRounds) {
                    setNext(match, index.get(new Position(round + 1, (number + 1) / 2)), side(number));
                } else {
                    setNext(match, grandFinal, 1);
                }
                if (losersRounds == 0) {
                    // Two players: the loser of the only match goes straight to the grand final.
                    setLoserNext(match, grandFinal, 2);
                } else if (round == 1) {
                    setLoserNext(match, index.get(new Position(winnersRounds + 1, (number + 1) / 2)), side(number));
                } else {
                    setLoserNext(match, index.get(new Position(winnersRounds + 2 * (round - 1), number)), 2);
                }
            }
        }
        for (int round = 1; round <= losersRounds; round++) {
            for (int number = 1, count = losersMatches(winnersRounds, round); number <= count; number++) {
                TournamentMatch match = index.get(new Position(winnersRounds + round, number));
                if (round == losersRounds) {
                    setNext(match, grandFinal, 2);
                } else if ((round + 1) % 2 == 0) {
                    // The next round also takes a winners-bracket loser, on side 2.
                    setNext(match, index.get(new Position(winnersRounds + round + 1, number)), 1);
                } else {
                    setNext(match, index.get(new Position(winnersRounds + round + 1, (number + 1) / 2)), side(number));
                }
            }
        }
    }

    private TournamentMatch add(List<TournamentMatch> bracket, Map<Position, TournamentMatch> index,
                                Tournament tournament, BracketSide side, int round, int number) {
        TournamentMatch match = new TournamentMatch();
        match.setTournament(tournament);
        match.setBracket(side);
        match.setRoundNumber(round);
        match.setMatchNumber(number);
        match.setStatus(MatchStatus.PENDING);
        bracket.add(match);
        index.put(new Position(round, number), match);
        return match;
    }

    private void setNext(TournamentMatch match, TournamentMatch next, int slot) {
        match.setNextMatch(next);
        match.setNextMatchSlot(slot);
    }

    private void setLoserNext(TournamentMatch match, TournamentMatch next, int slot) {
        match.setNextLoserMatch(next);
        match.setNextLoserSlot(slot);
    }

    private int side(int number) {
        return number % 2 == 1 ? 1 : 2;
    }

    // --- settling ---

    /**
     * The deciding rematch is not fed by links: it is played only when the losers-bracket finalist
     * wins the grand final, and is otherwise marked complete without participants, so the tournament
     * can finish while the bracket still shows that the match existed.
     */
    private void settleDecider(TournamentMatch decider, TournamentMatch grandFinal, List<TournamentMatch> becameReady) {
        if (grandFinal == null || grandFinal.getStatus() != MatchStatus.COMPLETED) {
            return;
        }
        if (wonBy(grandFinal, grandFinal.getParticipant2())) {
            decider.setParticipant1(grandFinal.getParticipant1());
            decider.setParticipant2(grandFinal.getParticipant2());
            if (decider.getStatus() != MatchStatus.READY) {
                decider.setStatus(MatchStatus.READY);
                becameReady.add(decider);
            }
            return;
        }
        decider.setStatus(MatchStatus.COMPLETED); // not needed: the unbeaten finalist won the grand final
    }

    private Slot slot(Participant participant, Source source) {
        if (participant != null) {
            return Slot.FILLED;
        }
        // No feeder at all is an unused seed position in winners round 1, i.e. a BYE.
        if (source == null || source.match().getStatus() == MatchStatus.COMPLETED) {
            return Slot.EMPTY;
        }
        return Slot.WAITING;
    }

    private void placeOutcome(Map<Long, TournamentMatch> byId, TournamentMatch match) {
        TournamentMatch next = resolve(byId, match.getNextMatch());
        if (next != null && match.getWinner() != null) {
            setSide(next, match.getNextMatchSlot(), match.getWinner());
        }
        TournamentMatch loserNext = resolve(byId, match.getNextLoserMatch());
        Participant loser = loserOf(match);
        if (loserNext != null && loser != null) {
            setSide(loserNext, match.getNextLoserSlot(), loser);
        }
    }

    private void setSide(TournamentMatch match, Integer slot, Participant participant) {
        if (slot != null && slot == 2) {
            match.setParticipant2(participant);
        } else {
            match.setParticipant1(participant);
        }
    }

    /** The beaten participant, or null when the match was a BYE, a draw or is still open. */
    private Participant loserOf(TournamentMatch match) {
        if (match.getWinner() == null || match.getParticipant1() == null || match.getParticipant2() == null) {
            return null;
        }
        return wonBy(match, match.getParticipant1()) ? match.getParticipant2() : match.getParticipant1();
    }

    private boolean wonBy(TournamentMatch match, Participant participant) {
        return same(match.getWinner(), participant);
    }

    /** Identity first, id second: works for an unsaved bracket and for one loaded as proxies. */
    private boolean same(Participant a, Participant b) {
        if (a == null || b == null) {
            return false;
        }
        return a == b || (a.getId() != null && a.getId().equals(b.getId()));
    }

    /** Which match (and which of its outcomes) every side of every match is waiting for. */
    private Map<SideKey, Source> sources(List<TournamentMatch> bracket) {
        Map<Long, TournamentMatch> byId = byId(bracket);
        Map<SideKey, Source> sources = new HashMap<>();
        for (TournamentMatch match : bracket) {
            TournamentMatch next = resolve(byId, match.getNextMatch());
            if (next != null) {
                sources.put(sideKey(next, slotOf(match.getNextMatchSlot())), new Source(match, false));
            }
            TournamentMatch loserNext = resolve(byId, match.getNextLoserMatch());
            if (loserNext != null) {
                sources.put(sideKey(loserNext, slotOf(match.getNextLoserSlot())), new Source(match, true));
            }
        }
        return sources;
    }

    /**
     * The instance of {@code link} that belongs to this list. Reading only the id never initialises a
     * lazy proxy, so a database-loaded bracket is walked without extra queries.
     */
    private TournamentMatch resolve(Map<Long, TournamentMatch> byId, TournamentMatch link) {
        if (link == null) {
            return null;
        }
        if (link.getId() == null) {
            return link; // freshly generated bracket: the link already points at the right object
        }
        return byId.get(link.getId());
    }

    private Map<Long, TournamentMatch> byId(List<TournamentMatch> bracket) {
        Map<Long, TournamentMatch> byId = new HashMap<>();
        for (TournamentMatch match : bracket) {
            if (match.getId() != null) {
                byId.put(match.getId(), match);
            }
        }
        return byId;
    }

    /** Sides are addressed by position so that new and database-loaded matches key the same way. */
    private SideKey sideKey(TournamentMatch match, int slot) {
        return new SideKey(match.getRoundNumber(), match.getMatchNumber(), slot);
    }

    private int slotOf(Integer slot) {
        return slot != null && slot == 2 ? 2 : 1;
    }

    private TournamentMatch grandFinal(List<TournamentMatch> ordered) {
        return ordered.stream().filter(m -> m.getBracket() == BracketSide.GRAND_FINAL).findFirst().orElse(null);
    }

    /** The deciding rematch, i.e. the second grand-final match, when the tournament has one. */
    private TournamentMatch decider(List<TournamentMatch> ordered) {
        List<TournamentMatch> finals = ordered.stream().filter(m -> m.getBracket() == BracketSide.GRAND_FINAL).toList();
        return finals.size() > 1 ? finals.get(1) : null;
    }

    private Position position(TournamentMatch match) {
        return new Position(match.getRoundNumber(), match.getMatchNumber());
    }

    private Participant seeded(List<Participant> seeding, int seed) {
        return seed <= seeding.size() ? seeding.get(seed - 1) : null;
    }

    private int nextPowerOfTwo(int n) {
        int highest = Integer.highestOneBit(n);
        return highest == n ? n : highest << 1;
    }
}
