package com.ilko.tournament.dto;

import com.ilko.tournament.enums.TournamentFormat;
import com.ilko.tournament.enums.TournamentStatus;
import java.time.LocalDate;

/**
 * JPQL constructor-expression target for {@code TournamentRepository.findAllForList()}.
 * Carries exactly the fields {@link TournamentResponse} needs for the tournament list view.
 * {@code participantCount} is computed by the database with {@code COUNT(p)} - the query that
 * populates this projection never loads {@code Participant} entities into memory.
 */
public record TournamentListProjection(Long id, String name, String description, TournamentFormat format,
        TournamentStatus status, LocalDate startDate, LocalDate endDate, String organizer, long participantCount,
        boolean grandFinalReset) { }
