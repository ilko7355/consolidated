package com.ilko.tournament.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

 public record UpdateTournamentRequest(@NotBlank(message = "Tournament name is required") @Size(max = 150, message = "Tournament name must be at most 150 characters") String name,
									   @Size(max = 2000, message = "Description must be at most 2000 characters") String description,
									   @NotNull(message = "Start date is required") @FutureOrPresent(message = "Start date cannot be in the past") LocalDate startDate,
									   @NotNull(message = "End date is required") LocalDate endDate) { }
