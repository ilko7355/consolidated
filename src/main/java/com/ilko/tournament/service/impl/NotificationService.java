package com.ilko.tournament.service.impl;

import com.ilko.tournament.dto.NotificationResponse;
import com.ilko.tournament.entity.Notification;
import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.exception.ResourceNotFoundException;
import com.ilko.tournament.repository.NotificationRepository;
import com.ilko.tournament.service.NotificationServiceApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service @RequiredArgsConstructor
public class NotificationService implements NotificationServiceApi {
    private final NotificationRepository notifications;

    @Transactional(readOnly = true)
    public List<NotificationResponse> all(String username, boolean unread) {
        var list = unread
                ? notifications.findByRecipientUsernameAndReadStatusFalseOrderByCreatedAtDesc(username)
                : notifications.findByRecipientUsernameOrderByCreatedAtDesc(username);
        return list.stream().map(this::response).toList();
    }

    @Transactional
    public NotificationResponse markRead(Long id, String username) {
        Notification notification = notifications.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + id));
        if (!notification.getRecipient().getUsername().equals(username))
            throw new com.ilko.tournament.exception.UnauthorizedOperationException("Notification does not belong to this user");
        notification.setReadStatus(true);
        return response(notifications.save(notification));
    }

    private NotificationResponse response(Notification n) {
        TournamentMatch match = n.getMatch();
        Tournament tournament = n.getTournament();

        Long tournamentId = tournament != null ? tournament.getId() : (match != null ? match.getTournament().getId() : null);
        String tournamentName = tournament != null ? tournament.getName() : (match != null ? match.getTournament().getName() : null);

        return new NotificationResponse(
                n.getId(),
                n.getMessage(),
                n.getType().name(),
                n.isReadStatus(),
                n.getCreatedAt(),
                tournamentId,
                tournamentName,
                match != null ? match.getId() : null,
                match != null ? match.getRoundNumber() : null,
                match != null ? match.getMatchNumber() : null,
                match != null ? opponentFor(n, match) : null,
                match != null ? match.getScheduledTime() : null,
                match != null ? match.getStatus().name() : null
        );
    }

    /** Determines the opponent's name from the recipient's point of view, if the recipient is one of the two match participants. */
    private String opponentFor(Notification n, TournamentMatch match) {
        Participant p1 = match.getParticipant1();
        Participant p2 = match.getParticipant2();
        boolean recipientIsP1 = p1 != null && p1.getAppUser() != null && p1.getAppUser().getId().equals(n.getRecipient().getId());
        boolean recipientIsP2 = p2 != null && p2.getAppUser() != null && p2.getAppUser().getId().equals(n.getRecipient().getId());
        if (recipientIsP1) return p2 != null ? p2.getName() : "TBD";
        if (recipientIsP2) return p1 != null ? p1.getName() : "TBD";
        // Recipient is the organizer (or unrelated to either side) - show both names.
        return (p1 != null ? p1.getName() : "TBD") + " vs " + (p2 != null ? p2.getName() : "TBD");
    }
}
