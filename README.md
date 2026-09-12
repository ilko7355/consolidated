# Tournament Platform

Enterprise platform for organizing sports and esports tournaments, built with Java 17 and Spring Boot.
Organizers create tournaments in **elimination**, **double-elimination** or **group-stage** format and register
participants (or let participants join themselves). The platform generates a seeded bracket or a round-robin
schedule automatically, moves winners to the next round as results are entered - and in double elimination moves
the losers into a losers bracket instead of eliminating them - keeps live standings and statistics, and notifies
people about upcoming matches and final results. Administrators manage accounts and roles.

Individual practical project, 11th grade - PGKNMA "Prof. Minko Balkanski", Stara Zagora. Author: Iliya Dimitrov Vlahov.

## Features by module

| Module | What it does |
| --- | --- |
| Tournaments | Create, edit and delete tournaments (elimination, double elimination or groups); registration lifecycle `REGISTRATION -> IN_PROGRESS -> COMPLETED`. |
| Participants | Organizers register participants or teams and can link them to user accounts; participants can join and withdraw themselves while registration is open. |
| Bracket | Standard seeding (1 v 8, 4 v 5, 2 v 7, 3 v 6 ...), BYEs for the top seeds when the count is not a power of two, automatic advancement of winners, live bracket view. |
| Double elimination | A defeat drops a participant into the losers bracket; only a second defeat eliminates. Winners bracket, losers bracket and grand final are shown as three linked blocks. The organizer chooses per tournament whether the grand final is followed by a deciding rematch when the losers-bracket finalist wins it. |
| Groups | Groups with manual assignment and a round-robin schedule (circle method) - nobody plays twice in one round. |
| Results | Organizers enter scores; knockout matches (both elimination formats) must have a winner, group matches may end in a draw. |
| Rankings & statistics | Standings (played, wins, draws, losses, score difference, points), champion and podium, top scorer, biggest win, progress. |
| Notifications | Upcoming-match notifications for both players and the organizer, result notifications for the organizer, final results for everyone involved. |
| Administration | Platform overview, user list, role changes, blocking and unblocking accounts. |

## Technology stack

- Java 17, Spring Boot 3.4.5 (Spring Web MVC, Spring Data JPA / Hibernate, Spring Security, Bean Validation)
- MySQL 8 (MySQL Connector/J)
- Lombok, Maven Wrapper
- JUnit 5, Mockito, MockMvc, Spring Security Test
- Browser client: plain HTML, CSS and JavaScript (no framework, no build step), Playwright for optional UI checks

## Requirements

- JDK 17 or newer (`java -version`)
- MySQL 8.0 server running locally
- Node.js 18 or newer - for the one-command runner (`node dev.js`), the static client server and the optional e2e scripts

The Maven Wrapper downloads Maven and all libraries on the first build; no global Maven installation is needed.

## 1. Configure the database

The application creates the `tournament_platform` database on first start (`createDatabaseIfNotExist=true`)
and the tables through Hibernate (`ddl-auto=update`). The MySQL account only needs permission to create a database.

Put your MySQL credentials in a git-ignored file next to `pom.xml`:

```powershell
Copy-Item application-local.properties.example application-local.properties
notepad application-local.properties
```

```properties
spring.datasource.username=root
spring.datasource.password=your-mysql-password
```

Alternatively set the environment variables `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME` and `DB_PASSWORD`.
Values in `application-local.properties` take precedence. Never commit real passwords.

## 2. Start everything with one command

```powershell
node dev.js
```

This builds the runnable jar if it is missing, starts the backend and serves the browser client on
`http://localhost:5173`. Output from both is prefixed (`[runner ]`, `[backend]`) and Ctrl+C stops both.
To create the demo accounts and tournaments as well (only into a database without tournaments):

```powershell
node dev.js --demo
```

The same two commands are available as `npm start` and `npm run demo`; there are no npm dependencies to
install. Good to know:

- `$env:PORT = "5190"` before the command serves the client on another port.
- If a backend already answers on port 8080, the runner reuses it and starts only the client.
- Java is taken from `JAVA_HOME`, then from `PATH`, then from the usual JDK installation folders.

The next two sections describe how to start the two parts separately.

## 3. Start the backend only

```powershell
.\mvnw.cmd spring-boot:run
```

The REST API listens on `http://localhost:8080/api`.

### Demo data (recommended for presentations)

Start once with demo data to get ready-made accounts and one tournament in every stage - a completed
8-player chess knockout, a live 6-team esports bracket with BYEs, a group-stage league with draws and an
open tournament that participants can join:

```powershell
$env:DEMO_DATA = "true"
.\mvnw.cmd spring-boot:run
```

(or add `app.demo-data=true` to `application-local.properties`). Demo data is only created into a database
without tournaments. All demo accounts use the password `Demo12345`:

| Username | Role |
| --- | --- |
| `admin` | Administrator (created only if no administrator exists yet) |
| `organizer`, `coach.maria` | Organizer |
| `georgi`, `elena`, `nikola`, `viktoria`, `stefan`, `kalina`, `martin`, `yoana`, `petar`, `desislava`, `ivan`, `radostina` | Participant |

Never enable demo data on a real deployment.

### First administrator without demo data

Public registration only creates participants and organizers. The first administrator is created on startup
from configuration, and only if no administrator exists yet:

```powershell
$env:ADMIN_USERNAME = "admin"
$env:ADMIN_EMAIL = "admin@example.com"
$env:ADMIN_PASSWORD = "choose-a-strong-password"
.\mvnw.cmd spring-boot:run
```

The password is stored as a BCrypt hash. Further administrators are appointed from the Administration page.

## 4. Start the client only

In a second terminal:

```powershell
cd frontend
node server.js
```

Open `http://localhost:5173`. The client calls `http://localhost:8080/api` by default; to use another API,
define `window.__API_URL__` before `app.js` in `frontend/index.html`. Credentials are kept in memory only, so
reloading the page signs you out.

## Basic usage

1. Sign in as `organizer` (or register an organizer account) and create a tournament.
2. Add participants - optionally link each one to a user account - or let participants join from the tournament page.
3. For a group tournament create groups and assign every participant.
4. Generate the bracket or the group matches. Registration closes and the tournament moves to *In progress*.
5. Open a READY match on the Bracket/Matches tab and enter the score. Winners advance automatically.
6. Follow Standings and Results & stats; the tournament completes itself after the last match and everyone involved is notified.
7. Participants see their own matches under *My matches*; administrators manage accounts under *Administration*.

## Roles and permissions

| Role | Permissions |
| --- | --- |
| `PARTICIPANT` | View tournaments, brackets, standings, results and statistics; join and withdraw during registration; *My matches*; own notifications. |
| `ORGANIZER` | Everything a participant can view; create tournaments; for **own** tournaments: edit/delete during registration, register participants, manage groups, generate matches, enter results. |
| `ADMINISTRATOR` | Organizer rights for **any** tournament; platform overview; list users, change roles, block and unblock accounts (never their own). |

Every rule is enforced by the backend - URL rules and `@PreAuthorize` checks for roles, service-layer checks for
ownership and tournament state. The frontend only hides actions that would be rejected anyway.

## REST API

All endpoints except authentication require `Authorization: Basic ...`. Errors share one JSON shape:
`timestamp`, `status`, `error`, `message`, `path` - never stack traces or SQL.

| Method | Endpoint | Access |
| --- | --- | --- |
| POST | `/api/auth/register`, `/api/auth/login` | public |
| GET | `/api/tournaments`, `/api/tournaments/{id}` | authenticated |
| POST | `/api/tournaments` | organizer, admin |
| PUT / DELETE | `/api/tournaments/{id}` | owner, admin |
| GET / POST | `/api/tournaments/{id}/participants` | authenticated / owner, admin |
| POST / DELETE | `/api/tournaments/{id}/join` | participant |
| GET / POST | `/api/tournaments/{id}/groups` | authenticated / owner, admin |
| PUT / DELETE | `/api/tournaments/{id}/groups/{groupId}/participants/{participantId}` | owner, admin |
| POST | `/api/tournaments/{id}/generate-bracket` | owner, admin |
| GET | `/api/tournaments/{id}/bracket`, `/rankings`, `/results`, `/statistics` | authenticated |
| GET | `/api/matches/mine` | authenticated |
| POST | `/api/matches/{id}/result` | owner, admin |
| GET | `/api/notifications?unread=true\|false` | authenticated (own) |
| PUT | `/api/notifications/{id}/read`, `/api/notifications/read-all` | authenticated (own) |
| GET | `/api/admin/overview`, `/api/admin/users` | admin |
| PUT | `/api/admin/users/{id}/role`, `/api/admin/users/{id}/status` | admin |

## Architecture

```text
Browser client (HTML/CSS/JS)
   |  JSON over HTTP, HTTP Basic
   v
Spring Security filter chain  ->  REST controllers  ->  service interfaces / implementations
                                                          |-> BracketGenerator, RoundRobinScheduler,
                                                          |   RankingCalculator, TournamentStatisticsCalculator
                                                          v
                                                   Spring Data repositories -> JPA entities -> MySQL
```

Controllers bind and validate requests and declare role requirements. Services hold business rules, transactions,
ownership checks and notifications. The algorithms are separate Spring components without database access, so they
are unit-tested directly. DTO records and mappers keep entities (and password hashes) out of the API.

The algorithms, business rules and the ER diagram are described in [docs/business-rules.md](docs/business-rules.md).

## Project structure

```text
dev.js               one-command runner: backend + client
package.json         npm scripts only, no dependencies
src/main/java/com/ilko/tournament/
  config/       startup: schema guard, first administrator, demo data, authentication manager
  controller/   REST endpoints (auth, tournaments, matches, notifications, admin)
  dto/          request/response records with validation annotations
  entity/       JPA entities: AppUser, Tournament, Participant, TournamentGroup, TournamentMatch, Notification
  enums/        roles, formats and statuses
  exception/    business exceptions and the global JSON error handler
  mapper/       entity -> DTO mapping
  repository/   Spring Data JPA repositories
  security/     Spring Security configuration and user loading
  service/      service interfaces and the algorithms (bracket, round robin, rankings, statistics)
  service/impl/ service implementations
src/main/resources/  application.properties, application-prod.properties
src/test/java/       unit, algorithm, service, security (MockMvc) and optional MySQL integration tests
frontend/            browser client and a dependency-free static server
e2e/                 optional Playwright checks of the notification UI against a mocked API
docs/                business rules, testing strategy, non-functional review, SQL migrations
```

## Testing

```powershell
.\mvnw.cmd test
```

The suite covers the bracket algorithm (seeding, BYEs, advancement, complete random tournaments for 2-64
participants), round-robin scheduling, rankings, statistics, the tournament service (lifecycle, ownership,
registration, joining, groups, results, notifications), administration rules, request validation and HTTP
security (401/403/400/404/409). See [docs/testing-strategy.md](docs/testing-strategy.md).

Optional real-MySQL integration tests run only when `TEST_DB_USERNAME` and `TEST_DB_PASSWORD` are set
(database `tournament_platform_test` by default); otherwise they are reported as skipped.

Optional browser check of the notification UI (mocked API, no backend needed):

```powershell
npm install playwright
npx playwright install chromium
node e2e/server.js     # terminal 1, serves frontend/ on port 5500
node e2e/verify.js     # terminal 2
```

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `Access denied for user` on startup | Wrong MySQL credentials - check `application-local.properties`. |
| `Unable to establish loopback connection` / `Invalid argument: connect` when Tomcat starts (Windows) | Java 17 creates a local socket file in `%TEMP%`; it fails when that path is very long or contains non-Latin characters (for example a Cyrillic Windows user name). `node dev.js` sets a short folder automatically; when starting the backend by hand use `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=C:\Temp"` (create `C:\Temp` first), or `java -Djdk.net.unixdomain.tmpdir=C:\Temp -jar target\tournament-platform-0.0.1-SNAPSHOT.jar`. |
| `Port 8080 was already in use` | Stop the other process or start with `SERVER_PORT=8081` and set `window.__API_URL__` in the frontend accordingly. |
| Frontend shows "Unable to reach the server" | The backend is not running, or runs on another port than `window.__API_URL__`. |
| Demo data did not appear | It is only created when the database has no tournaments; use an empty database (`DB_NAME`) to seed again. |
| `Data truncated for column 'format'` | A database created before double elimination existed: MySQL stores `format` as a native `ENUM` and `ddl-auto=update` never widens one. On startup `TournamentFormatSchemaGuard` fixes this automatically and logs it; in production (`ddl-auto=validate`) run `docs/migrations/2026-09-double-elimination.sql` instead. |

## Production notes

- Run with `SPRING_PROFILES_ACTIVE=prod`: Hibernate only validates the schema, and the versioned scripts in
  `docs/migrations/` are applied beforehand.
- HTTP Basic must only be used over HTTPS; configure the allowed CORS origins for the real frontend host.
- Passwords are stored as BCrypt hashes; SQL logging is off by default (`SHOW_SQL=true` enables it locally).
- Keep `app.demo-data` disabled.
