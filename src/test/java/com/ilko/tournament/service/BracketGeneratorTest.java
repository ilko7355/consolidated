package com.ilko.tournament.service;

import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.MatchStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BracketGeneratorTest {
    private final BracketGenerator generator = new BracketGenerator();

    @Test
    void padsToPowerOfTwoAndResolvesBye() {
        Tournament tournament = new Tournament();
        List<TournamentMatch> matches = generator.generate(tournament, participants(3), ignored -> { });
        generator.resolveReadyAndByes(matches, ignored -> { });

        assertEquals(3, matches.size());
        assertEquals(2, matches.stream().filter(match -> match.getStatus() == MatchStatus.COMPLETED).count());
    }

    @Test
    void createsDeterministicEliminationTreeForEightParticipants() {
        List<TournamentMatch> matches = generator.generate(new Tournament(), participants(8), ignored -> { });

        assertEquals(7, matches.size());
        assertEquals(4, matches.stream().filter(match -> match.getRoundNumber() == 1).count());
        assertEquals(2, matches.stream().filter(match -> match.getRoundNumber() == 2).count());
        assertEquals(1, matches.stream().filter(match -> match.getRoundNumber() == 3).count());

        TournamentMatch round1Match1 = match(matches, 1, 1);
        TournamentMatch round1Match2 = match(matches, 1, 2);
        TournamentMatch round1Match3 = match(matches, 1, 3);
        TournamentMatch round1Match4 = match(matches, 1, 4);

        assertNotNull(round1Match1.getNextMatch());
        assertNotNull(round1Match2.getNextMatch());
        assertNotNull(round1Match3.getNextMatch());
        assertNotNull(round1Match4.getNextMatch());
        assertSame(round1Match1.getNextMatch(), round1Match2.getNextMatch());
        assertSame(round1Match3.getNextMatch(), round1Match4.getNextMatch());
        assertNotSame(round1Match1.getNextMatch(), round1Match3.getNextMatch());
    }

    @Test
    void advancesByeWinnerIntoTheCorrectNextRoundMatch() {
        Tournament tournament = new Tournament();
        List<TournamentMatch> matches = generator.generate(tournament, participants(5), ignored -> { });

        generator.resolveReadyAndByes(matches, ignored -> { });

        TournamentMatch byeMatch = match(matches, 1, 3);
        assertEquals(MatchStatus.COMPLETED, byeMatch.getStatus());
        assertNotNull(byeMatch.getWinner());
        assertNotNull(byeMatch.getNextMatch());
        assertEquals(2, byeMatch.getNextMatch().getRoundNumber());
    }

    @Test
    void rejectsFewerThanTwoParticipants() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(new Tournament(), participants(1), ignored -> { }));
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