package com.ilko.tournament.service;

import com.ilko.tournament.entity.*;
import com.ilko.tournament.enums.*;
import com.ilko.tournament.repository.*;
import com.ilko.tournament.service.impl.TournamentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RankingTest {
    @Mock TournamentRepository tournaments; @Mock AppUserRepository users; @Mock ParticipantRepository participants; @Mock TournamentMatchRepository matches; @Mock NotificationRepository notifications;
    @org.mockito.Spy RankingCalculator rankingCalculator = new RankingCalculator();
    @org.mockito.Spy BracketGenerator bracketGenerator = new BracketGenerator();
    @InjectMocks TournamentService service;
    @Test void ranksByWinsThenLossesDeterministically() {
        Tournament t = new Tournament(); t.setId(1L); t.setName("T"); t.setFormat(TournamentFormat.ELIMINATION); t.setStartDate(LocalDate.now()); t.setEndDate(LocalDate.now());
        AppUser u = new AppUser(); u.setUsername("o"); t.setOrganizer(u); Participant a=p(1,"A"), b=p(2,"B"); t.setParticipants(new ArrayList<>(List.of(a,b)));
        TournamentMatch m=new TournamentMatch(); m.setParticipant1(a); m.setParticipant2(b); m.setWinner(a); m.setScore1(2); m.setScore2(0); m.setStatus(MatchStatus.COMPLETED);
        when(tournaments.findById(1L)).thenReturn(Optional.of(t)); when(matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(1L)).thenReturn(List.of(m));
        var ranking=service.rankings(1L);
        assertEquals("A", ranking.get(0).participant()); assertEquals(1, ranking.get(0).wins()); assertEquals(3, ranking.get(0).points());
    }
    private Participant p(long id,String name){Participant p=new Participant();p.setId(id);p.setName(name);return p;}
}
