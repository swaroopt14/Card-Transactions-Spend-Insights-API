package com.swaroop.spendinsights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** End-to-end: register -> JWT -> card -> transactions -> monthly insights -> admin is forbidden. */
@SpringBootTest
@AutoConfigureMockMvc
class SpendInsightsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @Test
    void fullUserFlow() throws Exception {
        String reg = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"flow@x.com\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + om.readTree(reg).get("token").asText();

        String card = mvc.perform(post("/api/v1/cards").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"last4\":\"4321\",\"nickname\":\"Gold\",\"creditLimit\":40000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long cardId = om.readTree(card).get("id").asLong();

        mvc.perform(post("/api/v1/cards/" + cardId + "/transactions").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":250.50,\"merchant\":\"Cafe\",\"category\":\"DINING\",\"occurredAt\":\"2026-10-05T10:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flagged").value(false));

        mvc.perform(post("/api/v1/cards/" + cardId + "/transactions").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":60000,\"merchant\":\"Airline\",\"category\":\"TRAVEL\",\"occurredAt\":\"2026-10-06T10:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flagged").value(true));

        String insights = mvc.perform(get("/api/v1/insights/monthly?month=2026-10").header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode n = om.readTree(insights);
        org.assertj.core.api.Assertions.assertThat(n.get("grandTotal").decimalValue()).isEqualByComparingTo("60250.50");

        mvc.perform(get("/api/v1/admin/flagged-transactions").header("Authorization", bearer))
                .andExpect(status().isForbidden());
    }
}
