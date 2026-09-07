package com.ilko.tournament.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateGroupRequest(
        @NotBlank(message = "Group name is required")
        @Size(max = 50, message = "Group name must be at most 50 characters")
        String name) { }
