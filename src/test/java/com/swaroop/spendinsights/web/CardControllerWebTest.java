package com.swaroop.spendinsights.web;

import com.swaroop.spendinsights.security.JwtAuthFilter;
import com.swaroop.spendinsights.security.JwtService;
import com.swaroop.spendinsights.security.SecurityConfig;
import com.swaroop.spendinsights.service.CardService;
import com.swaroop.spendinsights.service.TransactionService;
import com.swaroop.spendinsights.web.dto.Dtos.CardResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CardController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class})
class CardControllerWebTest {
    @Autowired MockMvc mvc;
    @MockBean CardService cards;
    @MockBean TransactionService txns;

    @Test
    void rejectsAnonymousRequests() throws Exception {
        mvc.perform(get("/api/v1/cards")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "me@x.com")
    void listsMyCards() throws Exception {
        when(cards.list("me@x.com")).thenReturn(List.of(new CardResponse(1L, "1234", "Main", new BigDecimal("50000"), "ACTIVE")));
        mvc.perform(get("/api/v1/cards"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].last4").value("1234"));
    }

    @Test
    @WithMockUser(username = "me@x.com")
    void validatesCreateCardBody() throws Exception {
        mvc.perform(post("/api/v1/cards").contentType(MediaType.APPLICATION_JSON)
                .content("{\"last4\":\"12\",\"nickname\":\"\",\"creditLimit\":5}"))
           .andExpect(status().isBadRequest());
    }
}
