package com.ilko.tournament.service.impl;

import com.ilko.tournament.dto.*;
import com.ilko.tournament.entity.AppUser;
import com.ilko.tournament.enums.Role;
import com.ilko.tournament.exception.ConflictException;
import com.ilko.tournament.exception.BusinessException;
import com.ilko.tournament.repository.AppUserRepository;
import com.ilko.tournament.service.AuthServiceApi;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class AuthService implements AuthServiceApi {
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AuthenticationManager authenticationManager;

    @Transactional public AuthResponse register(RegisterRequest request) {
        if (users.existsByUsername(request.username())) throw new ConflictException("Username is already registered");
        if (users.existsByEmail(request.email())) throw new ConflictException("Email is already registered");
        AppUser user = new AppUser(); user.setUsername(request.username()); user.setEmail(request.email()); user.setPassword(encoder.encode(request.password())); user.setRole(request.organizer() ? Role.ORGANIZER : Role.PARTICIPANT); users.save(user);
        return new AuthResponse(user.getUsername(), user.getRole().name(), "Registration successful. Use HTTP Basic authentication for protected endpoints.");
    }
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        AppUser user = users.findByUsername(request.username()).orElseThrow(() -> new BusinessException("User not found"));
        return new AuthResponse(user.getUsername(), user.getRole().name(), "Authentication successful");
    }
}
