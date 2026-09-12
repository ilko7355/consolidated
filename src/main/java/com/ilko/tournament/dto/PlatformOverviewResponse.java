package com.ilko.tournament.dto;

public record PlatformOverviewResponse(long users, long administrators, long organizers, long participants, long blockedUsers,
                                       long tournaments, long registrationTournaments, long activeTournaments,
                                       long completedTournaments, long matchesPlayed) { }
