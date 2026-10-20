# Project Plan: Card Transactions & Spend Insights API
*For: Amex Apprentice – Enterprise Technology Services (Job ID 26009713, apply before 27 Oct 2026, 11 PM IST)*

**Why this project:** It hits the Amex ETS stack directly: Java, Spring Boot, REST API specs, SQL, JWT, testing, Git and Agile. It's a cards/payments domain, and it doesn't overlap with your reconciliation project.

> Starter status: the skeleton compiled and the original 8 tests passed. The follow-up pass filled the contract gaps (409 on duplicate email, timestamp policy, stable history page, fraud boundaries, JSON 401/403) and the suite is now 30 tests, 0 failures, on JDK 17. See `TEST_REPORT.md` and `FILE_BY_FILE_GUIDE.md`. Don't recite this file. Re-run `mvn test` and walk the two flows out loud.

---
## 1. Features
| # | Feature | Notes |
|---|---|---|
| F1 | Register / login → JWT | BCrypt password hashing, 60-min token, `role` claim |
| F2 | Roles USER / ADMIN | `/api/v1/admin/**` is ADMIN only. The admin is seeded from env vars |
| F3 | Cards CRUD-lite | Add a card (last 4 digits only), list my cards, block/unblock |
| F4 | Record transactions | Amount, merchant, category, time. A blocked card is rejected with 422 |
| F5 | Fraud-flag rules | HIGH_AMOUNT (> ₹50,000), OVER_LIMIT (> the card's credit limit), VELOCITY (≥ 5 txns/min) |
| F6 | Monthly spend insights | `GROUP BY category` via a JPQL aggregation, plus the grand total |
| F7 | Paginated transaction history | `?page=&size=`, max size 100 |
| F8 | Validation + uniform errors | Bean Validation and `@RestControllerAdvice` return a JSON error body |
| F9 | API docs | Swagger UI with a Bearer auth button |
| F10 | Docker + CI | Multi-stage Dockerfile, compose with Postgres 16, GitHub Actions running `mvn verify` |
| *Stretch* | Flyway migrations, a spend-budget alert, Testcontainers, `@PreAuthorize`, a CSV export | Only if time allows |

## 2. API endpoints
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/auth/register` | public | Create a user and return a JWT (201) |
| POST | `/api/v1/auth/login` | public | Return a JWT, or 401 |
| POST | `/api/v1/cards` | USER | Add a card (201) |
| GET | `/api/v1/cards` | USER | List my cards |
| PATCH | `/api/v1/cards/{id}/block` | USER (owner) | Block a card |
| PATCH | `/api/v1/cards/{id}/unblock` | USER (owner) | Unblock a card |
| POST | `/api/v1/cards/{id}/transactions` | USER (owner) | Record a transaction. Fraud rules run here (201, or 422 if blocked) |
| GET | `/api/v1/cards/{id}/transactions?page=0&size=20` | USER (owner) | Paginated history, newest first |
| GET | `/api/v1/insights/monthly?month=2026-10` | USER | Spend by category for that month |
| GET | `/api/v1/admin/flagged-transactions` | ADMIN | All flagged transactions |
| GET | `/swagger-ui.html`, `/v3/api-docs` | public | API docs |

Status codes: 400 for validation errors, a bad month, a bad page, or a timestamp more than 5 minutes in the future. 401 for no or invalid token, or a bad login. 403 for the wrong role. 404 for a missing card or one you don't own. 409 when the email is already registered. 422 for a business-rule violation such as a blocked card.

## 3. DB schema (Hibernate generates it; the equivalent SQL is below)
```sql
CREATE TABLE app_users (
  id BIGSERIAL PRIMARY KEY,
  email VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(16) NOT NULL,           -- USER | ADMIN
  created_at TIMESTAMP
);
CREATE TABLE cards (
  id BIGSERIAL PRIMARY KEY,
  owner_id BIGINT NOT NULL REFERENCES app_users(id),
  last4 CHAR(4) NOT NULL,              -- never store a full PAN
  nickname VARCHAR(255) NOT NULL,
  credit_limit NUMERIC(14,2) NOT NULL,
  status VARCHAR(16) NOT NULL          -- ACTIVE | BLOCKED
);
CREATE TABLE card_transactions (
  id BIGSERIAL PRIMARY KEY,
  card_id BIGINT NOT NULL REFERENCES cards(id),
  amount NUMERIC(14,2) NOT NULL,
  merchant VARCHAR(255) NOT NULL,
  category VARCHAR(32) NOT NULL,       -- GROCERIES|DINING|TRAVEL|SHOPPING|UTILITIES|ENTERTAINMENT|OTHER
  occurred_at TIMESTAMP NOT NULL,
  flagged BOOLEAN,
  flag_reason VARCHAR(255)
);
CREATE INDEX idx_txn_card_time ON card_transactions(card_id, occurred_at);
```
Relationships: one user has many cards, and one card has many transactions. The `(card_id, occurred_at)` index serves both the velocity check and the history query.

## 4. Tech choices (and the one-line "why" for interviews)
| Choice | Why |
|---|---|
| Java 17 (LTS) | The Spring Boot 3 baseline. Records, `var`, text blocks, switch expressions |
| Spring Boot 3 + Spring Web | Industry-standard REST stack, and the one Amex uses |
| Spring Data JPA (Hibernate) | Repository interfaces with derived queries, plus JPQL for the aggregation |
| PostgreSQL (prod) / H2 (dev/test) | Real SQL in Docker; zero-setup in-memory DB for tests and CI |
| Spring Security + JWT (jjwt 0.12) | Stateless auth that scales horizontally. Role-based access |
| Bean Validation (`jakarta.validation`) | Declarative input checks such as `@NotBlank`, `@Pattern` and `@DecimalMin` |
| JUnit 5 + Mockito + MockMvc + AssertJ | The test pyramid: unit → slice (`@DataJpaTest`, `@WebMvcTest`) → integration |
| springdoc-openapi | The JD says "API specs", and this gives you a live spec plus Swagger UI |
| Docker (multi-stage) + compose | Reproducible run. The final image is JRE-only and runs as non-root |
| GitHub Actions | `mvn verify` on every push/PR, which shows you know CI discipline |

## 5. 4-day build plan (Wed 21 → Sat 24 Oct; buffer Sun–Mon; apply by 27 Oct)
Spend about 5–6 hours a day. Commit small and often, with messages like `feat: add card entity`. That history *is* your Agile/Git evidence.

### Day 1: Java basics + skeleton + entities
- **Learn (2 h), mapped from Go/Python:**
  - Classes vs Go structs. Constructors, `private` fields plus getters (encapsulation).
  - `interface` (like a Go interface, but implemented explicitly with `implements`). `enum`.
  - `record` (≈ a Go struct or Python dataclass, immutable). Used for DTOs.
  - Generics `List<T>`, `Optional<T>` (instead of a nil check). Streams `.stream().map().filter().toList()` ≈ a Python comprehension.
  - `BigDecimal` for money (`compareTo`, not `==`).
  - Checked vs unchecked exceptions. Here we use `RuntimeException` subclasses.
- **Build:** start the project at start.spring.io (Maven, Java 17, with the Web, JPA, Security, Validation, H2 and Postgres dependencies). Write `Role`, `Category`, `CardStatus`, `AppUser`, `Card`, `CardTransaction`, and the 3 repositories.
- **Learn Spring:** what `@SpringBootApplication` does (component scan + auto-config). `@Entity`, `@Id`, `@ManyToOne(fetch = LAZY)`, `@Enumerated(STRING)`.
- **Done when:** the app starts and the H2 console shows the 3 tables. Commit + push.

### Day 2: Services + controllers + validation
- **Learn:** dependency injection through the constructor (no `new` for services; compare it to passing deps into a Go struct). `@Service`, `@RestController`, `@RequestBody`, `@PathVariable`, `@RequestParam`. `@Transactional` and why reads are marked `readOnly`. DTOs vs entities (never return entities).
- **Build:** `CardService`, `TransactionService`, `FraudRuleEngine`, `InsightsService` (with the JPQL `GROUP BY` and the constructor-expression projection), the controllers, `GlobalExceptionHandler`, and the `Dtos` records.
- **Done when:** you can create a card, post transactions and get monthly insights from Postman or curl. Do this temporarily with security permitting everything.

### Day 3: Spring Security + JWT + roles
- **Learn:** the filter chain (a request passes through filters before the controller, like Go middleware). `SecurityFilterChain` bean. `OncePerRequestFilter`. `SecurityContextHolder`. Stateless sessions. Why CSRF is disabled for a pure token API. BCrypt. JWT structure (header.payload.signature, HMAC-SHA256, `exp` claim). Difference between 401 and 403.
- **Build:** `JwtService`, `JwtAuthFilter`, `SecurityConfig`, `AuthService`/`AuthController`, owner-scoped repository methods (`findByIdAndOwnerEmail`), and `AdminSeeder`. Add the OpenAPI config with a Bearer scheme.
- **Done when:** calls without a token get 401, a USER calling an admin endpoint gets 403, and the Swagger "Authorize" button works.

### Day 4: Tests + Docker + CI + README
- **Learn:**
  - `@Test`, `@BeforeEach`, AssertJ `assertThat`.
  - Mockito `@Mock` and `when(..).thenReturn(..)`.
  - Slice tests: `@DataJpaTest` loads only JPA, and `@WebMvcTest` loads only the web layer.
  - `@MockBean`, `@WithMockUser`, and `@SpringBootTest` + MockMvc.
  - The Maven lifecycle (`compile` → `test` → `package` → `verify`).
- **Build:**
  - Write at least one test per layer. Add edge cases: a blocked card returns 422, someone else's card returns 404, a bad month format returns 400.
  - Add the Dockerfile, docker-compose and `.github/workflows/ci.yml`.
  - Write the README with curl examples and a Swagger screenshot.
- **Done when:** `mvn verify` is green locally *and* the CI badge is green on GitHub, and `docker compose up` works.
- **Buffer (Sun–Mon):** stretch features (Flyway, a budget alert), polishing the README, and practising the walkthrough out loud. Add the resume line, then apply.

## 6. Interview talking points (only what you can defend)
1. **Architecture:** a layered controller → service → repository design. DTOs at the boundary keep entities and the JPA lazy-loading behaviour out of JSON. The design is stateless, so it can scale horizontally behind a load balancer.
2. **Request lifecycle with security:**
   - The request reaches `JwtAuthFilter`, which verifies the signature and expiry.
   - It then sets the `Authentication` (email + `ROLE_x`).
   - Next come the URL authorization rules, then the controller.
   - The controller gets the user from `Authentication.getName()`.
3. **401 vs 403:** a 401 means "who are you?" (no or invalid token); a 403 means "I know you, but you're not allowed" (a USER on an admin endpoint).
4. **IDOR prevention:** every card lookup is `findByIdAndOwnerEmail`, so someone else's card ID returns 404. A 404 rather than a 403 doesn't leak that the card exists.
5. **Money handling:** `BigDecimal` with `NUMERIC(14,2)`, because `double` has rounding errors. Compare with `compareTo`.
6. **PCI-awareness:** the app stores only the last 4 digits and never the full PAN or CVV. (Say "PCI-aware design choice", not "PCI-compliant".)
7. **Fraud rules:**
   - The rules are deterministic and explainable: HIGH_AMOUNT, OVER_LIMIT and VELOCITY.
   - They're configurable via `application.yml`.
   - Flagged transactions are *recorded and flagged*, not blocked, which is a soft-decline design.
   - A next step would be async scoring via Kafka, which ties to your existing Kafka experience.
8. **SQL:**
   - The monthly insight is a `GROUP BY category` with `SUM`/`COUNT` over a half-open date range `[from, to)`.
   - The composite index `(card_id, occurred_at)` serves the velocity count and the history query.
   - Know how to write the same query in raw SQL.
9. **Transactions:** `@Transactional` on writes, so the save and the fraud flag are atomic. `readOnly = true` on reads.
10. **Testing pyramid:** a domain unit test, a Mockito unit test (the rule engine has no Spring in it, so it's fast), and slice tests (`@DataJpaTest`, `@WebMvcTest` with security). Then one `@SpringBootTest` covering the end-to-end flow. CI runs them on every push.
11. **Go/Python comparison** (shows you can learn fast):
    - Spring DI ≈ wiring dependencies into Go structs by hand.
    - Filters ≈ Go/FastAPI middleware.
    - Bean Validation ≈ Pydantic.
    - Spring Data repositories ≈ an ORM-generated query layer.
12. **What I'd improve:**
    - Flyway migrations instead of `ddl-auto`
    - Refresh tokens
    - Rate limiting
    - Testcontainers with real Postgres
    - Idempotency keys on transaction POST
    - Kafka events for flagged transactions

Prep for likely questions: What is a Spring Bean, and what are its scopes? `@Component` vs `@Service` vs `@Repository`? What's the N+1 problem, and what does LAZY loading do? What are the HashMap internals? Explain OOP's 4 pillars using classes from this project. What does `equals`/`hashCode` mean for entities?

## 8. Completed in the verification pass, and what is still open

Done and covered by the 30 tests in `TEST_REPORT.md`:

- Register, login, BCrypt, JWT subject and role, expired and forged tokens.
- Duplicate email returns 409. A public register cannot grant ADMIN.
- Cards, owner scoping, block and unblock, 422 on a blocked card.
- Fraud boundaries for HIGH_AMOUNT, OVER_LIMIT, and VELOCITY, including the inclusive 60-second window.
- History page with a stable `TransactionPage`, newest first, size capped at 100.
- Monthly half-open UTC aggregation, empty month, invalid month, other users excluded, flagged rows included.
- Admin route: 401 anonymous, 403 user, 200 admin, unflagged rows omitted. Admin seed comes from env vars.

Still open, on purpose:

- Flyway, refresh tokens, rate limiting, a required idempotency key, Testcontainers against real PostgreSQL, Kafka for flagged events. An optional `Idempotency-Key` is already honored.
- Docker image and PostgreSQL were not started here. `docker compose config` only parsed the file.
- A finished GitHub Actions run had not been observed when this section was written.

## 7. Resume bullets (use ONLY after you've built and pushed it, with the GitHub link)
**Card Transactions & Spend Insights API** | Java 17, Spring Boot 3, Spring Security (JWT), JPA, PostgreSQL, JUnit/Mockito, Docker, GitHub Actions | github.com/<your-handle>/spend-insights-api
- Built a layered Spring Boot REST API for card management, transaction recording and monthly category-wise spend insights. Uses JPA/PostgreSQL with JPQL aggregation queries, Bean Validation and an OpenAPI/Swagger spec.
- Implemented stateless JWT authentication with BCrypt and role-based access (USER/ADMIN), plus owner-scoped data access. Added configurable rule-based fraud flags (high-amount, over-limit, velocity).
- Wrote unit, slice and integration tests (JUnit 5, Mockito, MockMvc), containerized the app with Docker Compose, and set up a GitHub Actions CI pipeline running the test suite on every push.

> Don't add numbers such as users, latency or coverage % unless you actually measured them. If you run `jacoco` and get a real coverage figure, you may add it. After it's pushed, add **Java, Spring Boot, JUnit** to the Skills line.
