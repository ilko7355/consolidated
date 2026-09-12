package com.ilko.tournament.dto;

import com.ilko.tournament.enums.Role;
import jakarta.validation.constraints.NotNull;

public record UpdateUserRoleRequest(@NotNull(message = "Role is required") Role role) { }
