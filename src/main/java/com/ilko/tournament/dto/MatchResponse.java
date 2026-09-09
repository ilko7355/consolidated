package com.ilko.tournament.dto;

import java.time.LocalDateTime;

/**
 * {@code scheduledTime}: reserved for a possible future explicit-scheduling feature. There is
 * currently no organizer/admin action that sets it, so it is always {@code null}. Upcoming-match
 * notifications do not depend on it - they fire when a match becomes {@code READY}.
 */
public record MatchResponse(Long id, int round, int matchNumber, Long participant1Id, String participant1, Long participant2Id, String participant2, Integer score1, Integer score2, Long winnerId, String status, LocalDateTime scheduledTime, String groupName) { }
