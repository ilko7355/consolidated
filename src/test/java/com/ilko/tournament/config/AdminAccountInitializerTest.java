package com.ilko.tournament.config;

import com.ilko.tournament.entity.AppUser;
import com.ilko.tournament.enums.Role;
import com.ilko.tournament.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAccountInitializerTest {
    @Mock AppUserRepository users;
    @Mock PasswordEncoder encoder;
    @InjectMocks AdminAccountInitializer initializer;

    private void configure(String username, String email, String password) {
        ReflectionTestUtils.setField(initializer, "adminUsername", username);
        ReflectionTestUtils.setField(initializer, "adminEmail", email);
        ReflectionTestUtils.setField(initializer, "adminPassword", password);
    }

    @Test
    void createsTheAdministratorOnAFreshDatabaseWithTheCorrectRoleAndHashedPassword() throws Exception {
        configure("admin", "admin@example.com", "super-secret-1");
        when(users.existsByRole(Role.ADMINISTRATOR)).thenReturn(false);
        when(users.existsByUsername("admin")).thenReturn(false);
        when(users.existsByEmail("admin@example.com")).thenReturn(false);
        when(encoder.encode("super-secret-1")).thenReturn("bcrypt-hash-of-secret");
        when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        initializer.run();

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(captor.capture());
        AppUser created = captor.getValue();
        assertEquals("admin", created.getUsername());
        assertEquals("admin@example.com", created.getEmail());
        assertEquals(Role.ADMINISTRATOR, created.getRole());
        assertEquals("bcrypt-hash-of-secret", created.getPassword());
        assertNotEquals("super-secret-1", created.getPassword(), "The raw password must never be persisted");
    }

    @Test
    void runningTheBootstrapAgainDoesNotCreateADuplicateAdministrator() throws Exception {
        configure("admin", "admin@example.com", "super-secret-1");
        when(users.existsByRole(Role.ADMINISTRATOR)).thenReturn(true); // an admin already exists from a previous run

        initializer.run();

        verify(users, never()).save(any());
        verifyNoInteractions(encoder);
    }

    @Test
    void doesNothingWhenNoBootstrapCredentialsAreConfigured() throws Exception {
        configure("", "", ""); // matches the default blank values in application.properties
        when(users.existsByRole(Role.ADMINISTRATOR)).thenReturn(false);

        initializer.run();

        verify(users, never()).save(any());
    }

    @Test
    void doesNotOverwriteOrDuplicateWhenTheConfiguredUsernameOrEmailIsAlreadyTakenBySomeoneElse() throws Exception {
        configure("admin", "admin@example.com", "super-secret-1");
        when(users.existsByRole(Role.ADMINISTRATOR)).thenReturn(false);
        when(users.existsByUsername("admin")).thenReturn(true); // some non-admin user already has this username

        initializer.run();

        verify(users, never()).save(any());
    }

    @Test
    void isSafeToRunMultipleTimesInARowAndOnlyEverCreatesOneAccount() throws Exception {
        configure("admin", "admin@example.com", "super-secret-1");
        when(encoder.encode(any())).thenReturn("bcrypt-hash");
        when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        // First run: no admin exists yet.
        when(users.existsByRole(Role.ADMINISTRATOR)).thenReturn(false);
        initializer.run();
        verify(users, times(1)).save(any());

        // Second run (e.g. app restart): an admin now exists, so nothing should happen.
        when(users.existsByRole(Role.ADMINISTRATOR)).thenReturn(true);
        initializer.run();
        verify(users, times(1)).save(any()); // still only ever called once in total
    }
}
