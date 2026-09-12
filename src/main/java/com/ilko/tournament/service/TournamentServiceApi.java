package com.ilko.tournament.service;

import com.ilko.tournament.dto.*;
import com.ilko.tournament.entity.Tournament;
import org.springframework.security.core.Authentication;
import java.util.List;

public interface TournamentServiceApi {
    TournamentResponse create(CreateTournamentRequest request, Authentication authentication);
    List<TournamentResponse> list();
    Tournament get(Long id);
    TournamentResponse getResponse(Long id);
    TournamentResponse update(Long id, UpdateTournamentRequest request, Authentication authentication);
    void delete(Long id, Authentication authentication);
    ParticipantResponse registerParticipant(Long id, ParticipantRequest request, Authentication authentication);
    ParticipantResponse join(Long id, JoinTournamentRequest request, Authentication authentication);
    void leave(Long id, Authentication authentication);
    List<ParticipantResponse> registered(Long id);
    List<GroupResponse> groups(Long id);
    GroupResponse createGroup(Long id, CreateGroupRequest request, Authentication authentication);
    GroupResponse assignParticipant(Long tournamentId, Long groupId, Long participantId, Authentication authentication);
    void removeParticipant(Long tournamentId, Long groupId, Long participantId, Authentication authentication);
    List<MatchResponse> generateBracket(Long id, Authentication authentication);
    List<MatchResponse> bracket(Long id);
    List<MatchResponse> results(Long id);
    MatchResponse result(Long matchId, MatchResultRequest request, Authentication authentication);
    List<RankingResponse> rankings(Long id);
    TournamentStatisticsResponse statistics(Long id);
    List<PlayerMatchResponse> myMatches(Authentication authentication);
}
