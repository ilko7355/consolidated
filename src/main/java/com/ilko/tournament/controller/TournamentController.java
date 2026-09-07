package com.ilko.tournament.controller;

import com.ilko.tournament.dto.*;
import com.ilko.tournament.service.TournamentServiceApi;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.List;

@RestController @RequestMapping("/api/tournaments") @RequiredArgsConstructor
public class TournamentController {
    private final TournamentServiceApi service;
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMINISTRATOR')") public TournamentResponse create(@Valid @RequestBody CreateTournamentRequest request, Authentication authentication) { return service.create(request, authentication); }
    @GetMapping public List<TournamentResponse> list() { return service.list(); }
    @GetMapping("/{id}") public TournamentResponse get(@PathVariable Long id) { return service.getResponse(id); }
    @PutMapping("/{id}") @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMINISTRATOR')") public TournamentResponse update(@PathVariable Long id, @Valid @RequestBody UpdateTournamentRequest request, Authentication authentication) { return service.update(id, request, authentication); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMINISTRATOR')") public void delete(@PathVariable Long id, Authentication authentication) { service.delete(id, authentication); }
    @PostMapping("/{id}/participants") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMINISTRATOR')") public ParticipantResponse register(@PathVariable Long id, @Valid @RequestBody ParticipantRequest request, Authentication authentication) { return service.registerParticipant(id, request, authentication); }
    @GetMapping("/{id}/participants") public List<ParticipantResponse> participants(@PathVariable Long id) { return service.registered(id); }
    @GetMapping("/{id}/groups") public List<GroupResponse> groups(@PathVariable Long id) { return service.groups(id); }
    @PostMapping("/{id}/groups") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMINISTRATOR')") public GroupResponse createGroup(@PathVariable Long id, @Valid @RequestBody CreateGroupRequest request, Authentication authentication) { return service.createGroup(id, request, authentication); }
    @PutMapping("/{id}/groups/{groupId}/participants/{participantId}") @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMINISTRATOR')") public GroupResponse assignParticipant(@PathVariable Long id, @PathVariable Long groupId, @PathVariable Long participantId, Authentication authentication) { return service.assignParticipant(id, groupId, participantId, authentication); }
    @DeleteMapping("/{id}/groups/{groupId}/participants/{participantId}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMINISTRATOR')") public void removeParticipant(@PathVariable Long id, @PathVariable Long groupId, @PathVariable Long participantId, Authentication authentication) { service.removeParticipant(id, groupId, participantId, authentication); }
    @PostMapping("/{id}/generate-bracket") @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMINISTRATOR')") public List<MatchResponse> generate(@PathVariable Long id, Authentication authentication) { return service.generateBracket(id, authentication); }
    @GetMapping("/{id}/bracket") public List<MatchResponse> bracket(@PathVariable Long id) { return service.bracket(id); }
    @GetMapping("/{id}/rankings") public List<RankingResponse> rankings(@PathVariable Long id) { return service.rankings(id); }
    @GetMapping("/{id}/results") public List<MatchResponse> results(@PathVariable Long id) { return service.bracket(id).stream().filter(m -> "COMPLETED".equals(m.status())).toList(); }
}
