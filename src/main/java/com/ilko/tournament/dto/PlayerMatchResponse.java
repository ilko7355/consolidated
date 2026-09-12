package com.ilko.tournament.dto;

/**
 * A match seen from one participant's side: {@code score} is always theirs, {@code opponent} is the other side.
 * {@code outcome} is WIN, LOSS, DRAW or BYE once the match is completed, otherwise null.
 */
public record PlayerMatchResponse(Long id, Long tournamentId, String tournamentName, String format, int round, int matchNumber,
                                  String participant, String opponent, Integer score, Integer opponentScore,
                                  String status, String outcome, String groupName) { }
