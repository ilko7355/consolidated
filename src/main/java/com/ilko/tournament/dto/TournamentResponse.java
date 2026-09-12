package com.ilko.tournament.dto;

import java.time.LocalDate;

public record TournamentResponse(Long id, String name, String description, String format, String status, LocalDate startDate, LocalDate endDate, String organizer, int participantCount, boolean grandFinalReset) { }
