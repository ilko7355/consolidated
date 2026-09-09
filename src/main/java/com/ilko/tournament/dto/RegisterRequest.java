package com.ilko.tournament.dto;

import jakarta.validation.constraints.*;

 public record RegisterRequest(@NotBlank(message = "Username is required") @Size(max = 50, message = "Username must be at most 50 characters") String username,
							   @NotBlank(message = "Email is required") @Email(message = "Email must be valid") @Size(max = 120, message = "Email must be at most 120 characters") String email,
							   @NotBlank(message = "Password is required") @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters") String password,
							   boolean organizer) { }
