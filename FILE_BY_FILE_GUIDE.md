# File-by-file guide

Package root: `com.swaroop.spendinsights`. Read this after you have run the app once. The two flows to trace first are card create (`CardController` → `CardService` → `CardRepository`) and transaction create (`TransactionService` → `FraudRuleEngine` → `CardTransactionRepository`).

## SpendInsightsApplication.java

1. Starts the process.
2. Java needs a `main` method. Spring Boot needs that method to build the application context and start Tomcat.
3. `main` calls `SpringApplication.run`.
4. `@SpringBootApplication` turns on auto-configuration and component scanning. `UserDetailsServiceAutoConfiguration` is excluded so Boot does not invent a default username and password. This API uses JWT, not form login.
5. Boot scans this package, creates the beans, and opens port 8080.
6. Covered indirectly by every `@SpringBootTest`.
7. Interview: why Spring Boot? It configures the web server, JPA, and security from the dependencies and `application.yml`, instead of wiring each of those by hand.

## domain/AppUser.java

1. One registered person.
2. JPA needs a class to map onto `app_users`.
3. Constructor sets email, password hash, and role. Getters expose `id`, `email`, `passwordHash`, `role`, `createdAt`. There is no setter for the password, so callers pass the hash into the constructor.
4. `@Entity`, `@Table(name = "app_users")`, `@Id`, `@GeneratedValue`, unique email column, `@Enumerated(EnumType.STRING)` on `role`.
5. `AuthService` and `AdminSeeder` construct it. `Card.owner` points at it.
6. `ApiAcceptanceTest.registrationReturnsTokenAndStoresBcryptHash`, `AdminSeederTest`.
7. Interview: why not return this entity from the API? The hash would leak, and Jackson can trip over lazy relations. DTOs are the response.

## domain/Card.java

1. A card owned by one user. Only `last4` is stored.
2. The product must not keep a full card number.
3. Constructor sets owner, last4, nickname, and credit limit. `setStatus` blocks or unblocks. Getters expose the fields. Status defaults to `ACTIVE`.
4. `@ManyToOne(fetch = LAZY)` and `@JoinColumn(name = "owner_id")`. Money is `BigDecimal` with precision 14 scale 2.
5. `CardService` saves it. `CardTransaction.card` points at it.
6. Card sections of `ApiAcceptanceTest` and `CardControllerWebTest`.
7. Interview: why `BigDecimal`? `double` cannot represent 0.10 exactly. Money uses `compareTo`, not `==`.

## domain/CardTransaction.java

1. One purchase.
2. History, fraud flags, and the monthly report all read this table.
3. Constructor sets card, amount, merchant, category, and time. `flag(reason)` sets `flagged` and `flagReason`. `applyClearing` stores the synchronous decision plus time delta, place change, and high-risk category. Getters expose the columns.
4. `@Index` on `(card_id, occurred_at)` supports history and the velocity count. Category is stored as a string enum.
5. `TransactionService` builds it, then the repository saves it.
6. `CardTransactionTest.flagSetsFlaggedAndReason`.
7. Interview: what does `flag` not do? It does not decline the purchase. The blocked-card check is a different decision and happens before save.

## domain/Role.java, Category.java, CardStatus.java

1. Closed sets of values: `USER`/`ADMIN`, seven spend categories, `ACTIVE`/`BLOCKED`.
2. A string column would accept typos. An enum will not.
3. No methods. The constants are the API.
4. Stored with `@Enumerated(EnumType.STRING)`, so the database contains `DINING`, not `1`. Reordering the enum does not corrupt old rows.
5. Registration forces `Role.USER`. Transaction requests bind `Category` from JSON.
6. Invalid category JSON is rejected in `recordsFraudFlagsAndRejectsBadTransactionInput`.
7. Interview: why `STRING` and not `ORDINAL`? Ordinal stores the position. Inserting a new category in the middle would change the meaning of old rows.

## repository/AppUserRepository.java

1. User lookup.
2. Services should not write SQL for `find` and `exists`.
3. `findByEmail` returns `Optional`. `existsByEmail` is the duplicate check. Inherited `save` inserts.
4. `extends JpaRepository<AppUser, Long>`. Spring Data derives the method names.
5. `AuthService.register` and `login`, `AdminSeeder`.
6. Registration and seeder tests.
7. Interview: why `Optional`? A missing user is a normal result. The caller decides between 401 and 404 instead of catching a null.

## repository/CardRepository.java

1. Card lookup scoped to the owner, plus a row lock.
2. `findById` alone would allow one user to open another user's card by guessing the id (IDOR).
3. `findByOwnerEmail` lists one user's cards. `findByIdAndOwnerEmail` returns a card only when both match. `findByIdForUpdate` is a `SELECT ... FOR UPDATE` used while recording a transaction.
4. `@Lock(PESSIMISTIC_WRITE)` on the update query.
5. `CardService` calls these. The lock is held only while `TransactionService.record` has a transaction open.
6. IDOR cases in `ApiAcceptanceTest.userManagesOnlyTheirOwnCards`.
7. Interview: why 404 instead of 403 when the card exists but is not yours? A 403 tells the caller the id is real. A 404 does not.

## repository/CardTransactionRepository.java

1. History, velocity count, admin queue, monthly aggregation.
2. Summing in Java would load every transaction. The database should group.
3. `findByCardIdOrderByOccurredAtDesc` is the history page. `countByCardIdAndOccurredAtBetween` is inclusive on both instants. `findByFlaggedTrueOrderByOccurredAtDesc` is the admin list. `monthlySpendByCategory` runs the JPQL below.
4. `@Query` with a constructor expression `select new CategorySpend(...)`.
5. `TransactionService`, `FraudRuleEngine`, `InsightsService`.
6. `CardTransactionRepositoryTest` covers the month window, owner isolation, flagged inclusion, the velocity count, and flagged sorting.
7. Interview: write the report in SQL. `SELECT category, SUM(amount), COUNT(*) FROM card_transactions t JOIN cards c ON ... JOIN app_users u ON ... WHERE u.email = ? AND occurred_at >= ? AND occurred_at < ? GROUP BY category ORDER BY SUM(amount) DESC`. The JPQL uses `t.card.owner.email` instead of naming the tables.

## repository/CategorySpend.java

1. One grouped row: category, total, count.
2. JPQL can construct a record directly, so the repository does not return `Object[]`.
3. The record components are `category`, `total`, and `count`.
4. No JPA annotations. It is not an entity.
5. `monthlySpendByCategory` constructs it. `InsightsService` maps it to `CategoryTotal`.
6. Repository aggregation test.
7. Interview: what is a projection? A small type that carries query output without being a table.

## service/AuthService.java

1. Register and login.
2. Controllers should not hash passwords or sign tokens.
3. `register` rejects a duplicate email with `ConflictException` (409), hashes with BCrypt, saves a `USER`, and returns a token. A unique-constraint race becomes the same 409. `login` finds the user and checks the password with `encoder.matches`. Failure is `BadCredentialsException` (401). The private `token` method builds `TokenResponse` and never includes the hash.
4. `@Service`, `@Transactional` on `register`.
5. `AuthController` → this class → `AppUserRepository`, `PasswordEncoder`, `JwtService`.
6. Registration, duplicate, role, and login tests.
7. Interview: why constructor injection? The dependencies are visible, final, and easy to replace in a unit test. `new` inside the method would hide them.

## service/CardService.java

1. Create, list, and block or unblock cards.
2. Ownership has to live in one place so every card operation uses it.
3. `create` loads the user and saves the card. `list` returns that user's cards. `setStatus` loads the owned card and changes status. `getOwned` is the IDOR check. `getOwnedForUpdate` does that check and then locks the row. `toDto` copies safe fields.
4. `@Transactional` on writes, `readOnly = true` on the list.
5. `CardController` and `TransactionService`.
6. Card and block tests.
7. Interview: where is the ownership check? In `findByIdAndOwnerEmail`, not in the controller. A new endpoint that forgets the controller check still has to go through the service to load a card.

## service/FraudRuleEngine.java

1. Decides which rule names apply.
2. Rules change more often than HTTP code, and they must be testable without Tomcat.
3. `evaluate(...)` returns `FraudAssessment` with reasons, `riskScore`, and `stepUpSuggested`. Order is `HIGH_AMOUNT`, `OVER_LIMIT`, `OVER_BALANCE`, `VELOCITY`, `GEO_VELOCITY`, `REPEAT_RISK`, `RISK_COHORT`, `STEP_UP`.
4. `@Component`. Thresholds come from `@Value`.
5. `TransactionService.record` calls it after the card is locked and before `save`.
6. `FraudRuleEngineTest` (no web server) and the HTTP fraud test.
7. Interview: does a flag decline the transaction? No. The row is saved with `flagged=true` and `flagReason`. Only a blocked card is rejected, and that happens before this method.

Rule boundaries:

- `HIGH_AMOUNT`: `amount > high-amount-threshold`. Equal is clean.
- `OVER_LIMIT`: `amount > card.creditLimit`. Equal is clean.
- `OVER_BALANCE`: saved total on that card plus this amount is `> card.creditLimit`. One purchase equal to the limit is clean. The next one that crosses the running total is flagged. The row is still saved.
- `VELOCITY`: count of rows already in `[at - velocity-window-seconds, at]` is `>= max-txn-per-minute`. Default window 60 seconds, default count 5. The current purchase is not in the count yet.
- `GEO_VELOCITY`: a named place differs from another named place on that card inside `geo-window-minutes`. A blank place skips the rule. The row is still saved.
- `REPEAT_RISK`: a flagged row already exists inside `repeat-flag-hours`, and this amount is greater than `high-amount-threshold * repeat-limit-factor`. Default line is 10000.
- `RISK_COHORT`: prior flag in the window, and the category is in `high-risk-categories` (default `ENTERTAINMENT,OTHER`).
- `STEP_UP`: prior flag in the window, and amount is greater than `step-up-amount` (default 5000). The weight counts toward `riskScore`. A score of 70 or more holds the purchase until the step-up code matches.
- Card create also writes `accountReferenceId` (UUID) and `cardToken` (`SHA-256` of pepper, owner email, card id, last4, reference). Never a PAN or CVV.

## service/TransactionService.java

1. Record a purchase and read history.
2. This is the use-case the interview will ask you to trace.
3. `record` applies the timestamp policy, locks the owned card, rejects a blocked card, evaluates fraud, and stores the clearing decision. A score under 70 saves the row, including a flag. A score of 70 or more opens a step-up challenge and does not save the row until `verifyStepUp` gets the matching code. After a successful save it publishes `PurchaseCleared`. `list` validates `page >= 0` and `size >= 1`, caps size at 100, checks ownership, and returns `TransactionPage`. `flagged` returns flagged rows for the admin controller. `toDto` maps the entity. The decision is `STEP_UP` when that rule fired, otherwise `FLAGGED` when any rule fired, otherwise `AUTHORIZED`.
4. `@Transactional` on `record`. The lock, the purchase insert, and the idempotency row commit or roll back together. There is no server retry loop. A failed request does not store the key, so the client may send that same key again. A second POST with no key does create a second row. The same key and the same body return the original row with 200. The same key and a different body return 409.
5. `CardController` and `AdminController`.
6. `ApiAcceptanceTest` transaction, timestamp, velocity, history, and idempotency tests.
7. Interview: walk the path. `RequestIdFilter` sets `X-Request-Id`. `IdempotencyKeyFilter` echoes `Idempotency-Key` when the client sent one. The JWT filter checks the token. The controller reads `auth.getName()`. The service checks a prior key, then the owner, then the engine. The repository inserts. A new purchase returns 201. The key is not in the JSON.

Timestamp policy: null `occurredAt` becomes `Instant.now()` in UTC. A value more than 5 minutes ahead is 400. Five minutes or less ahead is accepted. Any past instant is accepted.

## service/BehavioralLoop.java

1. Write the after-commit copy of a purchase.
2. The purchase must already be committed before this row exists. This class does not authorize, decline, or call a card network.
3. `onCleared` listens for `PurchaseCleared` with `AFTER_COMMIT`, then inserts one `BehavioralSignal` in a new transaction. A second event for the same purchase is ignored. A failure is logged and does not change the HTTP response.
4. The listener is a Spring bean. The insert uses `REQUIRES_NEW`, so it is not part of the purchase transaction.
5. `TransactionService` publishes the event. `BehavioralSignalRepository` stores the row.
6. `secondPurchaseKeepsTheRowAndStoresClearingFactsAfterCommit`.
7. Interview: why not write this row inside `record`? The ledger decision and the review copy are different jobs. If the review insert fails, the purchase that already committed stays committed.

## service/InsightsService.java

1. Monthly spend for the logged-in user.
2. The controller should not know how to compute a month window.
3. `monthly(email, month)` builds `[start of month, start of next month)` in UTC, calls the aggregation, maps rows to `CategoryTotal`, and adds the grand total with `BigDecimal`.
4. `@Transactional(readOnly = true)`.
5. `InsightsController`.
6. `monthlyInsightsUseAHalfOpenUtcWindowAndIgnoreOtherUsers` and the repository window test.
7. Interview: why not `t.occurredAt <= endOfMonth`? The end instant would be included twice if you also include the next month's start, and the last nanosecond is easy to miss. A half-open range has one comparison: `>= from AND < to`.

## service/NotFoundException.java, ConflictException.java, BadRequestException.java, BusinessRuleException.java

1. Named failures for 404, 409, 400, and 422.
2. The service should not build HTTP responses. It throws, and one handler maps them.
3. Each class has a constructor that takes a message.
4. They extend `RuntimeException`, so they are unchecked.
5. Services throw them. `GlobalExceptionHandler` catches them.
6. The HTTP tests assert the status codes.
7. Interview: 401 vs 403 vs 404? 401 means the token is missing or bad. 403 means the token is valid and the role is wrong. 404 means the resource is missing or is not yours.

## web/AuthController.java

1. `POST /api/v1/auth/register` and `POST /api/v1/auth/login`.
2. Thin HTTP adapter.
3. `register` returns 201. `login` returns 200. Both take `@Valid` bodies.
4. `@RestController`, `@RequestMapping`, `@PostMapping`, `@ResponseStatus`.
5. Calls `AuthService`.
6. Auth tests in `ApiAcceptanceTest`.
7. Interview: what does `@Valid` do? It runs the Bean Validation annotations on the record before the method body. A bad email never reaches the hasher.

## web/CardController.java

1. Cards and transactions for the logged-in user.
2. One resource family, one controller.
3. `create`, `list`, `block`, `unblock`, `addTxn`, `listTxns`. Each reads the email from `Authentication.getName()`.
4. `@PathVariable`, `@RequestParam(defaultValue = "0")` for page and `"20"` for size, `@ResponseStatus(CREATED)` on create.
5. `CardService` and `TransactionService`.
6. `CardControllerWebTest` and the acceptance tests.
7. Interview: where does the email come from? The JWT subject, copied into the security context by `JwtAuthFilter`. The client does not send their email on these routes.

## web/InsightsController.java

1. `GET /api/v1/insights/monthly?month=2026-10`.
2. Parsing `YearMonth` is HTTP-level. The math is in the service.
3. `monthly` parses the query string and calls `InsightsService`.
4. `@RequestParam String month`. `YearMonth.parse` throws `DateTimeParseException` on a bad value, which the handler turns into 400.
5. `InsightsService`.
6. Insights tests, including a missing `month` parameter.
7. Interview: why pass `YearMonth` and not two instants from the client? The server owns the timezone policy (UTC) and the half-open window. Clients only send `YYYY-MM`.

## web/AdminController.java

1. `GET /api/v1/admin/flagged-transactions`.
2. Admins need a queue of flagged purchases.
3. `flagged` delegates to `TransactionService.flagged`.
4. `@RestController`. There is no role annotation on the method. `SecurityConfig` requires `ROLE_ADMIN` for `/api/v1/admin/**` before the method runs.
5. `TransactionService`.
6. `adminSeesFlaggedTransactionsAndUsersDoNot`, `AdminSeederTest`.
7. Interview: why is the role check not inside the controller? A forgotten `if` in one method would be a hole. The filter chain rejects the request first, so the method is never called.

## web/GlobalExceptionHandler.java

1. Turns exceptions into one JSON shape: `status`, `error`, `message`, `timestamp`.
2. Without this, each controller would have its own try/catch, and Spring's default error can include a stack trace.
3. `notFound` → 404. `rule` → 422. `conflict` → 409. `badRequest` → 400. `creds` → 401. `date` → 400 for a bad month. `invalid` → 400 with field messages. `unreadable` → 400 for bad JSON or a bad enum. `typeMismatch` → 400 for `page=nope`. `missing` → 400 when `month` is absent. `build` fills `ErrorResponse`.
4. `@RestControllerAdvice`, `@ExceptionHandler`.
5. Any controller can throw. This class is in the same application.
6. Acceptance tests assert messages such as `Email already registered` and `Card is blocked`.
7. Interview: why one handler? Clients can branch on `status` and show `message`. Stack traces, SQL, and secrets stay in the server log. `server.error.include-stacktrace` is `never` as a second line of defense.

## web/dto/Dtos.java

1. The request and response records.
2. Entities are a database shape. DTOs are the API contract, with validation.
3. `RegisterRequest` email and password (8 to 72 characters, BCrypt's practical limit). `LoginRequest`. `TokenResponse`. `CreateCardRequest` (`last4` is exactly four digits, nickname required, credit limit at least 1000.00). `CardResponse`. `CreateTransactionRequest` (amount at least 0.01, at most 2 decimal places, merchant, category, optional `occurredAt`). `TransactionResponse`. `TransactionPage`. `CategoryTotal`. `MonthlySpendResponse`. `ErrorResponse`.
4. `@NotBlank`, `@Email`, `@Size`, `@Pattern`, `@NotNull`, `@DecimalMin`, `@Digits`, and `@Valid` on the controller argument.
5. Controllers accept requests and return responses. Services map entities with `toDto`.
6. Validation test in `CardControllerWebTest` and the bad-body cases in `ApiAcceptanceTest`.
7. Interview: what is a DTO? A data carrier for one crossing of the HTTP boundary. Records are immutable and give you `email()` style accessors.

## security/JwtService.java

1. Creates and checks JWTs.
2. Signing details should not be copied into the filter and the auth service.
3. Constructor reads the secret and the lifetime. `generate(email, role)` sets subject, a `role` claim, issued-at, and expiration, then signs with HMAC. `parse` verifies the signature and the expiry. `getExpirySeconds` fills `TokenResponse`.
4. `@Service`. jjwt `signWith` / `parseSignedClaims`.
5. `AuthService` generates. `JwtAuthFilter` parses.
6. Acceptance tests parse the role claim and send expired and forged tokens.
7. Interview: what makes the token trustworthy? The signature. Anyone can read the payload. Only a holder of `JWT_SECRET` can produce a signature this server accepts. An expired token fails in `parse`.

## security/JwtAuthFilter.java

1. Runs once per request, before the controller.
2. Spring Security does not understand this project's JWT until something converts it into an `Authentication`.
3. `doFilterInternal` reads `Authorization`. If it starts with `Bearer `, it parses the token. A blank subject or a role other than `USER` or `ADMIN` clears the context. A valid token becomes a principal whose name is the email and whose authority is `ROLE_USER` or `ROLE_ADMIN`. `JwtException` also clears the context. The chain always continues. The entry point later returns 401 if the route needs a user.
4. `@Component`, extends `OncePerRequestFilter`.
5. Registered in `SecurityConfig` before `UsernamePasswordAuthenticationFilter`.
6. Missing, expired, forged, and role-less token tests.
7. Interview: why `ROLE_` prefix? `hasRole("ADMIN")` checks the authority `ROLE_ADMIN`. Forgetting the prefix makes every admin look like a user.

## security/SecurityConfig.java

1. Which routes are public, which need a login, and which need ADMIN.
2. Security rules belong in one filter chain, not in scattered `if` statements.
3. `filterChain` disables CSRF because the browser is not sending a session cookie. Sessions are stateless. `/api/v1/auth/**` and the Swagger paths are public. `/api/v1/admin/**` needs `ROLE_ADMIN`. Every other route needs an authenticated user. 401 and 403 write `ErrorResponse` JSON. `passwordEncoder` is BCrypt.
4. `@Configuration`, `@EnableMethodSecurity`, `@Bean`.
5. The filter is inserted with `addFilterBefore`.
6. 401 and 403 tests, plus the web slice which imports this class.
7. Interview: why is CSRF off? CSRF attacks a browser that attaches a cookie automatically. This API expects an `Authorization` header. A malicious site cannot set that header on a cross-site request.

## config/AdminSeeder.java

1. Optional admin account at startup.
2. There is no public "make me admin" API. Operators pass credentials through the environment.
3. `run` saves an `ADMIN` only when both `ADMIN_EMAIL` and `ADMIN_PASSWORD` are non-blank and the email is not already there. The password is BCrypt-hashed.
4. `@Component`, `CommandLineRunner`.
5. Runs after the context is up, uses `AppUserRepository` and `PasswordEncoder`.
6. `AdminSeederTest` sets the two properties and logs in.
7. Interview: why not hardcode the admin? The password would be in git history. Environment variables stay out of the source.

## config/OpenApiConfig.java

1. Swagger title and the Bearer scheme.
2. The job description asks for an API spec. springdoc generates it from the controllers. This bean adds the Authorize button.
3. `api` returns an `OpenAPI` with title, version, and HTTP bearer scheme `bearerAuth`.
4. `@Configuration`, `@Bean`.
5. No runtime call from services. Swagger UI reads `/v3/api-docs`.
6. Not asserted by a test. The bean is loaded in the Spring Boot tests.
7. Interview: is Swagger the security layer? No. It documents the header. `SecurityConfig` enforces it.

## application.yml

1. Defaults for the datasource, JWT lifetime (60 minutes), and fraud thresholds.
2. The same JAR runs on H2 or PostgreSQL by changing environment variables.
3. Not a Java class. `DB_URL`, `DB_USER`, `DB_PASSWORD`, and `JWT_SECRET` override the defaults. `ddl-auto` is `update`. `open-in-view` is false. Error responses hide stack traces.
4. Spring binds `app.jwt.*` and `app.fraud.*` with `@Value`.
5. `JwtService`, `FraudRuleEngine`, and JPA read it.
6. The test file overrides the secret, a 5-minute expiry, and H2 `create-drop`.
7. Interview: why is `open-in-view` false? An open persistence session during JSON rendering hides N+1 queries and makes lazy-load failures accidental. Services load what they need inside the transaction.

## pom.xml

1. Maven build: Java 17, Spring Boot 3.3.5, Web, JPA, Security, Validation, springdoc, jjwt 0.12.6, PostgreSQL, H2, and the test starters.
2. The dependency list is the contract for CI and Docker.
3. No methods. The `spring-boot-maven-plugin` packages the executable JAR.
4. Parent POM manages Spring versions.
5. `mvn test` and `mvn verify` use it.
6. Both commands exited 0. See `TEST_REPORT.md`.
7. Interview: what is the difference between `test` and `verify`? `test` compiles and runs unit tests. `verify` continues through packaging. This project has no Failsafe integration-test module, so the extra work is the JAR.

## Dockerfile and docker-compose.yml

1. A multi-stage image and a two-service local stack.
2. A reviewer should be able to start Postgres and the API with one command.
3. The Docker build stage runs `mvn package` and skips tests. The run stage copies the JAR onto a JRE image and switches to `appuser`. Compose starts Postgres 16, healthchecks with `pg_isready`, and only then starts `api`.
4. No Java annotations.
5. The API container gets `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, and the optional admin variables.
6. `docker compose config` exited 0. The image was not built and the containers were not started.
7. Interview: why multi-stage? The final image does not contain Maven or the source tree. It is smaller, and it does not ship the build toolchain.

## .github/workflows/ci.yml

1. On a push to `main` or a pull request, GitHub Actions checks out the repo, sets up Temurin 17 with Maven caching, and runs `mvn -B verify`.
2. The same command you run locally is the command CI runs.
3. No Java methods.
4. `actions/checkout@v4`, `actions/setup-java@v4`.
5. It builds this project.
6. A finished Actions run was not observed when this guide was written.
7. Interview: what does a green check mean? The project compiled and the tests passed on a clean Ubuntu machine. It does not mean Docker or PostgreSQL were exercised. Those are not in the workflow.

## Test classes

| File | What it proves |
|---|---|
| `CardTransactionTest` | `flag` sets the boolean and the reason |
| `FraudRuleEngineTest` | Each rule, the exact thresholds, and the 60-second window, with Mockito |
| `CardTransactionRepositoryTest` | Aggregation, owner isolation, half-open month, flagged rows included, velocity count, flagged sort |
| `CardControllerWebTest` | Anonymous 401, list JSON, invalid card body. Security is real, services are mocked |
| `SpendInsightsIntegrationTest` | Register, card, clean transaction, flagged transaction, October total, user gets 403 on admin |
| `ApiAcceptanceTest` | The acceptance list: auth, IDOR, block, fraud, time, velocity, pages, insights, admin |
| `AdminSeederTest` | Env-based admin, BCrypt, admin route returns 200 |

Interview: what is the difference between a unit test and an integration test? `FraudRuleEngineTest` does not start Spring. `SpendInsightsIntegrationTest` starts the app, the database, and the filter chain, and calls HTTP.

## What was added so this does not stop at curl

`src/main/resources/static/` is the ledger page. It is public. It never asks for a full card number. It posts the same JSON the API already accepted, and it sends a fresh `Idempotency-Key` on every purchase.

`IdempotencyRecord` plus `IdempotencyRepository` remember one successful purchase for one customer and one key. The row stores the owner email, the key, a SHA-256 of the request, and the transaction id. `TransactionService.record` returns `PostedTransaction`. A repeat is `replayed=true` and the controller answers 200. A different body for that key is 409. `IdempotencyRace` covers two requests inserting the same key together: the loser rolls back and reads the winner. A blocked card, a bad body, or a timestamp too far ahead throws before that row is written, so the key stays unused. The server does not retry.

`IdempotencyKeyFilter` copies `Idempotency-Key` onto the response whenever the client sent one, including 400, 409, and 422. It does not invent a key. The JSON body stays the purchase or the `ErrorResponse`.

`RequestIdFilter` sets `X-Request-Id` and puts it on the log line as `requestId`. That header is a trace id, not the idempotency key.

Interview question: where does the card come from? Not from this service. An issuer creates the card. This system stores the reference the issuer is willing to share: last four, name, limit, status. The page is that intake. It is not a checkout form and it is not PCI evidence.

## How to practise the interview

Say this in your own words, and only the parts you have run:

I built a card transactions and spend insights API with Java 17 and Spring Boot. Users register and log in. Passwords are BCrypt hashes. Sessions are JWTs. Cards store the last four digits. A purchase is checked for a high amount, the card limit, and how many purchases landed in the last minute. Those hits are saved as flags. A blocked card is rejected with 422. An optional `Idempotency-Key` maps to one successful purchase. The same key and body return that purchase with 200. A failed request does not keep the key, and the server does not retry it. The key is echoed as a response header and is not in the JSON. Monthly spend is a JPQL `GROUP BY` over a half-open UTC month, scoped to the logged-in user. Another user's card id returns 404. Admins, and only admins, can list flagged transactions.
