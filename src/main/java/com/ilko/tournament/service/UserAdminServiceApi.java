package com.ilko.tournament.service;

import com.ilko.tournament.dto.PlatformOverviewResponse;
import com.ilko.tournament.dto.UserResponse;
import com.ilko.tournament.enums.Role;
import org.springframework.security.core.Authentication;
import java.util.List;

public interface UserAdminServiceApi {
    PlatformOverviewResponse overview();
    List<UserResponse> users();
    UserResponse changeRole(Long id, Role role, Authentication authentication);
    UserResponse changeStatus(Long id, boolean enabled, Authentication authentication);
}
