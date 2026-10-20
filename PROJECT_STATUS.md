# Project status

Audit date: 20 Oct 2026. The existing repository was inspected before any feature changes. Nothing was replaced with a second project.

## Architecture

Layered Spring Boot API, package `com.swaroop.spendinsights`.

```
HTTP client
  -> JwtAuthFilter (signature, expiry, subject, role)
  -> SecurityConfig (public auth, ADMIN routes, everything else authenticated)
  -> Controller
  -> Service (ownership, fraud, totals)
  -> Spring Data JPA repository
  -> H2 in local/test, PostgreSQL when DB_URL points at it
```

A transaction create follows: `POST /api/v1/cards/{id}/transactions` → `JwtAuthFilter` → `CardController.addTxn` → `TransactionService.record` → `CardService.getOwnedForUpdate` → `FraudRuleEngine.evaluate` → `CardTransactionRepository.save`.

## Baseline, before this pass

Command: `mvn -B test` with `JAVA_HOME` set to Homebrew OpenJDK 17.0.16.

| Result | Value |
|---|---|
| Exit code | 0 |
| Tests | 8 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 0 |

The skeleton already had entities, repositories, JWT auth, card and transaction services, three fraud rules, monthly JPQL aggregation, Swagger, Docker files, CI, and five test classes. It compiled and the original 8 tests passed.

## Gaps found in the audit

- Duplicate registration used `BusinessRuleException`, so the HTTP status was 422. The contract now required 409.
- A client-supplied `role` was ignored in code, but that was not tested.
- Expired, forged, and role-less JWTs were not tested. The filter also accepted a token whose `role` claim was missing.
- Pagination accepted a negative page or a zero size and then threw an unformatted exception. Page JSON was Spring's `PageImpl`, which Spring itself warns is not a stable contract.
- Future `occurredAt` values were stored with no policy.
- Malformed JSON and bad enum values were not mapped to the shared error body.
- Fraud boundaries (equal to the threshold, one step over, velocity at 4/5/6, the exact one-minute window) were not tested.
- Month boundaries, empty months, flagged rows inside the monthly total, admin seeding, and cross-user card access were only partly covered.
- 401 and 403 responses were empty status lines, not the JSON error body.
- Two simultaneous transactions on one card could both pass the velocity count. The count-then-insert race was undocumented.

## What this pass changed

- `ConflictException` → HTTP 409 for duplicate email, including a unique-constraint race.
- `BadRequestException` → HTTP 400 for a negative page, a size below 1, and an `occurredAt` more than 5 minutes in the future.
- Missing timestamps still default to `Instant.now()` (UTC).
- History returns `TransactionPage`: `content`, `page`, `size`, `totalElements`, `totalPages`. Size above 100 is capped at 100.
- Fraud thresholds stay strict `>` for amount rules and `>=` for velocity. The velocity window is configurable (`velocity-window-seconds`, default 60) and inclusive on both ends.
- `TransactionService.record` locks the card row before the velocity count, so a second request on the same card waits and sees the first insert.
- JWT filter requires a subject and a role of `USER` or `ADMIN`.
- 401 and 403 write the same `ErrorResponse` JSON as the controller advice.
- Public registration still forces `Role.USER`.
- Tests expanded from 8 to 30. See `TEST_REPORT.md`.

## Still not done

- No Flyway migrations. Schema updates still come from Hibernate `ddl-auto`.
- No idempotency key. A client that retries a successful POST creates a second transaction. The server itself does not retry the insert.
- Email match is case-sensitive.
- Admin flagged list is not paginated.
- PostgreSQL and the Docker image were not started in this session. `docker compose config` only checked that the Compose file parses.
- GitHub Actions was not observed on a finished run at the time these notes were written.
