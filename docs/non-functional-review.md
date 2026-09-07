# Non-Functional Requirements Review

This review records demonstrated qualities only. A passing unit or MVC test is evidence of the tested behavior, not proof of production capacity or security hardening beyond that behavior.

| Requirement | Implementation | Evidence |
| --- | --- | --- |
| Security | Spring Security HTTP Basic, BCrypt hashing, stateless sessions, backend role checks, ownership checks, local-origin CORS, DTO validation, and sanitized errors. | Security tests cover missing authentication, forbidden roles, invalid credentials, and ownership. Password DTOs and entities are never returned. |
| Usability | Clear dashboard/detail/notification navigation, role-aware actions, native form constraints, date-range feedback, loading/error states, responsive CSS, and friendly API status messages. | `frontend/index.html`, `frontend/styles.css`, and `frontend/app.js`; frontend JavaScript syntax check passes. |
| Reliability | Transactional service operations, optimistic locking on tournament/match changes, explicit business exceptions, conflict handling, malformed-request handling, and generic sanitized `500` fallback. | Service and MockMvc tests cover invalid states, duplicate data, `400/401/403/404/409`, and successful responses. |
| Maintainability | Controller/service/repository separation, service interfaces, DTOs, mapper boundary, extracted `BracketGenerator` and `RankingCalculator`, and developer documentation. | Layered package structure and [business-rules.md](business-rules.md). |
| Performance | Explicit fetch plan for tournament lists and match participants prevents known endpoint-level N+1 loading while keeping relationships lazy by default. | `TournamentRepository.findAllForList` and `TournamentMatchRepository` entity graph; no benchmark claim is made. |
| Consistency | Shared API error shape, stable DTO contracts, canonical `GROUPS` enum value, centralized frontend request/error translation, and consistent validation messages. | Global exception handler, security handlers, DTO annotations, and API tests. |

## Known limitations

- The application still requires a configured MySQL server. Runtime startup could not be verified in this environment because the local `root` credentials were rejected.
- There are no real-database integration tests yet for schema generation, transaction rollback, foreign keys, or concurrent optimistic-lock conflicts.
- The frontend is intentionally dependency-free but remains a single `app.js` module; it is adequate for the current scope but should be split into modules if the UI grows.
- HTTP Basic is appropriate for the current practical-project scope, but production deployment requires HTTPS and a stronger browser authentication strategy. CSRF is disabled for the stateless API and should be revisited if cookie-based authentication is introduced.
- No throughput, latency, or load-test results are claimed.