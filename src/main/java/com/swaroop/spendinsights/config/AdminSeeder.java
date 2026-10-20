package com.swaroop.spendinsights.config;

import com.swaroop.spendinsights.domain.AppUser;
import com.swaroop.spendinsights.domain.Role;
import com.swaroop.spendinsights.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Creates an ADMIN user on startup only if ADMIN_EMAIL and ADMIN_PASSWORD env vars are set. */
@Component
public class AdminSeeder implements CommandLineRunner {
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final String email;
    private final String password;

    public AdminSeeder(AppUserRepository users, PasswordEncoder encoder,
                       @Value("${ADMIN_EMAIL:}") String email, @Value("${ADMIN_PASSWORD:}") String password) {
        this.users = users; this.encoder = encoder; this.email = email; this.password = password;
    }

    @Override
    public void run(String... args) {
        if (!email.isBlank() && !password.isBlank() && !users.existsByEmail(email)) {
            users.save(new AppUser(email, encoder.encode(password), Role.ADMIN));
        }
    }
}
