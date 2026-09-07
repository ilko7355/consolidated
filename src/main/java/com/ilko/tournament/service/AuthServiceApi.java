package com.ilko.tournament.service;

import com.ilko.tournament.dto.AuthResponse;
import com.ilko.tournament.dto.LoginRequest;
import com.ilko.tournament.dto.RegisterRequest;

public interface AuthServiceApi {
    AuthResponse register(RegisterRequest request);
    AuthResponse login(LoginRequest request);
}