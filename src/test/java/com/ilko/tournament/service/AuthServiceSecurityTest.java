package com.ilko.tournament.service;

import com.ilko.tournament.dto.AuthResponse;
import com.ilko.tournament.dto.LoginRequest;
import com.ilko.tournament.dto.RegisterRequest;
import com.ilko.tournament.entity.AppUser;
import com.ilko.tournament.enums.Role;
import com.ilko.tournament.repository.AppUserRepository;
import com.ilko.tournament.service.impl.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceSecurityTest {
    @Mock AppUserRepository users;
    @Mock PasswordEncoder encoder;
    @Mock AuthenticationManager authenticationManager;
    @InjectMocks AuthService service;

    @Test
    void registrationStoresOnlyEncodedPassword() {
        when(users.existsByUsername("organizer")).thenReturn(false);
        when(users.existsByEmail("organizer@example.com")).thenReturn(false);
        when(encoder.encode("plain-password")).thenReturn("bcrypt-hash");
        when(users.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.register(new RegisterRequest("organizer", "organizer@example.com", "plain-password", true));

        var captor = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(captor.capture());
        assertEquals("bcrypt-hash", captor.getValue().getPassword());
        assertNotEquals("plain-password", captor.getValue().getPassword());
        assertEquals(Role.ORGANIZER, captor.getValue().getRole());
    }

    @Test
    void participantUsersAreAssignedTheParticipantRole() {
        when(users.existsByUsername("player")).thenReturn(false);
        when(users.existsByEmail("player@example.com")).thenReturn(false);
        when(encoder.encode("plain-password")).thenReturn("bcrypt-hash");
        when(users.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.register(new RegisterRequest("player", "player@example.com", "plain-password", false));

        var captor = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(captor.capture());
        assertEquals(Role.PARTICIPANT, captor.getValue().getRole());
    }

    @Test
    void successfulLoginDelegatesCredentialValidation() {
        AppUser user = new AppUser();
        user.setUsername("user");
        user.setRole(Role.PARTICIPANT);
        when(users.findByUsername("user")).thenReturn(Optional.of(user));

        AuthResponse response = service.login(new LoginRequest("user", "password"));

        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
        assertEquals("user", response.username());
        assertEquals("PARTICIPANT", response.role());
    }

    @Test
    void invalidLoginIsRejected() {
        doThrow(new BadCredentialsException("invalid credentials"))
                .when(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));

        assertThrows(BadCredentialsException.class, () -> service.login(new LoginRequest("user", "wrong")));
        verifyNoInteractions(users);
    }

    @Test
    void publicRegistrationCanNeverProduceAnAdministratorAccount() {
        // RegisterRequest only carries a boolean "organizer" flag (not a free-text role), so there is no
        // request shape that can ask for ADMINISTRATOR - this test proves both possible values of that
        // flag are exhaustive and neither one ever results in an ADMINISTRATOR account.
        when(users.existsByUsername(any())).thenReturn(false);
        when(users.existsByEmail(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("bcrypt-hash");
        when(users.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.register(new RegisterRequest("orgUser", "org@example.com", "plain-password", true));
        service.register(new RegisterRequest("playerUser", "player@example.com", "plain-password", false));

        var captor = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(users, times(2)).save(captor.capture());
        assertTrue(captor.getAllValues().stream().noneMatch(u -> u.getRole() == Role.ADMINISTRATOR),
                "No combination of registration input can ever create an administrator");
    }
}