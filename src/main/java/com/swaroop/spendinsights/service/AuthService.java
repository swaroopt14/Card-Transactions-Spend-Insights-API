package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.domain.AppUser;
import com.swaroop.spendinsights.domain.Role;
import com.swaroop.spendinsights.repository.AppUserRepository;
import com.swaroop.spendinsights.security.JwtService;
import com.swaroop.spendinsights.web.dto.Dtos.*;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(AppUserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users; this.encoder = encoder; this.jwt = jwt;
    }

    public TokenResponse register(RegisterRequest req) {
        if (users.existsByEmail(req.email())) throw new BusinessRuleException("Email already registered");
        AppUser u = users.save(new AppUser(req.email(), encoder.encode(req.password()), Role.USER));
        return token(u);
    }

    public TokenResponse login(LoginRequest req) {
        AppUser u = users.findByEmail(req.email())
                .filter(x -> encoder.matches(req.password(), x.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        return token(u);
    }

    private TokenResponse token(AppUser u) {
        return new TokenResponse(jwt.generate(u.getEmail(), u.getRole()), "Bearer", jwt.getExpirySeconds());
    }
}
