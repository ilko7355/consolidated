package com.ilko.tournament.dto;

public record RankingResponse(Long participantId, String participant, int matchesPlayed, int wins, int losses, int scoreDifference, int points, int placement) { }
