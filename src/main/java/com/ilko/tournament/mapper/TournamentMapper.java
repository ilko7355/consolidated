package com.ilko.tournament.mapper;

import com.ilko.tournament.dto.MatchResponse;
import com.ilko.tournament.dto.ParticipantResponse;
import com.ilko.tournament.dto.PlayerMatchResponse;
import com.ilko.tournament.dto.TournamentListProjection;
import com.ilko.tournament.dto.TournamentResponse;
import com.ilko.tournament.entity.Participant;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.MatchStatus;

public final class TournamentMapper {
    private TournamentMapper() { }

    public static TournamentResponse toResponse(Tournament tournament) {
        return new TournamentResponse(tournament.getId(), tournament.getName(), tournament.getDescription(),
                tournament.getFormat().name(), tournament.getStatus().name(), tournament.getStartDate(),
                tournament.getEndDate(), tournament.getOrganizer().getUsername(), tournament.getParticipants().size(),
                tournament.isGrandFinalReset());
    }

    public static TournamentResponse toResponse(TournamentListProjection projection) {
        return new TournamentResponse(projection.id(), projection.name(), projection.description(),
                projection.format().name(), projection.status().name(), projection.startDate(),
                projection.endDate(), projection.organizer(), Math.toIntExact(projection.participantCount()),
                projection.grandFinalReset());
    }

    public static ParticipantResponse toResponse(Participant participant) {
        return new ParticipantResponse(participant.getId(), participant.getName(), participant.getStatus().name(),
                participant.getAppUser() == null ? null : participant.getAppUser().getUsername());
    }

    public static MatchResponse toResponse(TournamentMatch match) {
        return new MatchResponse(match.getId(), match.getRoundNumber(), match.getMatchNumber(),
                id(match.getParticipant1()), name(match.getParticipant1()), id(match.getParticipant2()),
                name(match.getParticipant2()), match.getScore1(), match.getScore2(), id(match.getWinner()),
                match.getStatus().name(), match.getScheduledTime(), match.getGroup() == null ? null : match.getGroup().getName(),
                match.getBracket() == null ? null : match.getBracket().name());
    }

    /** Maps a match from the point of view of the participant linked to {@code username}. */
    public static PlayerMatchResponse toPlayerResponse(TournamentMatch match, String username) {
        boolean firstSide = isAccount(match.getParticipant1(), username);
        Participant self = firstSide ? match.getParticipant1() : match.getParticipant2();
        Participant opponent = firstSide ? match.getParticipant2() : match.getParticipant1();
        Integer score = firstSide ? match.getScore1() : match.getScore2();
        Integer opponentScore = firstSide ? match.getScore2() : match.getScore1();
        return new PlayerMatchResponse(match.getId(), match.getTournament().getId(), match.getTournament().getName(),
                match.getTournament().getFormat().name(), match.getRoundNumber(), match.getMatchNumber(), name(self),
                name(opponent), score, opponentScore, match.getStatus().name(), outcome(match, self, score),
                match.getGroup() == null ? null : match.getGroup().getName());
    }

    private static String outcome(TournamentMatch match, Participant self, Integer score) {
        if (match.getStatus() != MatchStatus.COMPLETED) return null;
        if (score == null) return "BYE";
        if (match.getWinner() == null) return "DRAW";
        return match.getWinner().getId().equals(self.getId()) ? "WIN" : "LOSS";
    }

    private static boolean isAccount(Participant participant, String username) {
        return participant != null && participant.getAppUser() != null && username.equals(participant.getAppUser().getUsername());
    }

    private static Long id(Participant participant) { return participant == null ? null : participant.getId(); }
    private static String name(Participant participant) { return participant == null ? null : participant.getName(); }
}
