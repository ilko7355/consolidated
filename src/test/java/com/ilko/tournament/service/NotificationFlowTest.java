package com.ilko.tournament.service;

import com.ilko.tournament.dto.MatchResultRequest;
import com.ilko.tournament.entity.*;
import com.ilko.tournament.enums.*;
import com.ilko.tournament.repository.*;
import com.ilko.tournament.service.impl.TournamentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers the notification checklist from the spec end-to-end through the real service layer
 * (TournamentService), not just isolated helper methods - these are the actual events
 * (bracket generated, result recorded, tournament completed) that trigger notifications.
 */
@ExtendWith(MockitoExtension.class)
class NotificationFlowTest {
    @Mock TournamentRepository tournaments;
    @Mock AppUserRepository users;
    @Mock ParticipantRepository participants;
    @Mock TournamentMatchRepository matches;
    @Mock NotificationRepository notifications;
    @Mock TournamentGroupRepository groups;
    @Spy BracketGenerator bracketGenerator = new BracketGenerator();
    @Spy RankingCalculator rankingCalculator = new RankingCalculator();
    @InjectMocks TournamentService service;

    @Test
    void upcomingMatchNotifiesLinkedParticipantsAndOrganizerWithRealMatchData() {
        AppUser organizerAccount = appUser(100L, "organizer");
        AppUser aliceAccount = appUser(1L, "alice");
        AppUser bobAccount = appUser(2L, "bob");
        Participant alice = participant(1L, "Alice", aliceAccount);
        Participant bob = participant(2L, "Bob", bobAccount);
        Tournament tournament = tournament(organizerAccount, alice, bob);

        List<TournamentMatch> persisted = generateBracket(tournament);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, times(3)).save(captor.capture()); // alice + bob + organizer
        List<Notification> saved = captor.getAllValues();
        assertTrue(saved.stream().allMatch(n -> n.getType() == NotificationType.MATCH_SCHEDULED));
        assertTrue(saved.stream().allMatch(n -> n.getTournament() == tournament));
        assertTrue(saved.stream().allMatch(n -> n.getMatch() != null), "Every upcoming-match notification must reference the real match");

        Notification aliceNotification = findFor(saved, aliceAccount);
        assertTrue(aliceNotification.getMessage().contains("Bob"), "Alice should be told her opponent is Bob");
        assertTrue(aliceNotification.getMessage().contains(tournament.getName()));

        Notification bobNotification = findFor(saved, bobAccount);
        assertTrue(bobNotification.getMessage().contains("Alice"), "Bob should be told his opponent is Alice");

        Notification organizerNotification = findFor(saved, organizerAccount);
        assertTrue(organizerNotification.getMessage().contains("Alice") && organizerNotification.getMessage().contains("Bob"));
        assertEquals(1, persisted.size());
    }

    @Test
    void participantsWithoutALinkedAccountReceiveNoNotification() {
        AppUser organizerAccount = appUser(100L, "organizer");
        Participant alice = participant(1L, "Alice", null); // no account linked
        Participant bob = participant(2L, "Bob", null);
        Tournament tournament = tournament(organizerAccount, alice, bob);

        generateBracket(tournament);

        // Only the organizer can be notified - there is no account to notify for either participant.
        verify(notifications, times(1)).save(any());
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(captor.capture());
        assertEquals(organizerAccount, captor.getValue().getRecipient());
    }

    @Test
    void unrelatedUserIsNeverNotified() {
        AppUser organizerAccount = appUser(100L, "organizer");
        AppUser aliceAccount = appUser(1L, "alice");
        AppUser bobAccount = appUser(2L, "bob");
        AppUser unrelatedAccount = appUser(999L, "stranger"); // registered on the platform, but not in this tournament
        Participant alice = participant(1L, "Alice", aliceAccount);
        Participant bob = participant(2L, "Bob", bobAccount);
        Tournament tournament = tournament(organizerAccount, alice, bob);

        generateBracket(tournament);

        verify(notifications, never()).save(argThat(n -> n.getRecipient() == unrelatedAccount));
    }

    @Test
    void duplicateNotificationIsNeverPersistedWhenOneAlreadyExistsForThatRecipientAndMatch() {
        AppUser organizerAccount = appUser(100L, "organizer");
        AppUser aliceAccount = appUser(1L, "alice");
        AppUser bobAccount = appUser(2L, "bob");
        Participant alice = participant(1L, "Alice", aliceAccount);
        Participant bob = participant(2L, "Bob", bobAccount);
        Tournament tournament = tournament(organizerAccount, alice, bob);

        // Simulate that an "upcoming match" notification was already generated for every possible recipient/match/type combo.
        when(notifications.existsByRecipientAndMatchAndType(any(), any(), eq(NotificationType.MATCH_SCHEDULED))).thenReturn(true);

        generateBracket(tournament);

        verify(notifications, never()).save(any());
    }

    @Test
    void recordingAResultNeverNotifiesParticipantsOnlyTheOrganizer() {
        // Design decision documented in TournamentService: participants are notified about upcoming
        // matches and final results, but not about every individual match result - the organizer is.
        AppUser organizerAccount = appUser(100L, "organizer");
        AppUser aliceAccount = appUser(1L, "alice");
        AppUser bobAccount = appUser(2L, "bob");
        Participant alice = participant(1L, "Alice", aliceAccount);
        Participant bob = participant(2L, "Bob", bobAccount);
        Tournament tournament = tournament(organizerAccount, alice, bob);
        List<TournamentMatch> persisted = generateBracket(tournament);
        reset(notifications); // ignore the "upcoming match" notifications from bracket generation
        TournamentMatch match = persisted.get(0);
        when(matches.findById(match.getId())).thenReturn(Optional.of(match));
        when(matches.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(tournament.getId())).thenReturn(persisted);

        service.result(match.getId(), new MatchResultRequest(3, 1), organizerAuth(organizerAccount));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .filter(n -> n.getType() == NotificationType.MATCH_RESULT)
                .noneMatch(n -> n.getRecipient() == aliceAccount || n.getRecipient() == bobAccount),
                "Participants must not be notified about an individual match result");
    }

    @Test
    void finalResultsAreNotPublishedWhileOtherMatchesAreStillPending() {
        AppUser organizerAccount = appUser(100L, "organizer");
        AppUser p1Account = appUser(1L, "p1"), p2Account = appUser(2L, "p2"), p3Account = appUser(3L, "p3"), p4Account = appUser(4L, "p4");
        Participant p1 = participant(1L, "P1", p1Account), p2 = participant(2L, "P2", p2Account);
        Participant p3 = participant(3L, "P3", p3Account), p4 = participant(4L, "P4", p4Account);
        Tournament tournament = tournament(organizerAccount, p1, p2, p3, p4);
        List<TournamentMatch> persisted = generateBracket(tournament);
        reset(notifications);
        TournamentMatch firstRoundMatchA = persisted.stream().filter(m -> m.getRoundNumber() == 1 && m.getMatchNumber() == 1).findFirst().orElseThrow();
        when(matches.findById(firstRoundMatchA.getId())).thenReturn(Optional.of(firstRoundMatchA));
        when(matches.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(tournament.getId())).thenReturn(persisted);

        service.result(firstRoundMatchA.getId(), new MatchResultRequest(3, 0), organizerAuth(organizerAccount));

        assertEquals(TournamentStatus.IN_PROGRESS, tournament.getStatus(), "Tournament must not complete after only one of several matches");
        verify(notifications, never()).save(argThat(n -> n.getType() == NotificationType.TOURNAMENT_COMPLETED));
    }

    @Test
    void finalResultsArePublishedToOrganizerAndLinkedParticipantsOnceTheTournamentActuallyCompletes() {
        AppUser organizerAccount = appUser(100L, "organizer");
        AppUser aliceAccount = appUser(1L, "alice");
        AppUser bobAccount = appUser(2L, "bob");
        Participant alice = participant(1L, "Alice", aliceAccount);
        Participant bob = participant(2L, "Bob", bobAccount);
        Tournament tournament = tournament(organizerAccount, alice, bob); // 2 participants -> single final match
        List<TournamentMatch> persisted = generateBracket(tournament);
        reset(notifications);
        TournamentMatch finalMatch = persisted.get(0);
        when(matches.findById(finalMatch.getId())).thenReturn(Optional.of(finalMatch));
        when(matches.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(tournament.getId())).thenReturn(persisted);
        when(tournaments.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.result(finalMatch.getId(), new MatchResultRequest(4, 2), organizerAuth(organizerAccount));

        assertEquals(TournamentStatus.COMPLETED, tournament.getStatus());
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, atLeastOnce()).save(captor.capture());
        List<Notification> finalResultNotifications = captor.getAllValues().stream()
                .filter(n -> n.getType() == NotificationType.TOURNAMENT_COMPLETED).toList();

        assertEquals(3, finalResultNotifications.size(), "Organizer AND every linked participant (winner and loser alike) should be notified");
        assertTrue(finalResultNotifications.stream().anyMatch(n -> n.getRecipient() == organizerAccount));
        assertTrue(finalResultNotifications.stream().anyMatch(n -> n.getRecipient() == aliceAccount), "Winner must be notified");
        assertTrue(finalResultNotifications.stream().anyMatch(n -> n.getRecipient() == bobAccount), "Runner-up must be notified too, not just the winner");
        assertTrue(finalResultNotifications.stream().allMatch(n -> n.getMessage().contains("Alice")), "The message must name the real winner");
        assertTrue(finalResultNotifications.stream().allMatch(n -> n.getMessage().contains(tournament.getName())));
    }

    // ===================== helpers =====================

    private List<TournamentMatch> generateBracket(Tournament tournament) {
        List<TournamentMatch> persisted = new ArrayList<>();
        Set<TournamentMatch> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        lenient().when(matches.existsByTournamentId(1L)).thenReturn(false);
        lenient().when(matches.saveAll(any())).thenAnswer(inv -> {
            Iterable<?> batch = inv.getArgument(0);
            for (Object item : batch) {
                TournamentMatch m = (TournamentMatch) item;
                if (seen.add(m)) { // resolveReadyAndByes() re-persists the same match objects more than once
                    if (m.getId() == null) m.setId((long) (persisted.size() + 1));
                    persisted.add(m);
                }
            }
            return inv.getArgument(0);
        });
        when(tournaments.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.generateBracket(1L, organizerAuth(tournament.getOrganizer()));
        return persisted;
    }

    private Notification findFor(List<Notification> notifications, AppUser recipient) {
        return notifications.stream().filter(n -> n.getRecipient() == recipient).findFirst()
                .orElseThrow(() -> new AssertionError("No notification found for " + recipient.getUsername()));
    }

    private AppUser appUser(Long id, String username) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private Participant participant(Long id, String name, AppUser linkedAccount) {
        Participant participant = new Participant();
        participant.setId(id);
        participant.setName(name);
        participant.setAppUser(linkedAccount);
        return participant;
    }

    private Tournament tournament(AppUser organizerAccount, Participant... roster) {
        Tournament tournament = new Tournament();
        tournament.setId(1L);
        tournament.setName("Winter Cup");
        tournament.setFormat(TournamentFormat.ELIMINATION);
        tournament.setStatus(TournamentStatus.REGISTRATION);
        tournament.setStartDate(LocalDate.now());
        tournament.setEndDate(LocalDate.now().plusDays(1));
        tournament.setOrganizer(organizerAccount);
        tournament.setParticipants(new ArrayList<>(List.of(roster)));
        return tournament;
    }

    private TestingAuthenticationToken organizerAuth(AppUser organizerAccount) {
        return new TestingAuthenticationToken(organizerAccount.getUsername(), "password", "ROLE_ORGANIZER");
    }
}
