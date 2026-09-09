package com.ilko.tournament.dto;

import java.time.LocalDateTime;

/**
 * {@code scheduledTime}: mirrors {@code TournamentMatch.scheduledTime}, which is currently always
 * {@code null} - see that field's Javadoc. The message text already degrades gracefully to
 * "date/time to be announced" (see {@code TournamentService.scheduleSuffix}), so a null value here
 * is expected, not a missing-data bug.
 */
public record NotificationResponse(
        Long id,
        String message,
        String type,
        boolean read,
        LocalDateTime createdAt,
        Long tournamentId,
        String tournamentName,
        Long matchId,
        Integer round,
        Integer matchNumber,
        String opponent,
        LocalDateTime scheduledTime,
        String matchStatus
) { }
