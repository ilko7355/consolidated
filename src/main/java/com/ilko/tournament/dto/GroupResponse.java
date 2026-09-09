package com.ilko.tournament.dto;

import java.util.List;

public record GroupResponse(Long id, String name, List<ParticipantResponse> participants) { }
