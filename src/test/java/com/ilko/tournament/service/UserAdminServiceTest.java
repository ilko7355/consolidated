package com.ilko.tournament.service;

import com.ilko.tournament.entity.AppUser;
import com.ilko.tournament.enums.Role;
import com.ilko.tournament.exception.BusinessException;
import com.ilko.tournament.repository.AppUserRepository;
import com.ilko.tournament.repository.TournamentMatchRepository;
import com.ilko.tournament.repository.TournamentRepository;
import com.ilko.tournament.service.impl.UserAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {
    @Mock AppUserRepository users;
    @Mock TournamentRepository tournaments;
    @Mock TournamentMatchRepository matches;
    @InjectMocks UserAdminService service;

    private final TestingAuthenticationToken admin = new TestingAuthenticationToken("admin", "password", "ROLE_ADMINISTRATOR");

    @Test
    void administratorPromotesAParticipantToOrganizer() {
        AppUser user = user(5L, "georgi", Role.PARTICIPANT);
        when(users.findById(5L)).thenReturn(Optional.of(user));
        when(users.save(user)).thenReturn(user);

        assertEquals("ORGANIZER", service.changeRole(5L, Role.ORGANIZER, admin).role());
    }

    @Test
    void administratorCannotChangeTheirOwnRoleOrBlockThemselves() {
        AppUser self = user(1L, "admin", Role.ADMINISTRATOR);
        when(users.findById(1L)).thenReturn(Optional.of(self));

        assertThrows(BusinessException.class, () -> service.changeRole(1L, Role.PARTICIPANT, admin));
        assertThrows(BusinessException.class, () -> service.changeStatus(1L, false, admin));
        verify(users, never()).save(any());
    }

    @Test
    void theLastAdministratorCannotBeDemoted() {
        AppUser otherAdmin = user(2L, "second-admin", Role.ADMINISTRATOR);
        when(users.findById(2L)).thenReturn(Optional.of(otherAdmin));
        when(users.countByRole(Role.ADMINISTRATOR)).thenReturn(1L);

        assertThrows(BusinessException.class, () -> service.changeRole(2L, Role.ORGANIZER, admin));
        assertEquals(Role.ADMINISTRATOR, otherAdmin.getRole());
    }

    @Test
    void blockingAnAccountDisablesIt() {
        AppUser user = user(7L, "elena", Role.PARTICIPANT);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(users.save(user)).thenReturn(user);

        assertFalse(service.changeStatus(7L, false, admin).enabled());
        assertFalse(user.isEnabled());
    }

    private AppUser user(Long id, String username, Role role) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setRole(role);
        return user;
    }
}
