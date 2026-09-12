package com.ilko.tournament.service;

import com.ilko.tournament.dto.*;
import com.ilko.tournament.entity.*;
import com.ilko.tournament.enums.*;
import com.ilko.tournament.repository.*;
import com.ilko.tournament.service.impl.TournamentService;
import com.ilko.tournament.exception.BusinessException;
import com.ilko.tournament.exception.UnauthorizedOperationException;
import com.ilko.tournament.exception.ConflictException;
import com.ilko.tournament.exception.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TournamentServiceTest {
    @Mock TournamentRepository tournaments;
    @Mock AppUserRepository users;
    @Mock ParticipantRepository participants;
    @Mock TournamentMatchRepository matches;
    @Mock NotificationRepository notifications;
    @Mock TournamentGroupRepository groups;
    @org.mockito.Spy BracketGenerator bracketGenerator = new BracketGenerator();
    @org.mockito.Spy DoubleEliminationGenerator doubleEliminationGenerator = new DoubleEliminationGenerator();
    @org.mockito.Spy RankingCalculator rankingCalculator = new RankingCalculator();
    @org.mockito.Spy RoundRobinScheduler roundRobinScheduler = new RoundRobinScheduler();
    @org.mockito.Spy TournamentStatisticsCalculator statisticsCalculator = new TournamentStatisticsCalculator();
    @InjectMocks TournamentService service;

    @Test void generatesCompleteSevenParticipantBracketWithOneBye() {
        Tournament tournament = tournament(7);
        List<TournamentMatch> persisted = new ArrayList<>();
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        lenient().when(matches.existsByTournamentId(1L)).thenReturn(false);
        lenient().when(matches.saveAll(any())).thenAnswer(inv -> { Iterable<?> batch = inv.getArgument(0); for (Object item : batch) persisted.add((TournamentMatch) item); return inv.getArgument(0); });
        when(tournaments.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(1L)).thenAnswer(inv -> persisted.stream().sorted(Comparator.comparingInt(TournamentMatch::getRoundNumber).thenComparingInt(TournamentMatch::getMatchNumber)).toList());

        List<MatchResponse> bracket = service.generateBracket(1L, organizer());

        assertEquals(7, bracket.size());
        assertEquals(4, bracket.stream().filter(m -> m.round() == 1).count());
        assertEquals(1, bracket.stream().filter(m -> m.round() == 1 && "COMPLETED".equals(m.status())).count());
        assertEquals(7, bracket.stream().flatMap(m -> java.util.stream.Stream.of(m.participant1Id(), m.participant2Id())).filter(Objects::nonNull).distinct().count());
    }

    @Test void advancesWinnerIntoNextMatch() {
        Tournament tournament = tournament(2);
        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        Participant first = tournament.getParticipants().get(0), second = tournament.getParticipants().get(1);
        TournamentMatch match = new TournamentMatch(); match.setId(10L); match.setTournament(tournament); match.setRoundNumber(1); match.setMatchNumber(1); match.setParticipant1(first); match.setParticipant2(second); match.setStatus(MatchStatus.READY);
        when(matches.findById(10L)).thenReturn(Optional.of(match)); when(matches.save(any())).thenAnswer(inv -> inv.getArgument(0)); when(matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(1L)).thenReturn(List.of(match)); when(tournaments.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MatchResponse result = service.result(10L, new MatchResultRequest(3, 1), organizer());

        assertEquals(first.getId(), result.winnerId());
        assertEquals(MatchStatus.COMPLETED.name(), result.status());
        assertEquals(TournamentStatus.COMPLETED, tournament.getStatus());
    }

    @Test void rejectsResultEntryWhenTournamentIsNotInProgress() {
        Tournament tournament = tournament(2);
        tournament.setStatus(TournamentStatus.REGISTRATION);
        TournamentMatch match = new TournamentMatch();
        match.setId(10L);
        match.setTournament(tournament);
        match.setRoundNumber(1);
        match.setMatchNumber(1);
        match.setParticipant1(tournament.getParticipants().get(0));
        match.setParticipant2(tournament.getParticipants().get(1));
        match.setStatus(MatchStatus.READY);

        when(matches.findById(10L)).thenReturn(Optional.of(match));

        assertThrows(com.ilko.tournament.exception.BusinessException.class,
                () -> service.result(10L, new MatchResultRequest(3, 1), organizer()));
    }

    @Test void generatesRoundRobinMatchesForGroupTournaments() {
        Tournament tournament = tournament(3);
        tournament.setFormat(TournamentFormat.GROUPS);
        List<TournamentMatch> persisted = new ArrayList<>();
        TournamentGroup group = new TournamentGroup();
        group.setId(10L);
        group.setTournament(tournament);
        group.setName("Group A");
        group.getParticipants().addAll(tournament.getParticipants());
        tournament.getParticipants().forEach(participant -> participant.setGroup(group));
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        when(groups.findByTournamentIdOrderByNameAsc(1L)).thenReturn(List.of(group));
        lenient().when(matches.existsByTournamentId(1L)).thenReturn(false);
        lenient().when(matches.saveAll(any())).thenAnswer(inv -> { Iterable<?> batch = inv.getArgument(0); for (Object item : batch) persisted.add((TournamentMatch) item); return inv.getArgument(0); });
        when(tournaments.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(1L)).thenAnswer(inv -> persisted.stream().sorted(Comparator.comparingInt(TournamentMatch::getRoundNumber).thenComparingInt(TournamentMatch::getMatchNumber)).toList());

        List<MatchResponse> matchesGenerated = service.generateBracket(1L, organizer());

        assertEquals(3, matchesGenerated.size());
        assertTrue(matchesGenerated.stream().anyMatch(m -> Objects.equals(m.participant1Id(), 1L) && Objects.equals(m.participant2Id(), 2L)));
        assertTrue(matchesGenerated.stream().anyMatch(m -> Objects.equals(m.participant1Id(), 1L) && Objects.equals(m.participant2Id(), 3L)));
        assertTrue(matchesGenerated.stream().anyMatch(m -> Objects.equals(m.participant1Id(), 2L) && Objects.equals(m.participant2Id(), 3L)));
        verify(groups, never()).save(any(TournamentGroup.class));
    }

    @Test void createsAndPersistsDistinctTournamentFormats() {
        AppUser organizerUser = new AppUser();
        organizerUser.setUsername("organizer");
        when(users.findByUsername("organizer")).thenReturn(Optional.of(organizerUser));
        when(tournaments.save(any())).thenAnswer(inv -> {
            Tournament tournament = inv.getArgument(0);
            if (tournament.getId() == null) tournament.setId(System.nanoTime());
            return tournament;
        });

        TournamentResponse elimination = service.create(new CreateTournamentRequest("Elimination Cup", "Cup", TournamentFormat.ELIMINATION, LocalDate.now().plusDays(2), LocalDate.now().plusDays(5), false), organizer());
        TournamentResponse groups = service.create(new CreateTournamentRequest("Group Cup", "Cup", TournamentFormat.GROUPS, LocalDate.now().plusDays(7), LocalDate.now().plusDays(10), false), organizer());

        assertEquals(TournamentFormat.ELIMINATION.name(), elimination.format());
        assertEquals(TournamentFormat.GROUPS.name(), groups.format());
        assertNotEquals(elimination.id(), groups.id());
        assertEquals(TournamentFormat.ELIMINATION, TournamentFormat.valueOf(elimination.format()));
        assertEquals(TournamentFormat.GROUPS, TournamentFormat.valueOf(groups.format()));
    }

    @Test void registeredParticipantsAreUsedForBracketGenerationAndDuplicatesAreRejected() {
        Tournament tournament = tournament(0);
        List<TournamentMatch> persisted = new ArrayList<>();
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        when(participants.save(any())).thenAnswer(inv -> {
            Participant participant = inv.getArgument(0);
            if (participant.getId() == null) participant.setId(System.nanoTime());
            return participant;
        });
        when(tournaments.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(matches.existsByTournamentId(1L)).thenReturn(false);
        lenient().when(matches.saveAll(any())).thenAnswer(inv -> {
            Iterable<?> batch = inv.getArgument(0);
            for (Object item : batch) persisted.add((TournamentMatch) item);
            return inv.getArgument(0);
        });
        lenient().when(matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(1L)).thenAnswer(inv -> persisted.stream().sorted(Comparator.comparingInt(TournamentMatch::getRoundNumber).thenComparingInt(TournamentMatch::getMatchNumber)).toList());

        ParticipantResponse first = service.registerParticipant(1L, new ParticipantRequest("Alpha", null), organizer());
        ParticipantResponse second = service.registerParticipant(1L, new ParticipantRequest("Beta", null), organizer());

        assertEquals(2, service.registered(1L).size());
        assertThrows(com.ilko.tournament.exception.ConflictException.class, () -> service.registerParticipant(1L, new ParticipantRequest("alpha", null), organizer()));

        List<MatchResponse> bracket = service.generateBracket(1L, organizer());
        assertTrue(bracket.stream().anyMatch(match -> Objects.equals(match.participant1Id(), first.id()) && Objects.equals(match.participant2Id(), second.id())));
    }

    @Test void rejectsUpdateByAnotherOrganizer() {
        Tournament tournament = tournament(0);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));

        assertThrows(com.ilko.tournament.exception.UnauthorizedOperationException.class, () ->
                service.update(1L, new UpdateTournamentRequest("Changed", null, LocalDate.now(), LocalDate.now().plusDays(1)),
                        new TestingAuthenticationToken("another-organizer", "password", "ROLE_ORGANIZER")));
        verify(tournaments, never()).save(any());
    }

    @Test void rejectsDuplicateParticipantNamesIgnoringCase() {
        Tournament tournament = tournament(2);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));

        assertThrows(com.ilko.tournament.exception.ConflictException.class, () ->
                service.registerParticipant(1L, new ParticipantRequest("p1", null), organizer()));
        verify(participants, never()).save(any());
    }

    @Test void sameParticipantNameIsAllowedInDifferentTournaments() {
        // The uniqueness rule is scoped to (tournament, name) - the same name must remain free to use
        // in a completely different tournament.
        Tournament tournamentOne = tournament(0);
        Tournament tournamentTwo = tournament(0);
        tournamentTwo.setId(2L);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournamentOne));
        when(tournaments.findById(2L)).thenReturn(Optional.of(tournamentTwo));
        lenient().when(participants.save(any())).thenAnswer(inv -> {
            Participant participant = inv.getArgument(0);
            if (participant.getId() == null) participant.setId(System.nanoTime());
            return participant;
        });

        assertDoesNotThrow(() -> service.registerParticipant(1L, new ParticipantRequest("Alpha", null), organizer()));
        assertDoesNotThrow(() -> service.registerParticipant(2L, new ParticipantRequest("Alpha", null), organizer()),
                "The same name must be allowed in a different tournament");
    }

    @Test void rejectsUpdatesOutsideRegistrationState() {
        Tournament tournament = tournament(0);
        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));

        assertThrows(com.ilko.tournament.exception.BusinessException.class, () ->
                service.update(1L, new UpdateTournamentRequest("Changed", null, LocalDate.now(), LocalDate.now().plusDays(1)), organizer()));
        verify(tournaments, never()).save(any());
    }

    @Test void administratorCanManageATournamentTheyDoNotPersonallyOrganize() {
        // tournament() sets the organizer's username to "organizer" - an ADMINISTRATOR authenticated as
        // a completely different user must still be allowed through the ownership check.
        Tournament tournament = tournament(0);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        when(tournaments.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var adminAuth = new TestingAuthenticationToken("some-admin", "password", "ROLE_ADMINISTRATOR");

        assertDoesNotThrow(() -> service.update(1L,
                new UpdateTournamentRequest("Changed by admin", null, LocalDate.now(), LocalDate.now().plusDays(1)), adminAuth));
        verify(tournaments).save(any());
    }

    @Test void nonAdministratorNonOwnerCannotManageSomeoneElsesTournament() {
        Tournament tournament = tournament(0);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        var otherOrganizer = new TestingAuthenticationToken("someone-else", "password", "ROLE_ORGANIZER");

        assertThrows(com.ilko.tournament.exception.UnauthorizedOperationException.class, () -> service.update(1L,
                new UpdateTournamentRequest("Changed", null, LocalDate.now(), LocalDate.now().plusDays(1)), otherOrganizer));
        verify(tournaments, never()).save(any());
    }

    // ===================== MATCH RESULT: additional required coverage =====================

    @Test void rejectsResultSubmissionByAnUnauthorizedUser() {
        Tournament tournament = tournament(2);
        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        TournamentMatch match = new TournamentMatch(); match.setId(10L); match.setTournament(tournament); match.setRoundNumber(1); match.setMatchNumber(1);
        match.setParticipant1(tournament.getParticipants().get(0)); match.setParticipant2(tournament.getParticipants().get(1)); match.setStatus(MatchStatus.READY);
        when(matches.findById(10L)).thenReturn(Optional.of(match));

        assertThrows(com.ilko.tournament.exception.UnauthorizedOperationException.class, () ->
                service.result(10L, new MatchResultRequest(3, 1), new TestingAuthenticationToken("someone-else", "password", "ROLE_ORGANIZER")));
        verify(matches, never()).save(any());
    }

    @Test void rejectsResultForAMatchThatIsAlreadyCompleted() {
        Tournament tournament = tournament(2);
        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        TournamentMatch match = new TournamentMatch(); match.setId(10L); match.setTournament(tournament); match.setRoundNumber(1); match.setMatchNumber(1);
        match.setParticipant1(tournament.getParticipants().get(0)); match.setParticipant2(tournament.getParticipants().get(1));
        match.setStatus(MatchStatus.COMPLETED); match.setScore1(3); match.setScore2(1); match.setWinner(tournament.getParticipants().get(0));
        when(matches.findById(10L)).thenReturn(Optional.of(match));

        assertThrows(com.ilko.tournament.exception.BusinessException.class, () ->
                service.result(10L, new MatchResultRequest(2, 2), organizer()));
        verify(matches, never()).save(any());
    }

    @Test void rejectsNegativeScores() {
        Tournament tournament = tournament(2);
        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        TournamentMatch match = new TournamentMatch(); match.setId(10L); match.setTournament(tournament); match.setRoundNumber(1); match.setMatchNumber(1);
        match.setParticipant1(tournament.getParticipants().get(0)); match.setParticipant2(tournament.getParticipants().get(1)); match.setStatus(MatchStatus.READY);
        when(matches.findById(10L)).thenReturn(Optional.of(match));

        assertThrows(com.ilko.tournament.exception.BusinessException.class, () ->
                service.result(10L, new MatchResultRequest(-1, 2), organizer()));
        verify(matches, never()).save(any());
    }

    @Test void rejectsTiedScores() {
        Tournament tournament = tournament(2);
        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        TournamentMatch match = new TournamentMatch(); match.setId(10L); match.setTournament(tournament); match.setRoundNumber(1); match.setMatchNumber(1);
        match.setParticipant1(tournament.getParticipants().get(0)); match.setParticipant2(tournament.getParticipants().get(1)); match.setStatus(MatchStatus.READY);
        when(matches.findById(10L)).thenReturn(Optional.of(match));

        assertThrows(com.ilko.tournament.exception.BusinessException.class, () ->
                service.result(10L, new MatchResultRequest(2, 2), organizer()));
        verify(matches, never()).save(any());
    }

    @Test void groupTournamentStandingsReflectRecordedResultsImmediately() {
        Tournament tournament = tournament(3);
        tournament.setFormat(TournamentFormat.GROUPS);
        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        Participant a = tournament.getParticipants().get(0), b = tournament.getParticipants().get(1), c = tournament.getParticipants().get(2);

        TournamentMatch abMatch = new TournamentMatch(); abMatch.setId(20L); abMatch.setTournament(tournament); abMatch.setRoundNumber(1); abMatch.setMatchNumber(1);
        abMatch.setParticipant1(a); abMatch.setParticipant2(b); abMatch.setStatus(MatchStatus.READY);
        // The other round-robin matches for this group have not been played yet, so the tournament as a whole is still in progress.
        TournamentMatch acMatch = new TournamentMatch(); acMatch.setId(21L); acMatch.setTournament(tournament); acMatch.setRoundNumber(1); acMatch.setMatchNumber(2);
        acMatch.setParticipant1(a); acMatch.setParticipant2(c); acMatch.setStatus(MatchStatus.READY);
        TournamentMatch bcMatch = new TournamentMatch(); bcMatch.setId(22L); bcMatch.setTournament(tournament); bcMatch.setRoundNumber(2); bcMatch.setMatchNumber(1);
        bcMatch.setParticipant1(b); bcMatch.setParticipant2(c); bcMatch.setStatus(MatchStatus.READY);

        when(matches.findById(20L)).thenReturn(Optional.of(abMatch));
        when(matches.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        when(matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(1L)).thenReturn(List.of(abMatch, acMatch, bcMatch));

        service.result(20L, new MatchResultRequest(4, 1), organizer());

        assertEquals(TournamentStatus.IN_PROGRESS, tournament.getStatus(), "Tournament must not be marked complete while matches are still pending");
        var standings = service.rankings(1L);
        assertEquals("P1", standings.get(0).participant());
        assertEquals(1, standings.get(0).wins());
        assertEquals(3, standings.get(0).points());
        // Participant C has not played yet and should still be listed with zero matches played.
        assertTrue(standings.stream().anyMatch(r -> r.participant().equals("P3") && r.matchesPlayed() == 0));
    }

    @Test void createGroupKeepsTournamentGroupsCollectionInSyncInMemory() {
        Tournament tournament = tournament(2);
        tournament.setFormat(TournamentFormat.GROUPS);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        when(groups.findByTournamentIdAndName(1L, "Group A")).thenReturn(Optional.empty());
        when(groups.save(any(TournamentGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createGroup(1L, new CreateGroupRequest("Group A"), organizer());

        // The whole point of this test: without touching the repository/DB again, the
        // in-memory inverse side (tournament.getGroups()) must already reflect the group
        // that was just created, in the same persistence context/call.
        assertEquals(1, tournament.getGroups().size());
        TournamentGroup created = tournament.getGroups().get(0);
        assertEquals("Group A", created.getName());
        assertSame(tournament, created.getTournament());
    }

    @Test void groupAssignmentsCanCreateAssignMoveAndRemoveParticipants() {
        Tournament tournament = tournament(3);
        tournament.setFormat(TournamentFormat.GROUPS);
        TournamentGroup firstGroup = group(10L, tournament, "Group A");
        TournamentGroup secondGroup = group(11L, tournament, "Group B");
        Participant participant = tournament.getParticipants().get(0);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        when(groups.findByTournamentIdAndName(1L, "Group A")).thenReturn(Optional.empty());
        when(groups.save(any(TournamentGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(groups.findById(10L)).thenReturn(Optional.of(firstGroup));
        when(groups.findById(11L)).thenReturn(Optional.of(secondGroup));
        when(participants.findById(1L)).thenReturn(Optional.of(participant));

        assertEquals("Group A", service.createGroup(1L, new CreateGroupRequest(" Group A "), organizer()).name());
        service.assignParticipant(1L, 10L, 1L, organizer());
        assertTrue(firstGroup.getParticipants().contains(participant));
        service.assignParticipant(1L, 11L, 1L, organizer());
        assertFalse(firstGroup.getParticipants().contains(participant));
        assertTrue(secondGroup.getParticipants().contains(participant));
        service.assignParticipant(1L, 11L, 1L, organizer()); // re-assigning to the same group must be a safe no-op
        assertTrue(secondGroup.getParticipants().contains(participant));
        service.removeParticipant(1L, 11L, 1L, organizer());
        assertTrue(secondGroup.getParticipants().isEmpty());
    }

    @Test void groupAssignmentRejectsForeignGroupsParticipantsAndUnauthorizedUsers() {
        Tournament tournament = tournament(1);
        tournament.setFormat(TournamentFormat.GROUPS);
        Tournament otherTournament = tournament(1);
        otherTournament.setId(2L);
        TournamentGroup otherGroup = group(20L, otherTournament, "Other");
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        when(groups.findById(20L)).thenReturn(Optional.of(otherGroup));

        assertThrows(UnauthorizedOperationException.class, () -> service.createGroup(1L, new CreateGroupRequest("Group A"), new TestingAuthenticationToken("other", "password", "ROLE_ORGANIZER")));
        assertThrows(BusinessException.class, () -> service.assignParticipant(1L, 20L, 1L, organizer()));
    }

    @Test void groupGenerationRequiresExplicitCompleteAssignments() {
        Tournament tournament = tournament(2);
        tournament.setFormat(TournamentFormat.GROUPS);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        when(groups.findByTournamentIdOrderByNameAsc(1L)).thenReturn(List.of());
        lenient().when(matches.existsByTournamentId(1L)).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.generateBracket(1L, organizer()));
    }

    private TournamentGroup group(Long id, Tournament tournament, String name) { TournamentGroup group = new TournamentGroup(); group.setId(id); group.setTournament(tournament); group.setName(name); return group; }
    // ===================== DELETE =====================

    @Test void deletesTournamentWithoutGroups() {
        Tournament tournament = tournament(2);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));

        service.delete(1L, organizer());

        assertTrue(tournament.getParticipants().isEmpty());
        verify(tournaments).delete(tournament);
    }

    @Test void rejectsDeleteByNonOwner() {
        Tournament tournament = tournament(2);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        TestingAuthenticationToken intruder = new TestingAuthenticationToken("someone-else", "password", "ROLE_ORGANIZER");

        assertThrows(UnauthorizedOperationException.class, () -> service.delete(1L, intruder));

        verify(tournaments, never()).delete(any());
    }

    @Test void rejectsDeleteOnceTournamentIsNoLongerInRegistration() {
        Tournament tournament = tournament(2);
        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));

        assertThrows(BusinessException.class, () -> service.delete(1L, organizer()));

        verify(tournaments, never()).delete(any());
    }

    // ===================== PARTICIPANT SELF-SERVICE =====================

    @Test void participantJoinsWithTheirOwnAccountAndCannotJoinTwice() {
        Tournament tournament = tournament(1);
        AppUser account = new AppUser(); account.setUsername("georgi");
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        when(users.findByUsername("georgi")).thenReturn(Optional.of(account));
        when(participants.save(any())).thenAnswer(inv -> { Participant p = inv.getArgument(0); p.setId(50L); return p; });
        var georgi = new TestingAuthenticationToken("georgi", "password", "ROLE_PARTICIPANT");

        ParticipantResponse joined = service.join(1L, new JoinTournamentRequest(" "), georgi);

        assertEquals("georgi", joined.name(), "A blank display name falls back to the username");
        assertEquals("georgi", joined.linkedUsername());
        assertThrows(ConflictException.class, () -> service.join(1L, new JoinTournamentRequest("Another name"), georgi));
        verify(participants, times(1)).save(any());
    }

    @Test void joiningIsRejectedOnceRegistrationIsClosed() {
        Tournament tournament = tournament(2);
        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));

        assertThrows(BusinessException.class, () -> service.join(1L, null, new TestingAuthenticationToken("georgi", "password", "ROLE_PARTICIPANT")));
        verify(participants, never()).save(any());
    }

    @Test void participantCanWithdrawOnlyTheirOwnEntry() {
        Tournament tournament = tournament(2);
        AppUser account = new AppUser(); account.setUsername("georgi");
        tournament.getParticipants().get(1).setAppUser(account);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));

        assertThrows(ResourceNotFoundException.class, () -> service.leave(1L, new TestingAuthenticationToken("elena", "password", "ROLE_PARTICIPANT")));
        service.leave(1L, new TestingAuthenticationToken("georgi", "password", "ROLE_PARTICIPANT"));

        assertEquals(List.of("P1"), tournament.getParticipants().stream().map(Participant::getName).toList());
    }

    @Test void organizerCannotLinkTheSameAccountToTwoEntries() {
        Tournament tournament = tournament(1);
        AppUser account = new AppUser(); account.setUsername("georgi");
        tournament.getParticipants().get(0).setAppUser(account);
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        when(users.findByUsername("georgi")).thenReturn(Optional.of(account));

        assertThrows(ConflictException.class, () -> service.registerParticipant(1L, new ParticipantRequest("Georgi Again", "georgi"), organizer()));
        verify(participants, never()).save(any());
    }

    // ===================== ELIMINATION FLOW THROUGH THE SERVICE =====================

    @Test void finalBecomesReadyOnlyAfterBothSemiFinalsAndTheTournamentEndsWithTheFinal() {
        Tournament tournament = tournament(4);
        List<TournamentMatch> persisted = new ArrayList<>();
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        when(matches.existsByTournamentId(1L)).thenReturn(false);
        when(matches.saveAll(any())).thenAnswer(inv -> {
            for (Object item : (Iterable<?>) inv.getArgument(0)) {
                TournamentMatch m = (TournamentMatch) item;
                if (m.getId() == null) { m.setId(100L + persisted.size()); persisted.add(m); }
            }
            return inv.getArgument(0);
        });
        when(tournaments.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.generateBracket(1L, organizer());
        when(matches.findById(any())).thenAnswer(inv -> persisted.stream().filter(m -> m.getId().equals(inv.getArgument(0))).findFirst());
        when(matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(1L)).thenReturn(persisted);
        TournamentMatch semiOne = find(persisted, 1, 1), semiTwo = find(persisted, 1, 2), finalMatch = find(persisted, 2, 1);

        service.result(semiOne.getId(), new MatchResultRequest(1, 3), organizer()); // P4 beats seed 1
        assertEquals(MatchStatus.PENDING, finalMatch.getStatus(), "The final still waits for the other semi-final");
        assertEquals(TournamentStatus.IN_PROGRESS, tournament.getStatus());

        service.result(semiTwo.getId(), new MatchResultRequest(2, 0), organizer());
        assertEquals(MatchStatus.READY, finalMatch.getStatus());
        assertEquals("P4", finalMatch.getParticipant1().getName());
        assertEquals("P2", finalMatch.getParticipant2().getName());

        service.result(finalMatch.getId(), new MatchResultRequest(0, 5), organizer());
        assertEquals(TournamentStatus.COMPLETED, tournament.getStatus());
        assertEquals("P2", service.rankings(1L).get(0).participant());
    }

    private TournamentMatch find(List<TournamentMatch> list, int round, int number) { return list.stream().filter(m -> m.getRoundNumber() == round && m.getMatchNumber() == number).findFirst().orElseThrow(); }


    // --- double elimination ---

    @Test void generatesWinnersLosersAndGrandFinalForADoubleEliminationTournament() {
        Tournament tournament = doubleElimination(8, true);
        List<TournamentMatch> persisted = wireBracketRepository(tournament);

        List<MatchResponse> bracket = service.generateBracket(1L, organizer());

        assertEquals(15, bracket.size(), "14 bracket matches plus the deciding rematch");
        assertEquals(7, bracket.stream().filter(m -> "WINNERS".equals(m.bracket())).count());
        assertEquals(6, bracket.stream().filter(m -> "LOSERS".equals(m.bracket())).count());
        assertEquals(2, bracket.stream().filter(m -> "GRAND_FINAL".equals(m.bracket())).count());
        assertEquals(TournamentStatus.IN_PROGRESS, tournament.getStatus());
        // Later rounds are written first so next_match_id / next_loser_match_id always resolve.
        assertTrue(persisted.get(0).getRoundNumber() >= persisted.get(persisted.size() - 1).getRoundNumber());
    }

    @Test void aFirstRoundLoserContinuesInTheLosersBracket() {
        Tournament tournament = doubleElimination(4, false);
        List<TournamentMatch> persisted = wireBracketRepository(tournament);
        service.generateBracket(1L, organizer());
        when(matches.findById(any())).thenAnswer(inv -> persisted.stream().filter(m -> inv.getArgument(0).equals(m.getId())).findFirst());
        when(matches.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TournamentMatch opener = persisted.stream().filter(m -> m.getRoundNumber() == 1 && m.getMatchNumber() == 1).findFirst().orElseThrow();
        Participant beaten = opener.getParticipant2();
        service.result(opener.getId(), new MatchResultRequest(3, 1), organizer());

        // persisted is in save order (later rounds first), so pick the first losers round explicitly.
        TournamentMatch losersOpener = persisted.stream().filter(m -> m.getBracket() == BracketSide.LOSERS)
                .min(Comparator.comparingInt(TournamentMatch::getRoundNumber).thenComparingInt(TournamentMatch::getMatchNumber)).orElseThrow();
        assertSame(beaten, losersOpener.getParticipant1(), "The loser drops into the losers bracket instead of being eliminated");
        assertNotEquals(MatchStatus.COMPLETED, losersOpener.getStatus());
    }

    @Test void rejectsADrawInDoubleEliminationJustLikeInSingleElimination() {
        Tournament tournament = doubleElimination(4, false);
        List<TournamentMatch> persisted = wireBracketRepository(tournament);
        service.generateBracket(1L, organizer());
        when(matches.findById(any())).thenAnswer(inv -> persisted.stream().filter(m -> inv.getArgument(0).equals(m.getId())).findFirst());

        TournamentMatch opener = persisted.stream().filter(m -> m.getStatus() == MatchStatus.READY).findFirst().orElseThrow();
        assertThrows(BusinessException.class, () -> service.result(opener.getId(), new MatchResultRequest(2, 2), organizer()));
    }

    @Test void theGrandFinalRematchFlagOnlyAppliesToDoubleElimination() {
        when(users.findByUsername("organizer")).thenReturn(Optional.of(new AppUser()));
        when(tournaments.save(any())).thenAnswer(inv -> { Tournament t = inv.getArgument(0); t.setId(1L); AppUser u = new AppUser(); u.setUsername("organizer"); t.setOrganizer(u); return t; });

        var dates = new LocalDate[]{LocalDate.now().plusDays(1), LocalDate.now().plusDays(2)};
        assertTrue(service.create(new CreateTournamentRequest("Cup", null, TournamentFormat.DOUBLE_ELIMINATION, dates[0], dates[1], true), organizer()).grandFinalReset());
        assertFalse(service.create(new CreateTournamentRequest("Cup", null, TournamentFormat.ELIMINATION, dates[0], dates[1], true), organizer()).grandFinalReset());
        assertFalse(service.create(new CreateTournamentRequest("Cup", null, TournamentFormat.GROUPS, dates[0], dates[1], true), organizer()).grandFinalReset());
    }

    /** Stores generated matches in a list and serves them back with ids, the way the repository would. */
    private List<TournamentMatch> wireBracketRepository(Tournament tournament) {
        List<TournamentMatch> persisted = new ArrayList<>();
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        lenient().when(matches.existsByTournamentId(1L)).thenReturn(false);
        lenient().when(matches.saveAll(any())).thenAnswer(inv -> {
            long id = persisted.size() + 100L;
            for (Object item : (Iterable<?>) inv.getArgument(0)) {
                TournamentMatch match = (TournamentMatch) item;
                if (match.getId() == null) match.setId(id++);
                persisted.add(match);
            }
            return inv.getArgument(0);
        });
        lenient().when(tournaments.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(1L)).thenAnswer(inv ->
                persisted.stream().sorted(Comparator.comparingInt(TournamentMatch::getRoundNumber).thenComparingInt(TournamentMatch::getMatchNumber)).toList());
        return persisted;
    }

    private Tournament doubleElimination(int count, boolean grandFinalReset) {
        Tournament tournament = tournament(count);
        tournament.setFormat(TournamentFormat.DOUBLE_ELIMINATION);
        tournament.setGrandFinalReset(grandFinalReset);
        return tournament;
    }

    private Tournament tournament(int count) { Tournament t = new Tournament(); t.setId(1L); t.setName("Test"); t.setFormat(TournamentFormat.ELIMINATION); t.setStatus(TournamentStatus.REGISTRATION); t.setStartDate(LocalDate.now()); t.setEndDate(LocalDate.now().plusDays(1)); AppUser u = new AppUser(); u.setUsername("organizer"); t.setOrganizer(u); for (int i=1;i<=count;i++) { Participant p=new Participant(); p.setId((long)i); p.setName("P"+i); p.setTournament(t); t.getParticipants().add(p); } return t; }
    private TestingAuthenticationToken organizer() { return new TestingAuthenticationToken("organizer", "password", "ROLE_ORGANIZER"); }
}
