package com.ilko.tournament.dto;

import java.time.LocalDateTime;

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
