package com.ilko.tournament.config;

import com.ilko.tournament.entity.AppUser;
import com.ilko.tournament.enums.Role;
import com.ilko.tournament.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions the very first ADMINISTRATOR account on application startup.
 *
 * <p>Registration ({@code POST /api/auth/register}) deliberately only ever creates ORGANIZER or
 * PARTICIPANT accounts - allowing anyone to self-register as an administrator would be a privilege
 * escalation hole. Without this initializer, however, the ADMINISTRATOR role (already referenced by
 * every {@code @PreAuthorize} check in the app) could never actually be assigned to anyone.</p>
 *
 * <p>Configure the account via environment variables / application.properties:
 * {@code ADMIN_USERNAME}, {@code ADMIN_EMAIL}, {@code ADMIN_PASSWORD}. If any of these are left
 * blank, or an administrator account already exists, this does nothing.</p>
 */
@Component
@Order(10) // after ParticipantSchemaGuard, before DemoDataInitializer
@RequiredArgsConstructor
public class AdminAccountInitializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;

    @Value("${app.admin.username:}") private String adminUsername;
    @Value("${app.admin.email:}") private String adminEmail;
    @Value("${app.admin.password:}") private String adminPassword;

    @Override
    @Transactional
    public void run(String... args) {
        if (users.existsByRole(Role.ADMINISTRATOR)) {
            return; // an administrator already exists, nothing to do
        }
        if (adminUsername.isBlank() || adminEmail.isBlank() || adminPassword.isBlank()) {
            log.info("No ADMINISTRATOR account exists yet. Set ADMIN_USERNAME, ADMIN_EMAIL and " +
                    "ADMIN_PASSWORD environment variables and restart to create one.");
            return;
        }
        if (users.existsByUsername(adminUsername) || users.existsByEmail(adminEmail)) {
            log.warn("Cannot create the configured administrator account: username or email is already taken by an existing user.");
            return;
        }

        AppUser admin = new AppUser();
        admin.setUsername(adminUsername);
        admin.setEmail(adminEmail);
        admin.setPassword(encoder.encode(adminPassword));
        admin.setRole(Role.ADMINISTRATOR);
        users.save(admin);
        log.info("Created initial ADMINISTRATOR account '{}'.", adminUsername);
    }
}
