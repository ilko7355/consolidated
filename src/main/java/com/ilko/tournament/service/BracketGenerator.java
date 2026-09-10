package com.ilko.tournament.service;

import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.MatchStatus;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Consumer;

@Component
public class BracketGenerator {

    public List<TournamentMatch> generate(Tournament tournament, List<Participant> participants, Consumer<TournamentMatch> onMatchReady) {
        if (participants == null || participants.size() < 2) {
            throw new IllegalArgumentException("Elimination bracket requires at least 2 participants.");
        }

        int n = participants.size();
        int bracketSize = nextPowerOfTwo(n);
        int totalRounds = Integer.numberOfTrailingZeros(bracketSize);

        // Seed participants and pad with nulls for BYEs
        List<Participant> slots = new ArrayList<>(Collections.nCopies(bracketSize, null));
        for (int i = 0; i < n; i++) {
            slots.set(i, participants.get(i));
        }

        List<TournamentMatch> allMatches = new ArrayList<>();
        Map<Integer, List<TournamentMatch>> roundsMap = new HashMap<>();

        // Generate Round 1
        int round1MatchesCount = bracketSize / 2;
        List<TournamentMatch> round1Matches = new ArrayList<>();
        for (int i = 0; i < round1MatchesCount; i++) {
            TournamentMatch match = new TournamentMatch();
            match.setTournament(tournament);
            match.setRoundNumber(1);
            match.setMatchNumber(i + 1);
            match.setParticipant1(slots.get(2 * i));
            match.setParticipant2(slots.get(2 * i + 1));
            match.setStatus(MatchStatus.PENDING);
            round1Matches.add(match);
            allMatches.add(match);
        }
        roundsMap.put(1, round1Matches);

        // Generate subsequent rounds
        int currentCount = round1MatchesCount;
        for (int round = 2; round <= totalRounds; round++) {
            currentCount /= 2;
            List<TournamentMatch> currentRoundMatches = new ArrayList<>();
            for (int i = 0; i < currentCount; i++) {
                TournamentMatch match = new TournamentMatch();
                match.setTournament(tournament);
                match.setRoundNumber(round);
                match.setMatchNumber(i + 1);
                match.setStatus(MatchStatus.PENDING);
                currentRoundMatches.add(match);
                allMatches.add(match);
            }
            roundsMap.put(round, currentRoundMatches);
        }

        // Link matches to their nextMatch
        for (int round = 1; round < totalRounds; round++) {
            List<TournamentMatch> current = roundsMap.get(round);
            List<TournamentMatch> next = roundsMap.get(round + 1);
            for (int i = 0; i < current.size(); i++) {
                TournamentMatch match = current.get(i);
                TournamentMatch nextMatch = next.get(i / 2);
                match.setNextMatch(nextMatch);
            }
        }

        resolveReadyAndByes(allMatches, onMatchReady);

        return allMatches;
    }

    public void resolveReadyAndByes(List<TournamentMatch> matches, Consumer<TournamentMatch> onMatchReady) {
        Map<Integer, List<TournamentMatch>> byRound = new TreeMap<>();
        for (TournamentMatch m : matches) {
            byRound.computeIfAbsent(m.getRoundNumber(), k -> new ArrayList<>()).add(m);
        }

        for (Map.Entry<Integer, List<TournamentMatch>> entry : byRound.entrySet()) {
            for (TournamentMatch match : entry.getValue()) {
                if (match.getStatus() == MatchStatus.COMPLETED) {
                    continue;
                }

                Participant p1 = match.getParticipant1();
                Participant p2 = match.getParticipant2();

                if (p1 != null && p2 != null) {
                    match.setStatus(MatchStatus.READY);
                    if (onMatchReady != null) {
                        onMatchReady.accept(match);
                    }
                } else if (p1 != null && p2 == null) {
                    match.setStatus(MatchStatus.COMPLETED);
                    match.setWinner(p1);
                    advance(match, p1);
                } else if (p1 == null && p2 != null) {
                    match.setStatus(MatchStatus.COMPLETED);
                    match.setWinner(p2);
                    advance(match, p2);
                } else {
                    // Empty branch: null vs null
                    match.setStatus(MatchStatus.COMPLETED);
                    match.setWinner(null);
                    advance(match, null);
                }
            }
        }
    }

    public void advance(TournamentMatch match, Participant winner) {
        TournamentMatch nextMatch = match.getNextMatch();
        if (nextMatch == null) {
            return;
        }

        if (match.getMatchNumber() % 2 != 0) {
            nextMatch.setParticipant1(winner);
        } else {
            nextMatch.setParticipant2(winner);
        }
    }

    private int nextPowerOfTwo(int n) {
        int highest = Integer.highestOneBit(n);
        return (highest == n) ? n : highest << 1;
    }
}