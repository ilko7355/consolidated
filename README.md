# Tournament Platform

Tournament Platform is a client-server application for organizing tournaments. Organizers create tournaments, register participants, generate elimination brackets, record results, and receive notifications. Authenticated users can browse tournament data and rankings.

The application does not implement group-tournament scheduling. `GROUPS` can be stored as a format, but bracket generation is implemented only for `ELIMINATION` tournaments.

## Technology Stack

- Java 25
- Spring Boot 3.4.5
- Spring Web MVC
- Spring Data JPA and Hibernate
- MySQL Connector/J
- Spring Security
- Jakarta Bean Validation
- Lombok
- Maven Wrapper
- JUnit 5, Mockito, and MockMvc
- Plain HTML, CSS, and browser JavaScript

The frontend has no Node.js, React, TypeScript, Vite, or npm build step. `package-lock.json` is not used by the application.

## Requirements

- JDK 25 or newer
- MySQL 8.0 or compatible MySQL server
- Node.js 18 or newer, only if using the optional `npx http-server` command
- PowerShell on Windows, or an equivalent shell

```powershell
java -version
mysql --version
```

## Database Setup

Create the database with a MySQL account that has permission to create databases:

```sql
CREATE DATABASE tournament_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Configure the application with environment variables. Never commit real passwords:

```powershell
$env:DB_HOST = "localhost"
$env:DB_PORT = "3306"
$env:DB_NAME = "tournament_platform"
$env:DB_USERNAME = "your-mysql-user"
$env:DB_PASSWORD = "your-mysql-password"
```

The JDBC URL also includes `createDatabaseIfNotExist=true`, but the MySQL account still needs suitable privileges. The default profile uses `ddl-auto=update` for local development. Production uses the `prod` profile with `ddl-auto=validate`; run the versioned scripts in `docs/migrations/` before starting it. SQL and bind-value logging should be disabled outside local development.

### Versioned schema changes

The project uses explicit, manually applied MySQL migrations rather than Flyway or Liquibase. The migration owner is the `docs/migrations/` directory; each file is applied once, in version order, and its result should be recorded by the deployment process. `V001__participants_tournament_relationship.sql` is the **one authoritative migration** for the participant restructuring, and is the only script that changes the schema, in every environment - local, staging, and production.

The older `docs/migrate-participants-table.sql` is **deprecated as a migration**: it no longer runs any `ALTER`/`UPDATE`/`DROP` statement, so there is only one implementation of this schema change to keep in sync. It remains only as an optional, read-only diagnostic you can run against a local database **before** V001, if you still have data in the legacy `tournament_participants` join table and want to see the exact rows that need manual attention (ambiguous or missing legacy links, and name collisions that would violate the new unique constraint) rather than just a count. If your local database is empty or disposable, skip it entirely - drop the database and let the app recreate the schema on next startup.

For production:

```powershell
$env:SPRING_PROFILES_ACTIVE = "prod"
.\mvnw.cmd spring-boot:run
```

The production profile never lets Hibernate change tables. Hibernate validates the schema, while the migration scripts change it. `ParticipantSchemaGuard` remains as a focused diagnostic for participant rows that are structurally present but incomplete.

> **Existing local database?** The `participants` table was restructured to add a direct `tournament_id`
> link and a case-insensitive unique constraint preventing duplicate participant names within the same
> tournament. `ddl-auto=update` can add the new columns to a **fresh/empty** database, but cannot safely
> add them as `NOT NULL` to a table that **already has rows** - MySQL rejects that outright, and because
> Hibernate's schema update does not stop the app from starting even when this happens, the app can look
> like it started fine while silently missing the columns (`ParticipantSchemaGuard` catches this at
> startup and fails loudly with instructions, instead of letting it surface later as a confusing SQL
> error on first participant registration). If you already have participants in a local database you
> want to keep, run `docs/migrations/V001__participants_tournament_relationship.sql` once **before**
> starting the app with the new code (optionally, run the read-only `docs/migrate-participants-table.sql`
> diagnostic first if you want to inspect any legacy rows needing manual attention). If your local
> database is empty or disposable, just drop and let the app recreate it - no manual step needed.

## Backend

Run commands from the repository root, `enterprise-tournament-system`. The Maven Wrapper downloads dependencies automatically; a global Maven installation is not required.

Run tests:

```powershell
.\mvnw.cmd test
```

Start Spring Boot after configuring MySQL:

```powershell
.\mvnw.cmd spring-boot:run
```

The API listens on `http://localhost:8080` and uses the base path `http://localhost:8080/api`.

## Frontend

The browser client is in `frontend/`. It is a dependency-free static application using stateless HTTP Basic authentication. Node.js is used only to run an optional static file server; it is not a frontend framework or application dependency.

In a second terminal:

```powershell
cd frontend
npx --yes http-server . -p 5173
```

Open `http://localhost:5173`. The default API is `http://localhost:8080/api`. To use another API, define this before `app.js` in `frontend/index.html`:

```html
<script>window.__API_URL__ = 'https://host.example/api';</script>
<script src="app.js"></script>
```

There is no frontend production build command. Serve the `frontend/` directory with a static web server. Use HTTPS and configure backend CORS for deployment.

## Basic Usage

1. Start MySQL and set the database environment variables.
2. Start the backend with `.\mvnw.cmd spring-boot:run`.
3. Start the frontend server on port 5173.
4. Register an account. Set `organizer` to `true` to create an organizer account.
5. Sign in. Credentials are kept in runtime memory and are not restored after page reload.
6. An organizer creates a tournament, adds participants, generates an elimination bracket, and records results.
7. Authenticated users can view tournament reports and rankings. Organizers can view their notifications.

## Roles and Permissions

| Role | Permissions |
| --- | --- |
| `PARTICIPANT` | View tournaments, participants, brackets, results, rankings, and their own notifications. Cannot perform tournament or match mutations. |
| `ORGANIZER` | All `PARTICIPANT` permissions; create tournaments; update/delete owned registration-stage tournaments; add participants; generate owned elimination brackets; record owned match results. |
| `ADMINISTRATOR` | Authenticated reads and authorized tournament mutations for **any** tournament, regardless of who organizes it. |

Backend role checks and service-layer ownership checks enforce these permissions. Frontend controls are not a security boundary.

### Creating an administrator account

There is no public sign-up option for `ADMINISTRATOR` - registration only ever creates `ORGANIZER` or
`PARTICIPANT` accounts, by design (a public administrator sign-up would be a security hole).

Instead, the very first administrator is bootstrapped automatically on application startup from
configuration. Set these before starting the app:

```bash
export ADMIN_USERNAME=admin
export ADMIN_EMAIL=admin@example.com
export ADMIN_PASSWORD=choose-a-strong-password
```

On startup, `AdminAccountInitializer` creates this account (with the password securely hashed through
the same `PasswordEncoder` used everywhere else) **only if no administrator account exists yet**. It is
safe to leave these variables set permanently - after the first admin is created, the initializer does
nothing on every subsequent restart. If the variables are left unset, no administrator is created and a
log message explains how to configure one.

## REST API Overview

Authentication:

- `POST /api/auth/register`
- `POST /api/auth/login`

Tournaments:

- `GET /api/tournaments`
- `POST /api/tournaments`
- `GET /api/tournaments/{id}`
- `PUT /api/tournaments/{id}`
- `DELETE /api/tournaments/{id}`
- `GET /api/tournaments/{id}/participants`
- `POST /api/tournaments/{id}/participants`
- `GET /api/tournaments/{id}/groups`
- `POST /api/tournaments/{id}/groups`
- `PUT /api/tournaments/{id}/groups/{groupId}/participants/{participantId}`
- `DELETE /api/tournaments/{id}/groups/{groupId}/participants/{participantId}`
- `GET /api/tournaments/{id}/bracket`
- `POST /api/tournaments/{id}/generate-bracket`
- `GET /api/tournaments/{id}/rankings`
- `GET /api/tournaments/{id}/results`

Matches and notifications:

- `POST /api/matches/{id}/result`
- `GET /api/notifications?unread=true|false`
- `PUT /api/notifications/{id}/read`

Protected endpoints require an `Authorization: Basic ...` header. Errors use `timestamp`, `status`, `error`, `message`, and `path` fields without stack traces or database details.

## Architecture

```text
Browser client -> REST controllers -> service interfaces and implementations
                                      -> repositories -> JPA entities -> MySQL
```

Controllers handle HTTP binding, validation, authorization annotations, and response status. Services contain business rules, transactions, ownership checks, orchestration, and notifications. Repositories handle persistence. DTOs and `TournamentMapper` separate API contracts from entities. `BracketGenerator` and `RankingCalculator` contain deterministic, unit-testable algorithms.

## Database Model

Major tables are `app_users`, `tournaments`, `participants`, `tournament_participants`, `tournament_matches`, and `notifications`. Numeric IDs are generated by the database except for the composite bridge key. Foreign keys connect organizers, tournament membership, match participants/winners/next matches, and notification recipients. Relationships are lazy and remove cascading is avoided because participants are reusable. See [business-rules.md](docs/business-rules.md) for the ER diagram and rule details.

## Project Structure

```text
src/main/java/com/ilko/tournament/
  controller/   REST endpoints
  service/      service contracts and business algorithms
  service/impl/ service implementations and transactions
  repository/   Spring Data persistence interfaces
  entity/       JPA database entities
  dto/          request and response API records
  mapper/       entity-to-response mapping
  security/     Spring Security and user loading
  exception/    centralized API error handling
frontend/       static browser client
docs/           architecture, business rules, testing, and NFR documentation
src/test/       unit, validation, security, and MockMvc tests
```

## Testing

Run the complete suite:

```powershell
.\mvnw.cmd test
```

The suite covers authentication, authorization, ownership, CRUD API contracts, standard error statuses, DTO validation, duplicate data, invalid lifecycle states, bracket generation, BYE handling, and deterministic rankings. The current suite is unit and MockMvc based; real-MySQL schema, transaction, concurrency, and load tests require a configured integration environment.

See [testing-strategy.md](docs/testing-strategy.md) for the test matrix, [business-rules.md](docs/business-rules.md) for business logic, and [non-functional-review.md](docs/non-functional-review.md) for evidence-based quality findings.

## Security and Deployment Notes

- Passwords are stored as BCrypt hashes.
- HTTP Basic must be transported over HTTPS in deployment.
- Authentication is stateless and credentials are not persisted by the frontend.
- Local CORS origins are configured for development; production origins must be explicitly configured.
- Development schema auto-update and SQL bind logging must not be used unchanged in production.
