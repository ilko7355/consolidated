package com.ilko.tournament.service;

import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentGroup;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.MatchStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Component
public class BracketGenerator {
    public List<TournamentMatch> generate(Tournament tournament, List<Participant> registeredParticipants,
                                          Consumer<List<TournamentMatch>> persist) {
        if (registeredParticipants == null || registeredParticipants.size() < 2) {
            throw new IllegalArgumentException("At least two participants are required");
        }

        if (tournament != null && tournament.getFormat() == com.ilko.tournament.enums.TournamentFormat.GROUPS) {
            return generateRoundRobin(tournament, registeredParticipants, persist);
        }

        List<Participant> slots = new ArrayList<>(registeredParticipants);
        int bracketSize = 1;
        while (bracketSize < slots.size()) bracketSize *= 2;
        while (slots.size() < bracketSize) slots.add(null);

        List<TournamentMatch> previous = new ArrayList<>();
        List<TournamentMatch> all = new ArrayList<>();
        int rounds = Integer.numberOfTrailingZeros(bracketSize);
        for (int round = 1; round <= rounds; round++) {
            List<TournamentMatch> current = new ArrayList<>();
            for (int number = 0; number < bracketSize / (1 << round); number++) {
                TournamentMatch match = new TournamentMatch();
                match.setTournament(tournament);
                match.setRoundNumber(round);
                match.setMatchNumber(number + 1);
                if (round == 1) {
                    match.setParticipant1(slots.get(number * 2));
                    match.setParticipant2(slots.get(number * 2 + 1));
                }
                current.add(match);
            }
            all.addAll(current);
            for (int index = 0; index < previous.size(); index++) {
                previous.get(index).setNextMatch(current.get(index / 2));
            }
            persist.accept(previous);
            previous = current;
        }
        persist.accept(previous);

        return all;
    }

    public List<TournamentMatch> generateGroupStage(Tournament tournament, List<TournamentGroup> groups,
                                                   Consumer<List<TournamentMatch>> persist) {
        List<TournamentMatch> all = new ArrayList<>();
        for (TournamentGroup group : groups) {
           all.addAll(generateRoundRobin(tournament, group, new ArrayList<>(group.getParticipants()), persist));
        }
        return all;
    }

    private List<TournamentMatch> generateRoundRobin(Tournament tournament, TournamentGroup group,
                                                    List<Participant> participants, Consumer<List<TournamentMatch>> persist) {
        List<Participant> roster = new ArrayList<>(participants);
        if (roster.size() % 2 != 0) {
            roster.add(null);
        }

        List<TournamentMatch> all = new ArrayList<>();
        for (int round = 0; round < roster.size() - 1; round++) {
            List<TournamentMatch> roundMatches = new ArrayList<>();
            for (int index = 0; index < roster.size() / 2; index++) {
                Participant first = roster.get(index);
                Participant second = roster.get(roster.size() - index - 1);
                if (first == null || second == null) {
                    continue;
                }
                TournamentMatch match = new TournamentMatch();
                match.setTournament(tournament);
                match.setGroup(group);
                match.setRoundNumber(round + 1);
                match.setMatchNumber(index + 1);
                match.setParticipant1(first);
                match.setParticipant2(second);
                match.setStatus(MatchStatus.READY);
                roundMatches.add(match);
            }
            all.addAll(roundMatches);
            persist.accept(roundMatches);

            Participant last = roster.get(roster.size() - 1);
            for (int index = roster.size() - 1; index > 0; index--) {
                roster.set(index, roster.get(index - 1));
            }
            roster.set(1, last);
        }
        return all;
    }

    private List<TournamentMatch> generateRoundRobin(Tournament tournament, List<Participant> participants,
                                                    Consumer<List<TournamentMatch>> persist) {
        return generateRoundRobin(tournament, null, participants, persist);
    }

    public void resolveReadyAndByes(List<TournamentMatch> matches, Consumer<List<TournamentMatch>> persist) {
        boolean changed;
        do {
            changed = false;
            for (TournamentMatch match : matches) {
                if (match.getStatus() == MatchStatus.PENDING && match.getParticipant1() != null && match.getParticipant2() != null) {
                    match.setStatus(MatchStatus.READY);
                    changed = true;
                } else if (match.getStatus() == MatchStatus.PENDING && (match.getParticipant1() != null ^ match.getParticipant2() != null)) {
                    match.setWinner(match.getParticipant1() != null ? match.getParticipant1() : match.getParticipant2());
                    match.setStatus(MatchStatus.COMPLETED);
                    advance(match, match.getWinner());
                    changed = true;
                }
            }
            persist.accept(matches);
        } while (changed);
    }

    public void advance(TournamentMatch match, Participant winner) {
        TournamentMatch next = match.getNextMatch();
        if (next == null) return;
        if (match.getMatchNumber() % 2 == 1) next.setParticipant1(winner);
        else next.setParticipant2(winner);
        if (next.getParticipant1() != null && next.getParticipant2() != null) next.setStatus(MatchStatus.READY);
    }
}