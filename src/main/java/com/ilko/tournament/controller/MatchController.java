package com.ilko.tournament.controller;

import com.ilko.tournament.dto.*;
import com.ilko.tournament.service.TournamentServiceApi;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

@RestController @RequestMapping("/api/matches") @RequiredArgsConstructor
public class MatchController {
    private final TournamentServiceApi service;
    @PostMapping("/{id}/result") @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ORGANIZER', 'ADMINISTRATOR')") public MatchResponse result(@PathVariable Long id, @Valid @RequestBody MatchResultRequest request, Authentication authentication) { return service.result(id, request, authentication); }
}
