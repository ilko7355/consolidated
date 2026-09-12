package com.ilko.tournament.config;

import com.ilko.tournament.dto.*;
import com.ilko.tournament.entity.AppUser;
import com.ilko.tournament.enums.Role;
import com.ilko.tournament.enums.TournamentFormat;
import com.ilko.tournament.repository.AppUserRepository;
import com.ilko.tournament.repository.TournamentRepository;
import com.ilko.tournament.service.TournamentServiceApi;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Fills an empty database with demo accounts and one tournament in every lifecycle stage (registration,
 * in progress, completed, plus a group stage), so the platform can be presented without manual data entry.
 *
 * <p>Enabled only with {@code app.demo-data=true}, and it never touches a database that already contains
 * tournaments. Everything is created through {@link TournamentServiceApi} - the same validation, bracket
 * algorithm and notifications as real organizer actions - instead of inserting rows directly.</p>
 */
@Component
@Order(20)
@ConditionalOnProperty(name = "app.demo-data", havingValue = "true")
@RequiredArgsConstructor
public class DemoDataInitializer implements CommandLineRunner {
    static final String DEMO_PASSWORD = "Demo12345";
    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final AppUserRepository users;
    private final TournamentRepository tournaments;
    private final PasswordEncoder encoder;
    private final TournamentServiceApi service;
    private final Random random = new Random(2026); // fixed seed: the same demo scores on every fresh database

    @Override
    public void run(String... args) {
        if (tournaments.count() > 0) {
            log.info("Demo data skipped: the database already contains tournaments.");
            return;
        }
        if (!users.existsByRole(Role.ADMINISTRATOR)) {
            account("admin", Role.ADMINISTRATOR);
        }
        account("organizer", Role.ORGANIZER);
        account("coach.maria", Role.ORGANIZER);
        for (String username : List.of("georgi", "elena", "nikola", "viktoria", "stefan", "kalina", "martin", "yoana",
                "petar", "desislava", "ivan", "radostina")) {
            account(username, Role.PARTICIPANT);
        }

        Authentication organizer = organizer("organizer");
        Authentication maria = organizer("coach.maria");
        LocalDate today = LocalDate.now();

        Long chess = tournament("Stara Zagora Rapid Chess Open", "Knockout rapid chess for PGKNMA students. Eight seeded players, one champion.",
                TournamentFormat.ELIMINATION, today.minusDays(10), today.minusDays(8), organizer);
        register(chess, organizer, "Georgi Petrov:georgi", "Elena Ivanova:elena", "Nikola Dimitrov:nikola", "Viktoria Koleva:viktoria",
                "Stefan Georgiev:stefan", "Kalina Todorova:kalina", "Martin Stoyanov:martin", "Yoana Marinova:yoana");
        service.generateBracket(chess, organizer);
        play(chess, organizer, Integer.MAX_VALUE, -1);

        Long esports = tournament("PGKNMA Esports Cup", "Rocket League 3v3 knockout. Six teams, so the two top seeds start with a BYE.",
                TournamentFormat.ELIMINATION, today.minusDays(1), today.plusDays(4), organizer);
        register(esports, organizer, "Nova Esports:petar", "Thracian Wolves:desislava", "Zagora Rockets:ivan", "Byte Knights:radostina",
                "Pixel Storm:", "Lime Hawks:georgi");
        service.generateBracket(esports, organizer);
        play(esports, organizer, 3, -1);

        Long tableTennis = tournament("School Table Tennis League", "Two groups of four - everyone plays everyone in their group.",
                TournamentFormat.GROUPS, today.minusDays(3), today.plusDays(10), maria);
        List<ParticipantResponse> players = register(tableTennis, maria, "Georgi Petrov:georgi", "Elena Ivanova:elena", "Nikola Dimitrov:nikola",
                "Viktoria Koleva:viktoria", "Stefan Georgiev:stefan", "Kalina Todorova:kalina", "Martin Stoyanov:martin", "Yoana Marinova:yoana");
        Long groupA = service.createGroup(tableTennis, new CreateGroupRequest("Group A"), maria).id();
        Long groupB = service.createGroup(tableTennis, new CreateGroupRequest("Group B"), maria).id();
        for (int i = 0; i < players.size(); i++) {
            service.assignParticipant(tableTennis, i < 4 ? groupA : groupB, players.get(i).id(), maria);
        }
        service.generateBracket(tableTennis, maria);
        play(tableTennis, maria, 7, 2);

        Long blitz = tournament("Winter Blitz Chess Cup",
                "Double elimination: one loss is not the end - beaten players drop into the losers bracket and can still "
                + "reach the grand final, which is decided by a rematch if the losers-bracket finalist wins it.",
                TournamentFormat.DOUBLE_ELIMINATION, today.minusDays(2), today.plusDays(2), organizer, true);
        register(blitz, organizer, "Georgi Petrov:georgi", "Kalina Todorova:kalina", "Martin Stoyanov:martin",
                "Elena Ivanova:elena", "Ivan Kirilov:ivan", "Yoana Marinova:yoana");
        service.generateBracket(blitz, organizer);
        play(blitz, organizer, 5, -1);

        Long futsal = tournament("Autumn Futsal Cup", "Five-a-side futsal. Registration is open - participants can join from the tournament page.",
                TournamentFormat.ELIMINATION, today.plusDays(14), today.plusDays(16), maria);
        register(futsal, maria, "FC Chaika:", "Beroe Juniors:", "Vereya United:nikola");

        log.info("Demo data created: 5 tournaments, {} accounts. Sign in as admin, organizer, coach.maria or georgi with password {}.",
                users.count(), DEMO_PASSWORD);
    }

    private void account(String username, Role role) {
        String email = username + "@example.com";
        if (users.existsByUsername(username) || users.existsByEmail(email)) {
            return;
        }
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(encoder.encode(DEMO_PASSWORD));
        user.setRole(role);
        users.save(user);
    }

    private Authentication organizer(String username) {
        return new UsernamePasswordAuthenticationToken(username, null, AuthorityUtils.createAuthorityList("ROLE_ORGANIZER"));
    }

    private Long tournament(String name, String description, TournamentFormat format, LocalDate start, LocalDate end, Authentication owner) {
        return tournament(name, description, format, start, end, owner, false);
    }

    private Long tournament(String name, String description, TournamentFormat format, LocalDate start, LocalDate end,
                            Authentication owner, boolean grandFinalReset) {
        return service.create(new CreateTournamentRequest(name, description, format, start, end, grandFinalReset), owner).id();
    }

    /** Each entry is "Display name:account" - the account part may be empty for participants without a login. */
    private List<ParticipantResponse> register(Long tournamentId, Authentication owner, String... entries) {
        List<ParticipantResponse> registered = new ArrayList<>();
        for (String entry : entries) {
            String[] parts = entry.split(":", 2);
            String username = parts[1].isBlank() ? null : parts[1];
            registered.add(service.registerParticipant(tournamentId, new ParticipantRequest(parts[0], username), owner));
        }
        return registered;
    }

    /** Records results for up to {@code limit} READY matches, earliest round first; match number {@code drawAt} ends level. */
    private void play(Long tournamentId, Authentication owner, int limit, int drawAt) {
        for (int played = 0; played < limit; played++) {
            Optional<MatchResponse> next = service.bracket(tournamentId).stream().filter(m -> "READY".equals(m.status())).findFirst();
            if (next.isEmpty()) {
                return;
            }
            int winning = 2 + random.nextInt(4);
            int losing = random.nextInt(winning);
            MatchResultRequest score = played == drawAt ? new MatchResultRequest(winning, winning)
                    : random.nextBoolean() ? new MatchResultRequest(winning, losing) : new MatchResultRequest(losing, winning);
            service.result(next.get().id(), score, owner);
        }
    }
}
