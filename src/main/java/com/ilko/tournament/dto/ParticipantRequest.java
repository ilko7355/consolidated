package com.ilko.tournament.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

 public record ParticipantRequest(
        @NotBlank(message = "Participant name is required") @Size(max = 100, message = "Participant name must be at most 100 characters") String name,
        @Size(max = 50, message = "Username must be at most 50 characters") String username
) { }
