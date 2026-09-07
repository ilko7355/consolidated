package com.ilko.tournament.mapper;

import com.ilko.tournament.dto.MatchResponse;
import com.ilko.tournament.dto.ParticipantResponse;
import com.ilko.tournament.dto.TournamentResponse;
import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;

public final class TournamentMapper {
    private TournamentMapper() { }

    public static TournamentResponse toResponse(Tournament tournament) {
        return new TournamentResponse(tournament.getId(), tournament.getName(), tournament.getDescription(),
                tournament.getFormat().name(), tournament.getStatus().name(), tournament.getStartDate(),
                tournament.getEndDate(), tournament.getOrganizer().getUsername(), tournament.getParticipants().size());
    }

    public static ParticipantResponse toResponse(Participant participant) {
        return new ParticipantResponse(participant.getId(), participant.getName(), participant.getStatus().name(),
                participant.getAppUser() == null ? null : participant.getAppUser().getUsername());
    }

    public static MatchResponse toResponse(TournamentMatch match) {
        return new MatchResponse(match.getId(), match.getRoundNumber(), match.getMatchNumber(),
                id(match.getParticipant1()), name(match.getParticipant1()), id(match.getParticipant2()),
                name(match.getParticipant2()), match.getScore1(), match.getScore2(), id(match.getWinner()),
                match.getStatus().name(), match.getScheduledTime(), match.getGroup() == null ? null : match.getGroup().getName());
    }

    private static Long id(Participant participant) { return participant == null ? null : participant.getId(); }
    private static String name(Participant participant) { return participant == null ? null : participant.getName(); }
}