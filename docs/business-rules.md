# Business Rules and Algorithms

## Business rules

| Rule | Input and condition | Processing and output | Errors and data |
| --- | --- | --- | --- |
| Account registration | Username/email are not already registered and the request is valid. | BCrypt-hash the password, assign `USER` or `ORGANIZER`, and persist the account. | `409` for duplicate username/email; `app_users` is affected. |
| Tournament ownership | A mutation includes an authenticated user and tournament ID. | Allow an organizer only when they own the tournament; allow an admin for any tournament. | `401` without authentication and `403` without permission; `tournaments` is affected. |
| Tournament date validity | End date must not precede start date; start date cannot be in the past for requests. | Reject invalid requests before persistence. | `400`; `tournaments` is affected only after validation succeeds. |
| Participant registration | Tournament is in `REGISTRATION` and the name is not already present. | Create the participant and add it to the tournament bridge. | `409` for duplicate names and `400` when registration is closed; `participants` and `tournament_participants` are affected. |
| Bracket generation | Tournament is elimination, has at least two participants, and has no existing matches. | Generate one-elimination rounds, pad slots with BYEs, persist matches, and move the tournament to `IN_PROGRESS`. | `400` for an invalid format/count/state; `tournaments` and `tournament_matches` are affected. |
| Match result | Match is `READY`, scores are non-negative and unequal, and the caller owns the tournament or is an admin. | Store scores, mark the match completed, advance the winner, notify the organizer, and complete the tournament at the final round. | `400` for invalid state/scores/ties; `tournament_matches`, `tournaments`, and `notifications` are affected. |

## Elimination bracket algorithm

`BracketGenerator` chooses the smallest power of two greater than or equal to the participant count. It creates the first round with participant slots, creates later empty rounds, and links each pair of matches to its next match. A match with two participants becomes `READY`; a match with exactly one participant becomes a completed BYE and advances that participant. This keeps bracket shape deterministic and avoids special cases in the controller.

For $n$ participants, the padded bracket contains $2^k - 1$ matches where $2^k$ is the next power of two. Construction is $O(2^k)$ time and space. Empty or one-participant input is rejected by the service before generation.

## Ranking algorithm

`RankingCalculator` initializes zeroed statistics for every registered participant, processes completed matches once, then sorts by wins descending, losses ascending, and participant ID ascending. Points are three per win, matching the existing application rule. Incomplete matches and matches without a winner are ignored.

For $p$ participants and $m$ matches, aggregation is $O(p + m)$ and sorting is $O(p \log p)$. Empty participant input returns an empty ranking. The deterministic ID tie-breaker ensures repeatable output.