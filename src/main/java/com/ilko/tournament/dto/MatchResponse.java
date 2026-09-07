package com.ilko.tournament.dto;

import java.time.LocalDateTime;

public record MatchResponse(Long id, int round, int matchNumber, Long participant1Id, String participant1, Long participant2Id, String participant2, Integer score1, Integer score2, Long winnerId, String status, LocalDateTime scheduledTime, String groupName) { }
