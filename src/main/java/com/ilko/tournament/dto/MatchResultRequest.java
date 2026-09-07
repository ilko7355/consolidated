package com.ilko.tournament.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

 public record MatchResultRequest(@NotNull(message = "Participant 1 score is required") @Min(value = 0, message = "Score cannot be negative") Integer score1,
								  @NotNull(message = "Participant 2 score is required") @Min(value = 0, message = "Score cannot be negative") Integer score2) { }
