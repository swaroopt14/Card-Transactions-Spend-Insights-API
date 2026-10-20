# Card Transactions & Spend Insights API

A Java 17 and Spring Boot 3.3.5 REST API for card management, transaction recording, JWT login, explainable fraud flags, and monthly spend by category.

This is a backend only. Use Swagger, Postman, or curl. There is no frontend.

## Architecture

```
Client → JwtAuthFilter → Controller → Service → Repository → H2 or PostgreSQL
```

| Package | Job |
|---|---|
| `domain` | `AppUser`, `Card`, `CardTransaction`, and the enums |
| `repository` | Spring Data JPA queries, including the monthly `GROUP BY` |
| `service` | Registration, ownership, fraud rules, history, insights |
| `web` | REST controllers, DTOs, one error handler |
| `security` | JWT create/parse, the filter, the access rules |
| `config` | Swagger bearer auth, optional admin seed |

Request path for a purchase:

`POST /api/v1/cards/{id}/transactions` → JWT filter → `CardController` → `TransactionService` → `FraudRuleEngine` → `CardTransactionRepository` → JSON

`FILE_BY_FILE_GUIDE.md` explains every class. `TEST_REPORT.md` has the commands that were actually run.

## Prerequisites

- JDK 17. On this machine Maven may pick up Java 24 unless you set `JAVA_HOME`.
- Maven 3.9+. There is no `mvnw`.
- Optional: Docker, for PostgreSQL.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
java -version
mvn -version
```

## Run locally (H2)

```bash
mvn spring-boot:run
```

The API listens on port 8080. Hibernate creates or updates the schema (`ddl-auto: update`). That is a development setting. Swagger UI: http://localhost:8080/swagger-ui.html

## Environment variables

| Variable | Purpose | Local default |
|---|---|---|
| `DB_URL` | JDBC URL | In-memory H2 |
| `DB_USER` | Database user | `sa` |
| `DB_PASSWORD` | Database password | empty |
| `JWT_SECRET` | HMAC secret, at least 32 characters | A dev-only default. Set this outside your laptop. |
| `ADMIN_EMAIL` | Optional admin email | unset, so no admin is created |
| `ADMIN_PASSWORD` | Optional admin password | unset |
| `POSTGRES_PASSWORD` | Used by Docker Compose only | `localdevonly` |

Do not commit real secrets. The defaults in `application.yml` and `docker-compose.yml` are for local development.

## Tests

```bash
mvn clean test
mvn verify
```

The suite uses H2, not PostgreSQL. Latest local result: 30 tests, 0 failures (`TEST_REPORT.md`).

## Docker

```bash
docker compose up --build
```

Compose starts PostgreSQL 16, waits until `pg_isready` succeeds, then starts the API as a non-root user. This session validated the Compose file with `docker compose config` and did not build or run the image.

## Authentication

`POST /api/v1/auth/register` and `POST /api/v1/auth/login` are public. Everything else needs `Authorization: Bearer <token>`.

Registration always creates a `USER`. Sending `"role": "ADMIN"` does not change that. An admin exists only when `ADMIN_EMAIL` and `ADMIN_PASSWORD` are set at startup, and the password is stored as a BCrypt hash.

Send the token from Swagger's Authorize button or the `Authorization` header. Tokens in the default profile last 60 minutes. The test profile uses 5 minutes.

## API

| Method | Path | Auth | Success |
|---|---|---|---|
| POST | `/api/v1/auth/register` | public | 201 token |
| POST | `/api/v1/auth/login` | public | 200 token |
| POST | `/api/v1/cards` | user | 201 card |
| GET | `/api/v1/cards` | user | 200 own cards |
| PATCH | `/api/v1/cards/{id}/block` | owner | 200 |
| PATCH | `/api/v1/cards/{id}/unblock` | owner | 200 |
| POST | `/api/v1/cards/{id}/transactions` | owner | 201, maybe flagged |
| GET | `/api/v1/cards/{id}/transactions?page=0&size=20` | owner | 200 page |
| GET | `/api/v1/insights/monthly?month=2026-10` | user | 200 totals |
| GET | `/api/v1/admin/flagged-transactions` | ADMIN | 200 flagged rows |

### Register

```bash
curl -s -X POST localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"Passw0rd!"}'
```

```json
{"token":"<jwt>","tokenType":"Bearer","expiresInSeconds":3600}
```

A second register with the same email returns 409:

```json
{"status":409,"error":"Conflict","message":"Email already registered","timestamp":"2026-10-20T15:30:00Z"}
```

### Card and transaction

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"Passw0rd!"}' | jq -r .token)

curl -s -X POST localhost:8080/api/v1/cards \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"last4":"4321","nickname":"Gold","creditLimit":40000}'

curl -s -X POST localhost:8080/api/v1/cards/1/transactions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"amount":250.50,"merchant":"Cafe","category":"DINING"}'
```

If `occurredAt` is omitted, the server uses the current UTC instant. A timestamp more than 5 minutes in the future is 400. A timestamp equal to that 5-minute skew is accepted. Past timestamps are accepted.

A blocked card returns 422 with message `Card is blocked`. Another user's card id returns 404.

### History page

```json
{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0}
```

`page` is zero-based. `size` must be at least 1. A size above 100 is stored as 100 in the response. Rows are newest first.

### Monthly insights

`GET /api/v1/insights/monthly?month=2026-10`

The window is half-open in UTC: from `2026-10-01T00:00:00Z` inclusive to `2026-11-01T00:00:00Z` exclusive. Flagged transactions are included. The total is a database `SUM`/`COUNT`, grouped by category, ordered by total descending. Another user's rows are not included. An empty month returns `"grandTotal": 0` and `"categories": []`. `month=2026-13` returns 400.

### Fraud rules

Flags are recorded. They do not decline the purchase. Declining a blocked card is a separate rule.

| Rule | Fires when |
|---|---|
| `HIGH_AMOUNT` | amount is strictly greater than `app.fraud.high-amount-threshold` (50000). 50000.00 is clean. |
| `OVER_LIMIT` | amount is strictly greater than the card's credit limit. An equal amount is clean. |
| `VELOCITY` | the number of already-saved transactions in `[occurredAt - window, occurredAt]` is `>= max-txn-per-minute`. Default window is 60 seconds, default count is 5. The new row is not counted yet, so the 6th transaction in that window is the first flagged one. |

Both ends of the velocity window are inclusive. A transaction 61 seconds earlier is outside it. The card row is locked for the duration of the insert so two overlapping requests on the same card cannot both miss the count.

### Status codes

| Code | When |
|---|---|
| 200 | Login, reads, block, unblock |
| 201 | Register, create card, create transaction |
| 400 | Validation, bad JSON, bad month, bad page, far-future timestamp |
| 401 | Missing, expired, or invalid token, or bad login |
| 403 | Authenticated, wrong role |
| 404 | Missing card, or a card the caller does not own |
| 409 | Email already registered |
| 422 | Business rule, such as a blocked card |

## Known limitations

- Hibernate updates the schema automatically. Production should use Flyway or an equivalent migration tool.
- A successful client retry creates a second transaction. There is no idempotency key. The server does not retry the insert on its own.
- Email lookup is case-sensitive.
- The admin flagged list returns every flagged row and is not paged.
- Tests prove the H2 behavior. They do not prove PostgreSQL or the Docker image.
- Storing only `last4` is a safer design choice. It is not a PCI DSS certification.
