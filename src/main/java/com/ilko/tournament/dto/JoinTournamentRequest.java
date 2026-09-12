package com.ilko.tournament.dto;

import jakarta.validation.constraints.Size;

/** Optional display name (for example a team name); the account's username is used when it is blank. */
public record JoinTournamentRequest(@Size(max = 100, message = "Participant name must be at most 100 characters") String name) { }
