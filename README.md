# Card Transactions & Spend Insights API

A REST API built with Java 17 and Spring Boot 3. Users register and log in with JWT, add cards, and record card transactions. Each transaction is checked against rule-based fraud flags. Users can also get their monthly spend broken down by category. ADMIN users can review every flagged transaction.

**Stack:** Java 17 · Spring Boot 3 (Web, Data JPA, Security, Validation) · JWT (jjwt) · PostgreSQL / H2 · JUnit 5 + Mockito + MockMvc · springdoc OpenAPI · Docker · GitHub Actions

## Run locally
```bash
# Option A - in-memory H2, no setup
mvn spring-boot:run
# Option B - Postgres via Docker
docker compose up --build
```
Swagger UI: http://localhost:8080/swagger-ui.html

To create an admin user, set the `ADMIN_EMAIL` and `ADMIN_PASSWORD` env vars before you start the app.

## Quick try (curl)
```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"Passw0rd!"}' | jq -r .token)
curl -s -X POST localhost:8080/api/v1/cards -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"last4":"4321","nickname":"Gold","creditLimit":40000}'
curl -s -X POST localhost:8080/api/v1/cards/1/transactions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"amount":250.50,"merchant":"Cafe","category":"DINING"}'
curl -s "localhost:8080/api/v1/insights/monthly?month=$(date +%Y-%m)" -H "Authorization: Bearer $TOKEN"
```

## Tests
`mvn verify` runs these tests:
- a domain unit test
- a `@DataJpaTest` for the aggregation query
- a Mockito unit test for the fraud rules
- a `@WebMvcTest` for the controller and its security
- a `@SpringBootTest` end-to-end flow

## Design notes
- The app stores only the last 4 digits of a card. A full card number (PAN) is never stored.
- Money uses `BigDecimal`, never `double`.
- Users can only access their own cards. Every card lookup is scoped to the owner, which prevents IDOR.
- Auth is stateless JWT, and passwords are hashed with BCrypt.
- The fraud rules are HIGH_AMOUNT, OVER_LIMIT and VELOCITY. Their thresholds are set in `application.yml`.
