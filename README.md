# Ledger

Card references, purchases, and a monthly spend total. Java 17, Spring Boot 3.3.5.

This service does not issue cards and it does not store a card number. A card-management system issues the plastic. This API receives the reference that is safe to keep: last four digits, a name, a limit, and a status. On create it also stores a stable `accountReferenceId` and a SHA-256 `cardToken` built from the owner email, card id, last four, and that reference. Purchases are written to a ledger, checked by `FraudRuleEngine`, and summed by category for the signed-in customer.

The demo UI is at [http://localhost:8080](http://localhost:8080). Swagger remains at `/swagger-ui.html`.

## Boundary

| This service does | This service does not |
|---|---|
| Register a person and issue a JWT | Accept a client-chosen role. Public registration is always `USER` |
| Store a card reference owned by that person | Store a PAN, CVV, or expiry |
| Fingerprint the reference with SHA-256 | Hash a PAN+CVV payload. That belongs in a token vault |
| Append a purchase and flag it when the score is under 70 | Read a fingerprint, a face, or an EMV 3-D Secure directory |
| Hold authorization when the score is 70 or more, until the step-up code matches | Store a PAN, CVV, or the dynamic CVV code |
| Refuse a purchase when the reference is blocked | Retry a failed insert on its own |
| Sum a customer's month in the database | Include another customer's rows |

A flag and a block are different decisions. `HIGH_AMOUNT`, `OVER_LIMIT`, and `VELOCITY` are notes on a saved row when the score is under 70. A blocked reference, a velocity block, a dynamic CVV mismatch, and a PAN that does not match the reference return 422 and write nothing.

## Architecture

Two layers, both in this process. `TransactionService.record` decides before the HTTP response. `BehavioralLoop` stores the outcome after that database transaction commits. Neither layer keeps a PAN, CVV, or expiry.

```
browser or API client
        │
        ▼
┌────────────────────────────────────────────────────────────┐
│ Synchronous clearing          budget 50 ms, measured       │
│ TransactionService.record                                  │
└──────────────────────────────┬─────────────────────────────┘
                               │
            1. EdgeTokenizerFilter
               drop pan, cvv, and cvc before the body is bound
               a 13–19 digit PAN is reduced to its last four
               and must match the card reference
                               │
                               ▼
            2. NetworkGuard
               dynamic CVV checked as a hash
               velocity block after 30 attempts in 60 seconds
               in-memory, or Redis when NETWORK_STATE=redis
                               │
                               ▼
            3. InferenceEngine → FraudRuleEngine
               fixed weights for amount, limit, balance,
               velocity, place change, and high-risk category
               riskScore is 0–100
                               │
                ┌──────────────┴──────────────┐
         score under 70                 score 70 or more
                │                              │
                ▼                              ▼
   CardTransactionRepository          step-up hold, HTTP 202
   AUTHORIZED or FLAGGED              method BIOMETRIC_STEP_UP
                │                              │
                │                     POST .../step-up/{id}
                │                     matching code writes the row
                │                     a wrong code writes nothing
                └──────────────┬───────────────┘
                               ▼
                            commit
                               │
                               ▼
                      BehavioralLoop
                      behavioral_signals
                      CLEARED, STEP_UP_VERIFIED, or STEP_UP_FAILED
```

`cardToken` is the vault id for this ledger. It is `SHA-256(pepper|ownerEmail|cardId|last4|accountReferenceId)`, assigned when the card reference is created. It is not a hash of a PAN, CVV, and expiry.

`X-Clearing-Budget-Ms` is 50. `X-Clearing-Latency-Ms` is how long that synchronous section took. A breach is logged. This process does not guarantee the latency of a network authorization host.

The step-up method is named `BIOMETRIC_STEP_UP`. The check is a 6-digit code, returned once on the 202, and only its hash is stored. This API does not read a fingerprint or a face, and it does not call a 3-D Secure directory server. Three wrong codes, or a hold older than 5 minutes, close the challenge.

A card flagged in the last 72 hours can add `REPEAT_RISK`, `RISK_COHORT`, and `STEP_UP`. Those weights can push the next purchase to 70 or above, which is the hold. `BehavioralLoop` stores that label for later review. It does not change the live weights and it does not re-decide the purchase.

Ownership is `findByIdAndOwnerEmail`. A guessed id returns 404, so the caller does not learn that the card exists. The card row is locked for the duration of a purchase write, so two requests on the same card cannot both pass the velocity count.

Money is `BigDecimal`. The month window is half-open UTC: `1 Oct 00:00` inclusive, `1 Nov 00:00` exclusive. Flagged rows are included in the sum.

## Processing

**Time.** A missing `occurredAt` becomes the current UTC instant. More than five minutes in the future is 400. Anything earlier is accepted.

**Fraud.** Thresholds live in `application.yml`.

| Rule | Fires when |
|---|---|
| `HIGH_AMOUNT` | amount is strictly greater than 50000 |
| `OVER_LIMIT` | amount is strictly greater than that card's limit |
| `OVER_BALANCE` | already-saved purchases plus this amount are strictly greater than that card's limit |
| `VELOCITY` | five or more purchases are already saved in the inclusive previous 60 seconds |
| `GEO_VELOCITY` | this purchase names a different place from another purchase on that card in the previous 60 minutes |
| `REPEAT_RISK` | the card was flagged in the previous 72 hours and this amount is strictly greater than 10000 |
| `RISK_COHORT` | the card was flagged in the previous 72 hours and the category is high-risk (`ENTERTAINMENT` or `OTHER` by default) |
| `STEP_UP` | the card was flagged in the previous 72 hours and this amount is strictly greater than 5000 |

Equal to a threshold is clean. The purchase being judged is not in the velocity count yet, so the sixth one in that minute is the first flag. Reasons are returned in that order, joined with a comma. The response also carries `riskScore` (0–100), `stepUpSuggested`, and `clearingDecision` (`AUTHORIZED`, `FLAGGED`, or `STEP_UP`). The same response includes `timeDeltaSeconds` since the previous purchase on that card, `placeChanged` when both purchases named a place and the names differ, and `highRiskCategory` (`1` for `ENTERTAINMENT` or `OTHER`, otherwise `0`).

`riskScore` is the sum of fixed weights for the rules that fired, capped at 100. That is the linear model the synchronous path uses. A place change is the geolocation check. `ENTERTAINMENT` and `OTHER` are the high-risk categories. There is no separate neural network.

A score under 70 still saves the row, including a flag. A score of 70 or more does not. The response is `202` with `status` `STEP_UP_REQUIRED`, `method` `BIOMETRIC_STEP_UP`, the reasons, and `challengeCode`. `POST /api/v1/cards/{id}/step-up/{challengeId}` with that code writes the row. The code is not stored; only its hash is. It expires after 5 minutes.

`POST /api/v1/cards/{id}/dynamic-cvv` returns a 3-digit code for 60 seconds. The server stores the hash. Sending `dynamicCvv` on the purchase checks it. Omitting the field skips the check. The prevention layer also refuses the purchase when more than 30 attempts for that card token land inside 60 seconds (`Velocity block`). That hard stop is separate from the `VELOCITY` flag, which still marks the sixth saved purchase in the same minute.

After the decision commits, `BehavioralLoop` copies the facts into `behavioral_signals`, including `outcome`. A failure there does not undo the ledger and does not change the decision. The row is a labeled example. This service does not refit the live weights from it.

With `NETWORK_STATE=redis` and `REDIS_URL`, the velocity window and the dynamic-CVV hash are kept in Redis. The default `memory` keeps them in this process. `docker compose up` starts Redis and sets those variables.

**Tokenization in this ledger.** `accountReferenceId` is a UUID assigned at card create. `cardToken` is `SHA-256(pepper|ownerEmail|cardId|last4|accountReferenceId)`. The same inputs always produce the same token. Different owners with the same last four produce different tokens. The stored row never contains a PAN or CVV.

**PAN at the edge.** A purchase body may include `pan`. `EdgeTokenizerFilter` removes `pan`, `cvv`, and `cvc` before the controller binds the JSON. A 13–19 digit PAN is reduced to its last four and must match the card reference. Anything else in that field is 400. The full number is not logged, stored, or returned. The vault token remains `cardToken`.

**Retries.** One `Idempotency-Key` belongs to one successful purchase for that user. Send it on `POST /transactions`. The same key and the same body return the original purchase (`200`). The same key and a different body return `409`. The key is stored in `idempotency_keys` only after the transaction row is saved, in the same database transaction. It is not a column on the purchase, and it is not in the JSON body. The response header echoes the key you sent, on success and on failure. The server does not generate a key and does not retry a failed insert. A 400 or 422 leaves the key free, so the client may send it again. A fraud flag under the authorize threshold is a success: the row is saved and the key is kept. A step-up hold does not keep the key until the code is verified and the row is written. Keys are 8–80 characters from `[A-Za-z0-9_.:-]`. With no header, a second POST creates a second row.

**Trace.** Every response carries `X-Request-Id`. Pass one in, or the server assigns a UUID. It is also on the log line. That id is separate from the idempotency key.

## Run

JDK 17 and Maven 3.9+. This machine's default `java` may not be 17. Point `JAVA_HOME` at 17 before Maven.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
mvn spring-boot:run
```

Open http://localhost:8080. Create an account, save a reference (`4321`, Gold, limit `40000`), record ₹250.50 at a cafe, then ₹60000 at an airline. The second row is flagged `HIGH_AMOUNT,OVER_LIMIT`. Block the reference and the next purchase returns 422.

```bash
mvn clean test
mvn verify
```

Tests use H2. They do not start PostgreSQL.

| Variable | Use | If unset |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | JDBC | In-memory H2 |
| `JWT_SECRET` | HMAC key, 32+ characters | A development default. Set a real one before any shared environment |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Seed one admin at startup | No admin is created |
| `POSTGRES_PASSWORD` | Compose only | `localdevonly` |

```bash
docker compose up --build
```

Compose waits until Postgres answers `pg_isready` and Redis answers `PING`, then starts the API as a non-root user with `NETWORK_STATE=redis`. Schema changes are still Hibernate `ddl-auto: update`. That is acceptable for this demo and the wrong tool for a real migration history. A local `mvn spring-boot:run` keeps the network state in memory and does not need Redis.

## API

| Method | Path | Who | Result |
|---|---|---|---|
| POST | `/api/v1/auth/register` | public | 201 token. Duplicate email is 409 |
| POST | `/api/v1/auth/login` | public | 200 token, or 401 |
| POST | `/api/v1/cards` | user | 201 reference |
| GET | `/api/v1/cards` | user | own references |
| PATCH | `/api/v1/cards/{id}/block` | owner | 200, then purchases return 422 |
| PATCH | `/api/v1/cards/{id}/unblock` | owner | 200 |
| POST | `/api/v1/cards/{id}/dynamic-cvv` | owner | 200, a 3-digit code and a 60-second expiry |
| POST | `/api/v1/cards/{id}/transactions` | owner | 201, 200 when the idempotency key repeats, or 202 when the score is held |
| POST | `/api/v1/cards/{id}/step-up/{challengeId}` | owner | 201 once the step-up code matches. A wrong code is 422 and writes nothing |
| GET | `/api/v1/cards/{id}/transactions?page=0&size=20` | owner | newest first. `size` above 100 is capped |
| GET | `/api/v1/insights/monthly?month=2026-10` | user | category totals and a grand total |
| GET | `/api/v1/admin/flagged-transactions` | admin | flagged rows only. A user receives 403 |

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"Passw0rd!"}' | jq -r .token)

curl -s -X POST localhost:8080/api/v1/cards \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"last4":"4321","nickname":"Gold","creditLimit":40000}'

curl -s -X POST localhost:8080/api/v1/cards/1/transactions \
  -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: cafe-2026-10-20-1' \
  -H 'Content-Type: application/json' \
  -d '{"amount":250.50,"merchant":"Cafe","category":"DINING"}'
```

History page:

```json
{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0}
```

Errors share one body: `status`, `error`, `message`, `timestamp`. Wrong or missing credentials are 401. A valid user on an admin route is 403. A card the caller does not own is 404.

## What is still unfinished

Flyway, refresh tokens, a required idempotency key, pagination of the admin queue, and a real Postgres run in CI. The automated tests have not started the Docker image. The live score is a fixed linear model; the async loop stores labels and does not refit those weights. The step-up code is not a biometric capture. `FILE_BY_FILE_GUIDE.md` is the line-by-line interview notes. This file is the contract.
