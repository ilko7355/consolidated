package com.ilko.tournament.entity;

import com.ilko.tournament.dto.ParticipantResponse;
import com.ilko.tournament.enums.Role;
import com.ilko.tournament.enums.TournamentFormat;
import com.ilko.tournament.repository.AppUserRepository;
import com.ilko.tournament.repository.ParticipantRepository;
import com.ilko.tournament.repository.TournamentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies, over REAL simultaneous HTTP requests against the real embedded server (not direct
 * repository calls, not an artificially serialized test), that:
 * <pre>
 *   HTTP request -&gt; Spring Security -&gt; TournamentController -&gt; TournamentService
 *   (application-level equalsIgnoreCase check) -&gt; Hibernate -&gt; real MySQL
 *   UNIQUE(tournament_id, name_lower) constraint
 * </pre>
 * together guarantee that two concurrent "create participant" requests for the exact same name in
 * the exact same tournament can never both succeed - regardless of which one the application-level
 * check happens to see first.
 *
 * <p>Enable explicitly by supplying TEST_DB_USERNAME and TEST_DB_PASSWORD (same convention as
 * {@link ParticipantMySqlIntegrationTest}). Without a real MySQL instance configured, this test
 * class is SKIPPED - not failed - and that skip is reported explicitly rather than claimed as a
 * pass. This sandbox environment has no MySQL/Docker available, so this test has been written and
 * carefully reviewed but NOT executed here; run it locally with TEST_DB_USERNAME/TEST_DB_PASSWORD
 * set against a real MySQL instance to get a real result.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("mysqlTestDatabaseConfigured")
class ParticipantConcurrentHttpRequestIntegrationTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://"
                + env("TEST_DB_HOST", "localhost") + ":"
                + env("TEST_DB_PORT", "3306") + "/"
                + env("TEST_DB_NAME", "tournament_platform_test")
                + "?createDatabaseIfNotExist=true&serverTimezone=UTC");
        registry.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", ""));
        registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", ""));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    static boolean mysqlTestDatabaseConfigured() {
        return !env("TEST_DB_USERNAME", "").isBlank() && System.getenv("TEST_DB_PASSWORD") != null;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired AppUserRepository users;
    @Autowired TournamentRepository tournaments;
    @Autowired ParticipantRepository participants;
    @Autowired PasswordEncoder encoder;

    private static final String RAW_PASSWORD = "concurrency-test-password-1";
    private String organizerUsername;
    private Long tournamentId;

    /**
     * Fixtures (organizer account, tournament) are created directly through the repositories -
     * this is setup, not the behavior under test. Only the actual "create participant" calls in
     * the test itself go through the real HTTP endpoint, per the task's requirement.
     */
    @BeforeEach
    void createTournamentAndOrganizer() {
        organizerUsername = "concurrency-organizer-" + System.nanoTime();

        AppUser organizer = new AppUser();
        organizer.setUsername(organizerUsername);
        organizer.setEmail(organizerUsername + "@example.com");
        organizer.setPassword(encoder.encode(RAW_PASSWORD));
        organizer.setRole(Role.ORGANIZER);
        users.saveAndFlush(organizer);

        Tournament tournament = new Tournament();
        tournament.setName("Tournament A - " + System.nanoTime());
        tournament.setFormat(TournamentFormat.ELIMINATION);
        tournament.setStartDate(LocalDate.of(2099, 1, 1));
        tournament.setEndDate(LocalDate.of(2099, 1, 2));
        tournament.setOrganizer(organizer);
        tournamentId = tournaments.saveAndFlush(tournament).getId();
    }

    @Test
    void twoSimultaneousHttpRequestsForTheSameParticipantNameResultInExactlyOneSuccess() throws Exception {
        String url = "http://localhost:" + port + "/api/tournaments/" + tournamentId + "/participants";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(organizerUsername, RAW_PASSWORD);
        HttpEntity<String> requestA = new HttpEntity<>("{\"name\":\"Team Alpha\"}", headers);
        HttpEntity<String> requestB = new HttpEntity<>("{\"name\":\"Team Alpha\"}", headers); // identical name, real race

        int requestCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(requestCount);
        CountDownLatch bothReady = new CountDownLatch(requestCount);
        CountDownLatch go = new CountDownLatch(1);

        Callable<ResponseEntity<String>> fireRequest = () -> {
            bothReady.countDown();
            go.await(5, TimeUnit.SECONDS); // line both threads up so the requests hit the server as close together as possible
            // TestRestTemplate does not throw on non-2xx responses - the real status/body is returned directly.
            return restTemplate.postForEntity(url, requestA, String.class);
        };
        Callable<ResponseEntity<String>> fireRequestB = () -> {
            bothReady.countDown();
            go.await(5, TimeUnit.SECONDS);
            return restTemplate.postForEntity(url, requestB, String.class);
        };

        Future<ResponseEntity<String>> futureA = pool.submit(fireRequest);
        Future<ResponseEntity<String>> futureB = pool.submit(fireRequestB);
        assertTrue(bothReady.await(5, TimeUnit.SECONDS), "Both threads must reach the starting line before either fires");
        go.countDown(); // release both at (as close as practically achievable to) the same instant

        ResponseEntity<String> responseA = futureA.get(15, TimeUnit.SECONDS);
        ResponseEntity<String> responseB = futureB.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        List<ResponseEntity<String>> responses = List.of(responseA, responseB);

        // --- The application must not have crashed: every response has a real, well-formed HTTP status. ---
        for (ResponseEntity<String> response : responses) {
            assertNotNull(response.getStatusCode());
            assertNotNull(response.getBody());
        }

        // --- Exactly one request succeeded, the other was rejected - never both, never neither. ---
        long createdCount = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
        long rejectedCount = responses.stream().filter(r -> !r.getStatusCode().is2xxSuccessful()).count();
        assertEquals(1, createdCount, "Exactly one of the two identical concurrent requests must succeed. Actual responses: " + describe(responses));
        assertEquals(1, rejectedCount, "The other concurrent request must be rejected, not silently accepted as a second duplicate. Actual responses: " + describe(responses));

        // --- The rejected request must get a sane client error, never a 500, and never leak raw SQL. ---
        ResponseEntity<String> rejected = responses.stream().filter(r -> !r.getStatusCode().is2xxSuccessful()).findFirst().orElseThrow();
        assertTrue(rejected.getStatusCode().is4xxClientError(),
                "The losing request must get a 4xx client error (e.g. 409 Conflict), not a 500: got " + rejected.getStatusCode());
        assertNotEquals(HttpStatus.INTERNAL_SERVER_ERROR, rejected.getStatusCode(), "The database race must be handled gracefully, not surfaced as a server crash");
        String rejectedBody = rejected.getBody().toLowerCase();
        assertFalse(rejectedBody.contains("sql"), "Raw SQL must never reach the client: " + rejected.getBody());
        assertFalse(rejectedBody.contains("constraintviolationexception"), "Raw exception class name must never reach the client: " + rejected.getBody());
        assertFalse(rejectedBody.contains("uk_participant_tournament_name"), "Internal constraint name must never reach the client: " + rejected.getBody());

        // --- The database itself must contain exactly one "Team Alpha" row for this tournament - the ultimate source of truth. ---
        long persistedCount = participants.findAll().stream()
                .filter(p -> p.getTournament().getId().equals(tournamentId) && p.getName().equalsIgnoreCase("Team Alpha"))
                .count();
        assertEquals(1, persistedCount, "The database must contain exactly one 'Team Alpha' participant for this tournament, not zero or two");

        // --- The application must still be healthy and usable afterwards (it did not crash). ---
        ResponseEntity<ParticipantResponse[]> after = restTemplate
                .withBasicAuth(organizerUsername, RAW_PASSWORD)
                .getForEntity(url, ParticipantResponse[].class);
        assertEquals(HttpStatus.OK, after.getStatusCode());
        assertEquals(1, after.getBody().length, "A normal read after the race must still show exactly one participant");
    }

    private String describe(List<ResponseEntity<String>> responses) {
        StringBuilder sb = new StringBuilder();
        for (ResponseEntity<String> r : responses) sb.append(r.getStatusCode()).append(" -> ").append(r.getBody()).append(" | ");
        return sb.toString();
    }
}
