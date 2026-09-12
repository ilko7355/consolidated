package com.ilko.tournament.controller;

import com.ilko.tournament.dto.*;
import com.ilko.tournament.service.UserAdminServiceApi;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/admin") @RequiredArgsConstructor @PreAuthorize("hasRole('ADMINISTRATOR')")
public class AdminController {
    private final UserAdminServiceApi service;
    @GetMapping("/overview") public PlatformOverviewResponse overview() { return service.overview(); }
    @GetMapping("/users") public List<UserResponse> users() { return service.users(); }
    @PutMapping("/users/{id}/role") public UserResponse changeRole(@PathVariable Long id, @Valid @RequestBody UpdateUserRoleRequest request, Authentication authentication) { return service.changeRole(id, request.role(), authentication); }
    @PutMapping("/users/{id}/status") public UserResponse changeStatus(@PathVariable Long id, @Valid @RequestBody UpdateUserStatusRequest request, Authentication authentication) { return service.changeStatus(id, request.enabled(), authentication); }
}
