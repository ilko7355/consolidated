# Testing Strategy

The suite prioritizes business invariants and externally visible API behavior over raw test count. Unit tests isolate deterministic algorithms and service rules with Mockito. MockMvc tests verify controller routing, validation, security, status codes, response bodies, and centralized exception handling.

## Executable test matrix

| Test ID | Description | Preconditions | Input | Expected result | Actual result | Status |
| --- | --- | --- | --- | --- | --- | --- |
| API-001 | List tournaments | Authenticated user | `GET /api/tournaments` | `200 OK`, list response | `200 OK` | PASS |
| API-002 | Create tournament | Authenticated organizer; valid dates | Valid elimination JSON | `201 Created` and DTO body | `201 Created` with ID/name | PASS |
| API-003 | Update tournament | Authenticated organizer | Valid update JSON | `200 OK` and updated DTO | `200 OK` with updated name | PASS |
| API-004 | Delete tournament | Authenticated organizer | Tournament ID | `204 No Content` | `204 No Content` | PASS |
| API-005 | Invalid request | Authenticated organizer | Missing tournament name | `400` validation response | `400` | PASS |
| API-006 | Missing authentication | No credentials | Protected GET | `401 Unauthorized` | `401` | PASS |
| API-007 | Incorrect role | Authenticated `USER` | Create request | `403 Forbidden` | `403` | PASS |
| API-008 | Missing resource | Authenticated user | Unknown tournament ID | `404` with standard error body | `404`, status/path asserted | PASS |
| API-009 | Conflict | Authenticated organizer | Duplicate/create conflict | `409` with standard error body | `409`, status asserted | PASS |
| AUTH-001 | BCrypt registration | Empty user repository | Valid registration | Persist encoded password | Hash verified | PASS |
| AUTH-002 | Invalid login | Authentication manager rejects | Wrong password | Authentication failure | Rejection verified | PASS |
| OWN-001 | Ownership protection | Different organizer | Update another owner’s tournament | Forbidden business exception; no save | Verified | PASS |
| RULE-001 | Duplicate participant | Existing case-insensitive name | Register duplicate | Conflict; no participant save | Verified | PASS |
| RULE-002 | Invalid tournament state | Tournament in progress | Update tournament | Business exception; no save | Verified | PASS |
| ALG-001 | Bracket BYE | Three participants | Generate and resolve | Power-of-two bracket and automatic advances | Verified | PASS |
| ALG-002 | Bracket boundary | Fewer than two participants | Generate bracket | Reject invalid input | Verified | PASS |
| ALG-003 | Ranking | Completed match data | Calculate standings | Deterministic wins/points/order | Verified | PASS |
| VAL-001 | Request validation | Validator available | Valid and invalid DTOs | Correct constraints/messages | Verified | PASS |

## Coverage boundaries

The current executable suite does not run against a real MySQL instance. Database foreign keys, Hibernate schema generation, transaction rollback, query plans, and optimistic-lock races require a configured integration environment. The application currently depends on external MySQL credentials, so adding an embedded database would introduce a new runtime technology rather than reflect production behavior.

Run the complete suite with:

```powershell
.\mvnw.cmd test
```