package com.swaroop.spendinsights.config;

import com.swaroop.spendinsights.domain.Role;
import com.swaroop.spendinsights.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"ADMIN_EMAIL=seeded-admin@x.com", "ADMIN_PASSWORD=Seedpass1"})
@AutoConfigureMockMvc
class AdminSeederTest {
    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;

    @Test
    void seedsAdminFromEnvironmentAndDoesNotStoreThePlainPassword() throws Exception {
        var admin = users.findByEmail("seeded-admin@x.com").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getPasswordHash()).doesNotContain("Seedpass1").startsWith("$2");
        assertThat(encoder.matches("Seedpass1", admin.getPasswordHash())).isTrue();

        String token = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"seeded-admin@x.com\",\"password\":\"Seedpass1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + new com.fasterxml.jackson.databind.ObjectMapper().readTree(token).get("token").asText();
        mvc.perform(get("/api/v1/admin/flagged-transactions").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
