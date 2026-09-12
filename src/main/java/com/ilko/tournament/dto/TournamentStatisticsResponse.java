package com.ilko.tournament.dto;

public record TournamentStatisticsResponse(Long tournamentId, String format, String status, int participants,
                                           int totalMatches, int completedMatches, int remainingMatches, int playedMatches,
                                           int byes, int draws, int totalScore, double averageScorePerMatch, int progressPercent,
                                           String champion, String runnerUp, String topScorer, int topScorerPoints,
                                           String biggestWin, int biggestWinMargin) { }
