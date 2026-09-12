package com.ilko.tournament.service.impl;

import com.ilko.tournament.dto.PlatformOverviewResponse;
import com.ilko.tournament.dto.UserResponse;
import com.ilko.tournament.entity.AppUser;
import com.ilko.tournament.enums.MatchStatus;
import com.ilko.tournament.enums.Role;
import com.ilko.tournament.enums.TournamentStatus;
import com.ilko.tournament.exception.BusinessException;
import com.ilko.tournament.exception.ResourceNotFoundException;
import com.ilko.tournament.mapper.UserMapper;
import com.ilko.tournament.repository.AppUserRepository;
import com.ilko.tournament.repository.TournamentMatchRepository;
import com.ilko.tournament.repository.TournamentRepository;
import com.ilko.tournament.service.UserAdminServiceApi;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Account administration. Authentication is stateless (HTTP Basic), so a role change or a block takes
 * effect on the affected user's very next request - there is no session that could keep old rights alive.
 */
@Service
@RequiredArgsConstructor
public class UserAdminService implements UserAdminServiceApi {
    private final AppUserRepository users;
    private final TournamentRepository tournaments;
    private final TournamentMatchRepository matches;

    @Transactional(readOnly = true)
    public PlatformOverviewResponse overview() {
        return new PlatformOverviewResponse(users.count(), users.countByRole(Role.ADMINISTRATOR),
                users.countByRole(Role.ORGANIZER), users.countByRole(Role.PARTICIPANT), users.countByEnabledFalse(),
                tournaments.count(), tournaments.countByStatus(TournamentStatus.REGISTRATION),
                tournaments.countByStatus(TournamentStatus.IN_PROGRESS), tournaments.countByStatus(TournamentStatus.COMPLETED),
                matches.countByStatusAndScore1IsNotNull(MatchStatus.COMPLETED));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> users() {
        return users.findAllByOrderByCreatedAtAsc().stream().map(UserMapper::toResponse).toList();
    }

    @Transactional
    public UserResponse changeRole(Long id, Role role, Authentication authentication) {
        AppUser user = find(id);
        requireSomeoneElse(user, authentication, "You cannot change your own role.");
        if (user.getRole() == Role.ADMINISTRATOR && role != Role.ADMINISTRATOR && users.countByRole(Role.ADMINISTRATOR) <= 1) {
            throw new BusinessException("The platform must keep at least one administrator.");
        }
        user.setRole(role);
        return UserMapper.toResponse(users.save(user));
    }

    @Transactional
    public UserResponse changeStatus(Long id, boolean enabled, Authentication authentication) {
        AppUser user = find(id);
        requireSomeoneElse(user, authentication, "You cannot block or unblock your own account.");
        user.setEnabled(enabled);
        return UserMapper.toResponse(users.save(user));
    }

    private AppUser find(Long id) {
        return users.findById(id).orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    /** Stops administrators from locking themselves out. */
    private void requireSomeoneElse(AppUser user, Authentication authentication, String message) {
        if (user.getUsername().equals(authentication.getName())) {
            throw new BusinessException(message);
        }
    }
}
