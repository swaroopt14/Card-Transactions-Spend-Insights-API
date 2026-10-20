package com.swaroop.spendinsights.web;

import com.swaroop.spendinsights.service.AuthService;
import com.swaroop.spendinsights.web.dto.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest req) { return auth.register(req); }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) { return auth.login(req); }
}
