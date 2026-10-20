package com.swaroop.spendinsights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swaroop.spendinsights.domain.*;
import com.swaroop.spendinsights.repository.AppUserRepository;
import com.swaroop.spendinsights.repository.CardRepository;
import com.swaroop.spendinsights.repository.CardTransactionRepository;
import com.swaroop.spendinsights.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiAcceptanceTest {
    private static final String PASSWORD = "Passw0rd!";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired AppUserRepository users;
    @Autowired CardRepository cards;
    @Autowired CardTransactionRepository txns;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;
    @Value("${app.jwt.secret}") String jwtSecret;

    @Test
    void registrationReturnsTokenAndStoresBcryptHash() throws Exception {
        String email = email("reg");
        String body = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(creds(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(300))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(PASSWORD).doesNotContain("passwordHash");
        AppUser saved = users.findByEmail(email).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(Role.USER);
        assertThat(saved.getPasswordHash()).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(encoder.matches(PASSWORD, saved.getPasswordHash())).isTrue();
        assertThat(jwt.parse(om.readTree(body).get("token").asText()).get("role", String.class)).isEqualTo("USER");
    }

    @Test
    void duplicateRegistrationReturns409() throws Exception {
        String email = email("dup");
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(creds(email)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(creds(email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    void registrationIgnoresClientSuppliedRole() throws Exception {
        String email = email("role");
        String body = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = om.readTree(body).get("token").asText();
        assertThat(jwt.parse(token).get("role", String.class)).isEqualTo("USER");
        mvc.perform(get("/api/v1/admin/flagged-transactions").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void loginAcceptsTheRightPasswordAndRejectsTheWrongOne() throws Exception {
        String email = email("login");
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(creds(email)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(creds(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void missingExpiredAndForgedTokensCannotCallProtectedApis() throws Exception {
        mvc.perform(get("/api/v1/cards")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/api/v1/cards").header("Authorization", "Bearer " + signed("gone@x.com", "USER", Instant.now().minusSeconds(30))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/cards").header("Authorization", "Bearer " + signed("gone@x.com", "USER", Instant.now().plusSeconds(60), "different-secret-different-secret-123456")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/cards").header("Authorization", "Bearer " + signed("gone@x.com", null, Instant.now().plusSeconds(60))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/flagged-transactions")).andExpect(status().isUnauthorized());
    }

    @Test
    void userManagesOnlyTheirOwnCards() throws Exception {
        String owner = bearer(email("owner"));
        String other = bearer(email("other"));
        mvc.perform(post("/api/v1/cards").header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"last4\":\"12\",\"nickname\":\"\",\"creditLimit\":5}"))
                .andExpect(status().isBadRequest());
        long cardId = createCard(owner, "4321", "Gold", "40000");
        mvc.perform(get("/api/v1/cards").header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].last4").value("4321"))
                .andExpect(jsonPath("$[0].id").value(cardId));
        mvc.perform(get("/api/v1/cards").header("Authorization", other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(patch("/api/v1/cards/" + cardId + "/block").header("Authorization", other))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/cards/" + cardId + "/transactions").header("Authorization", other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txn("10.00", "Cafe", "DINING", "2026-04-01T10:00:00Z")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/cards/" + cardId + "/transactions").header("Authorization", other))
                .andExpect(status().isNotFound());
    }

    @Test
    void blockStopsNewTransactionsUntilUnblocked() throws Exception {
        String token = bearer(email("block"));
        long cardId = createCard(token, "1111", "Main", "40000");
        mvc.perform(patch("/api/v1/cards/" + cardId + "/block").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));
        mvc.perform(post("/api/v1/cards/" + cardId + "/transactions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txn("10.00", "Cafe", "DINING", "2026-04-02T10:00:00Z")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Card is blocked"));
        mvc.perform(patch("/api/v1/cards/" + cardId + "/unblock").header("Authorization", token))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/api/v1/cards/" + cardId + "/transactions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txn("10.00", "Cafe", "DINING", "2026-04-02T10:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flagged").value(false));
    }

    @Test
    void recordsFraudFlagsAndRejectsBadTransactionInput() throws Exception {
        String token = bearer(email("fraud"));
        long wide = createCard(token, "2222", "Wide", "100000");
        long tight = createCard(token, "3333", "Tight", "40000");

        mvc.perform(post("/api/v1/cards/" + wide + "/transactions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txn("250.50", "Cafe", "DINING", "2026-05-01T10:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flagged").value(false))
                .andExpect(jsonPath("$.flagReason").value(nullValue()));

        mvc.perform(post("/api/v1/cards/" + wide + "/transactions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txn("60000", "Airline", "TRAVEL", "2026-05-02T10:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flagged").value(true))
                .andExpect(jsonPath("$.flagReason").value("HIGH_AMOUNT"));

        mvc.perform(post("/api/v1/cards/" + tight + "/transactions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txn("45000", "Hotel", "TRAVEL", "2026-05-03T10:00:00Z")))
                .andExpect(jsonPath("$.flagReason").value("OVER_LIMIT"));

        mvc.perform(post("/api/v1/cards/" + tight + "/transactions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txn("60000", "Airline", "TRAVEL", "2026-05-04T10:00:00Z")))
                .andExpect(jsonPath("$.flagReason").value("HIGH_AMOUNT,OVER_LIMIT"));

        mvc.perform(post("/api/v1/cards/" + wide + "/transactions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0,\"merchant\":\"Cafe\",\"category\":\"DINING\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/cards/" + wide + "/transactions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10,\"merchant\":\"Cafe\",\"category\":\"NOT_A_CATEGORY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON or invalid field value"));
        mvc.perform(post("/api/v1/cards/" + wide + "/transactions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void timestampPolicyDefaultsNowAndRejectsFarFutureValues() throws Exception {
        String token = bearer(email("time"));
        long cardId = createCard(token, "4444", "Time", "100000");
        Instant before = Instant.now().minusSeconds(2);
        MvcResult created = mvc.perform(post("/api/v1/cards/" + cardId + "/transactions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":12.00,\"merchant\":\"Cafe\",\"category\":\"DINING\"}"))
                .andExpect(status().isCreated()).andReturn();
        Instant occurred = Instant.parse(om.readTree(created.getResponse().getContentAsString()).get("occurredAt").asText());
        assertThat(occurred).isAfter(before).isBefore(Instant.now().plusSeconds(30));

        String soon = Instant.now().plus(Duration.ofMinutes(4)).toString();
        mvc.perform(post("/api/v1/cards/" + cardId + "/transactions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txn("12.00", "Soon", "DINING", soon)))
                .andExpect(status().isCreated());
        String later = Instant.now().plus(Duration.ofMinutes(6)).toString();
        mvc.perform(post("/api/v1/cards/" + cardId + "/transactions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txn("12.00", "Later", "DINING", later)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("occurredAt cannot be more than 5 minutes in the future"));
        mvc.perform(post("/api/v1/cards/" + cardId + "/transactions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":12.00,\"merchant\":\"Bad\",\"category\":\"DINING\",\"occurredAt\":\"not-a-date\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void velocityFlagsTheSixthTransactionInsideTheMinuteOnly() throws Exception {
        String token = bearer(email("vel"));
        long inside = createCard(token, "5555", "Inside", "100000");
        for (int i = 0; i < 4; i++) {
            postTxn(token, inside, txn("10.00", "Below" + i, "DINING", "2026-02-02T10:00:0" + i + "Z"), false);
        }
        postTxn(token, inside, txn("10.00", "Fifth", "DINING", "2026-02-02T10:00:04Z"), false);

        long atLimit = createCard(token, "6666", "At", "100000");
        for (int i = 0; i < 5; i++) {
            postTxn(token, atLimit, txn("10.00", "Prior" + i, "DINING", "2026-02-01T10:00:00Z"), false);
        }
        mvc.perform(post("/api/v1/cards/" + atLimit + "/transactions").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txn("10.00", "Sixth", "DINING", "2026-02-01T10:01:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flagReason").value("VELOCITY"));

        long outside = createCard(token, "7777", "Out", "100000");
        for (int i = 0; i < 5; i++) {
            postTxn(token, outside, txn("10.00", "Old" + i, "DINING", "2026-02-03T09:58:59Z"), false);
        }
        postTxn(token, outside, txn("10.00", "AfterGap", "DINING", "2026-02-03T10:00:00Z"), false);
    }

    @Test
    void historyIsPagedNewestFirstAndRejectsBadPageArguments() throws Exception {
        String token = bearer(email("page"));
        long cardId = createCard(token, "8888", "Hist", "100000");
        postTxn(token, cardId, txn("10.00", "First", "DINING", "2026-03-01T10:00:00Z"), false);
        postTxn(token, cardId, txn("10.00", "Second", "DINING", "2026-03-01T11:00:00Z"), false);
        postTxn(token, cardId, txn("10.00", "Third", "DINING", "2026-03-01T12:00:00Z"), false);

        JsonNode page0 = json(mvc.perform(get("/api/v1/cards/" + cardId + "/transactions?page=0&size=2").header("Authorization", token))
                .andExpect(status().isOk()).andReturn());
        JsonNode page1 = json(mvc.perform(get("/api/v1/cards/" + cardId + "/transactions?page=1&size=2").header("Authorization", token))
                .andExpect(status().isOk()).andReturn());
        assertThat(page0.get("page").asInt()).isZero();
        assertThat(page0.get("content")).hasSize(2);
        assertThat(page0.get("content").get(0).get("merchant").asText()).isEqualTo("Third");
        assertThat(page0.get("content").get(1).get("merchant").asText()).isEqualTo("Second");
        assertThat(page1.get("content")).hasSize(1);
        assertThat(page1.get("content").get(0).get("merchant").asText()).isEqualTo("First");
        Set<String> seen = new HashSet<>();
        page0.get("content").forEach(n -> seen.add(n.get("id").asText()));
        page1.get("content").forEach(n -> assertThat(seen).doesNotContain(n.get("id").asText()));

        mvc.perform(get("/api/v1/cards/" + cardId + "/transactions?page=-1").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/cards/" + cardId + "/transactions?size=0").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/cards/" + cardId + "/transactions?page=nope").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/cards/" + cardId + "/transactions?size=500").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        long empty = createCard(token, "9999", "Empty", "100000");
        mvc.perform(get("/api/v1/cards/" + empty + "/transactions").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void monthlyInsightsUseAHalfOpenUtcWindowAndIgnoreOtherUsers() throws Exception {
        String token = bearer(email("month"));
        String other = bearer(email("month-other"));
        long cardId = createCard(token, "1212", "Month", "100000");
        long otherCard = createCard(other, "1313", "Other", "100000");
        postTxn(token, cardId, txn("100.00", "Dinner", "DINING", "2026-10-05T10:00:00Z"), false);
        postTxn(token, cardId, txn("40.00", "Lunch", "DINING", "2026-10-06T10:00:00Z"), false);
        postTxn(token, cardId, txn("500.00", "Flight", "TRAVEL", "2026-10-01T00:00:00Z"), false);
        postTxn(other, otherCard, txn("999.00", "Secret", "DINING", "2026-10-05T10:00:00Z"), false);
        Card card = cards.findById(cardId).orElseThrow();
        CardTransaction nextMonth = new CardTransaction(card, new BigDecimal("999.00"), "November", Category.TRAVEL, Instant.parse("2026-11-01T00:00:00Z"));
        txns.save(nextMonth);

        JsonNode body = json(mvc.perform(get("/api/v1/insights/monthly?month=2026-10").header("Authorization", token))
                .andExpect(status().isOk()).andReturn());
        assertThat(body.get("grandTotal").decimalValue()).isEqualByComparingTo("640.00");
        assertThat(body.get("categories")).hasSize(2);
        assertThat(body.get("categories").get(0).get("category").asText()).isEqualTo("TRAVEL");
        assertThat(body.get("categories").get(0).get("total").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(body.get("categories").get(0).get("count").asLong()).isEqualTo(1L);
        assertThat(body.get("categories").get(1).get("total").decimalValue()).isEqualByComparingTo("140.00");
        assertThat(body.get("categories").get(1).get("count").asLong()).isEqualTo(2L);

        JsonNode empty = json(mvc.perform(get("/api/v1/insights/monthly?month=2020-01").header("Authorization", token))
                .andExpect(status().isOk()).andReturn());
        assertThat(empty.get("grandTotal").decimalValue()).isEqualByComparingTo("0");
        assertThat(empty.get("categories")).isEmpty();
        mvc.perform(get("/api/v1/insights/monthly?month=2026-13").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/insights/monthly").header("Authorization", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminSeesFlaggedTransactionsAndUsersDoNot() throws Exception {
        String userEmail = email("user");
        String token = bearer(userEmail);
        long cardId = createCard(token, "1414", "Admin", "100000");
        postTxn(token, cardId, txn("10.00", "CleanCafe", "DINING", "2026-06-01T10:00:00Z"), false);
        postTxn(token, cardId, txn("60000", "FlaggedAirline", "TRAVEL", "2026-06-02T10:00:00Z"), true);

        String adminEmail = email("admin");
        users.save(new AppUser(adminEmail, encoder.encode(PASSWORD), Role.ADMIN));
        String adminToken = login(adminEmail);

        JsonNode flagged = json(mvc.perform(get("/api/v1/admin/flagged-transactions").header("Authorization", adminToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(flagged).isNotEmpty();
        assertThat(flagged.findValuesAsText("merchant")).contains("FlaggedAirline").doesNotContain("CleanCafe");
        flagged.forEach(row -> assertThat(row.get("flagged").asBoolean()).isTrue());
        mvc.perform(get("/api/v1/admin/flagged-transactions").header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    private String email(String prefix) { return prefix + "-" + UUID.randomUUID() + "@x.com"; }

    private String creds(String email) { return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"; }

    private String bearer(String email) throws Exception {
        return "Bearer " + om.readTree(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(creds(email)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private String login(String email) throws Exception {
        return "Bearer " + om.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(creds(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private long createCard(String bearer, String last4, String nickname, String limit) throws Exception {
        String body = mvc.perform(post("/api/v1/cards").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"last4\":\"" + last4 + "\",\"nickname\":\"" + nickname + "\",\"creditLimit\":" + limit + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("id").asLong();
    }

    private String txn(String amount, String merchant, String category, String at) {
        return "{\"amount\":" + amount + ",\"merchant\":\"" + merchant + "\",\"category\":\"" + category + "\",\"occurredAt\":\"" + at + "\"}";
    }

    private void postTxn(String bearer, long cardId, String json, boolean flagged) throws Exception {
        mvc.perform(post("/api/v1/cards/" + cardId + "/transactions").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flagged").value(flagged));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return om.readTree(result.getResponse().getContentAsString());
    }

    private String signed(String subject, String role, Instant exp) {
        return signed(subject, role, exp, jwtSecret);
    }

    private String signed(String subject, String role, Instant exp, String secret) {
        var builder = Jwts.builder().subject(subject)
                .issuedAt(Date.from(exp.minusSeconds(120)))
                .expiration(Date.from(exp))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)));
        if (role != null) builder.claim("role", role);
        return builder.compact();
    }
}
