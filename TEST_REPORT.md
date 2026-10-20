# Test report

Numbers below are copied from commands that were actually run. No count is estimated.

## Environment

| Item | Value |
|---|---|
| Java | 17.0.16 (Homebrew OpenJDK 17). `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` |
| Maven | 3.9.11. No Maven wrapper (`mvnw` is not in the repo). |
| OS | Darwin 25.5.0, arm64 |
| Database used by tests | H2 in memory, PostgreSQL compatibility mode. `src/test/resources/application.yml` |
| PostgreSQL | Not started |
| Docker containers | Not started. `docker compose config` exited 0, which only means the Compose file parses. |

`java` is not on the default `PATH` on this machine. Maven finds Java 24 unless `JAVA_HOME` points at JDK 17. Every command below used JDK 17.

## Baseline

| Item | Value |
|---|---|
| Command | `mvn -B test` |
| Exit code | 0 |
| Passed | 8 |
| Failed | 0 |
| Skipped | 0 |
| Completed | Yes |

The 8 tests were the original domain, fraud, repository, web, and one end-to-end test. There were no failures to fix.

## Final results

### `mvn -B clean test`

| Item | Value |
|---|---|
| Command | `mvn -B clean test` |
| Exit code | 0 |
| Tests run | 30 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 0 |
| Completed | Yes |
| Finished | 2026-10-20T21:08:51+05:30 |
| Maven total time | 10.474 s |

Per class on that run:

| Class | Tests |
|---|---|
| `CardTransactionRepositoryTest` | 5 |
| `ApiAcceptanceTest` | 13 |
| `AdminSeederTest` | 1 |
| `CardControllerWebTest` | 3 |
| `FraudRuleEngineTest` | 6 |
| `CardTransactionTest` | 1 |
| `SpendInsightsIntegrationTest` | 1 |

### `mvn -B verify`

| Item | Value |
|---|---|
| Command | `mvn -B verify` |
| Exit code | 0 |
| Tests run | 30 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 0 |
| Completed | Yes. The build packaged the JAR after the tests. |
| Finished | 2026-10-20T21:07:19+05:30 |
| Maven total time | 13.970 s |

`mvn verify` ran before `mvn clean test`. Both were against the same test sources. `clean test` is the later run and deleted `target/`, so the packaged JAR from `verify` is no longer on disk. `mvn spring-boot:run` rebuilds it.

## Acceptance criteria

| Feature | Test or verification | Expected | Actual | Status |
|---|---|---|---|---|
| Registration returns a token | `ApiAcceptanceTest.registrationReturnsTokenAndStoresBcryptHash` | 201, Bearer token, expiry 300s in the test profile | Passed | PASS |
| Password stored as BCrypt, not returned | Same test | Hash starts with `$2`, plaintext absent from the body and the hash | Passed | PASS |
| Duplicate registration | `duplicateRegistrationReturns409` | HTTP 409 | Passed | PASS |
| Public registration cannot become ADMIN | `registrationIgnoresClientSuppliedRole` | JWT role USER, admin route 403 | Passed | PASS |
| Login success and bad password | `loginAcceptsTheRightPasswordAndRejectsTheWrongOne` | 200 then 401 | Passed | PASS |
| Missing, expired, forged, role-less JWT | `missingExpiredAndForgedTokensCannotCallProtectedApis` | 401 | Passed | PASS |
| Create card and reject invalid card | `userManagesOnlyTheirOwnCards` | 201 for a valid card, 400 for a bad body | Passed | PASS |
| User sees only their cards | Same test | Other user's list is empty | Passed | PASS |
| IDOR on block, post, and history | Same test | 404 | Passed | PASS |
| Unauthenticated card list | Web test and acceptance test | 401 | Passed | PASS |
| Block and unblock | `blockStopsNewTransactionsUntilUnblocked` | BLOCKED, then 422, then ACTIVE, then 201 | Passed | PASS |
| Valid transaction saved | `recordsFraudFlagsAndRejectsBadTransactionInput` | 201, `flagged` false | Passed | PASS |
| Invalid amount, category, and JSON | Same test | 400 | Passed | PASS |
| HIGH_AMOUNT, OVER_LIMIT, both | Same test, plus `FraudRuleEngineTest` | Reasons match the rules, equal-to-threshold is clean | Passed | PASS |
| Velocity below, at, and outside the window | `velocityFlagsTheSixthTransactionInsideTheMinuteOnly` and repository count test | 5th clean, 6th flagged, 61 seconds earlier does not count | Passed | PASS |
| Default and future timestamps | `timestampPolicyDefaultsNowAndRejectsFarFutureValues` | Omitted time is near now; +4 min accepted; +6 min is 400 | Passed | PASS |
| Flag reasons returned | Fraud and velocity HTTP tests | `flagReason` is the joined rule ids | Passed | PASS |
| Pagination and sort | `historyIsPagedNewestFirstAndRejectsBadPageArguments` | Page 0 is newest two, page 1 is the oldest, no shared ids | Passed | PASS |
| Bad page and size | Same test | page -1, size 0, and `page=nope` are 400; size 500 is capped to 100 | Passed | PASS |
| Empty history | Same test | `content` empty, `totalElements` 0 | Passed | PASS |
| Monthly totals, counts, order | `monthlyInsightsUseAHalfOpenUtcWindowAndIgnoreOtherUsers` | Grand total 640.00, TRAVEL then DINING | Passed | PASS |
| Month start included, next month excluded | Same test, plus `monthWindowIncludesStartAndExcludesNextMonthStart` | 1 Oct included, 1 Nov excluded | Passed | PASS |
| Other user's spend excluded | Both of those tests | Other user's 999 is absent | Passed | PASS |
| Empty month and bad month | HTTP insights test | Zero total, empty categories; `2026-13` and a missing month are 400 | Passed | PASS |
| Flagged rows included in the month total | `monthlySpendIncludesFlaggedTransactions` | Flagged 20.00 is in the sum | Passed | PASS |
| Admin can list flagged rows | `adminSeesFlaggedTransactionsAndUsersDoNot` and `AdminSeederTest` | 200, only flagged rows | Passed | PASS |
| User forbidden, anonymous rejected | Same tests | 403 and 401 | Passed | PASS |
| Admin seeded from env, password hashed | `AdminSeederTest` | Role ADMIN, BCrypt hash, login works | Passed | PASS |
| Maven package / verify | `mvn -B verify` | Exit 0 | Exit 0, 30 tests | PASS |
| CI workflow file | `.github/workflows/ci.yml` runs `mvn -B verify` | Same command as local verify | File present. A finished GitHub Actions run was not observed | NOT RUN |
| PostgreSQL | Compose service defined | App talks to Postgres | No Postgres process was started | NOT RUN |
| Docker image | `Dockerfile`, `docker compose config` | Image builds and serves traffic | Compose file parses. Image was not built or run | NOT RUN |
