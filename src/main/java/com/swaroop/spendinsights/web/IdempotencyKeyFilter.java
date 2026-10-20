package com.swaroop.spendinsights.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * Echoes {@code Idempotency-Key} on the response, including errors.
 * The server never invents a key. A missing header stays missing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class IdempotencyKeyFilter extends OncePerRequestFilter {
    public static final String HEADER = "Idempotency-Key";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader(HEADER);
        if (key != null && !key.isBlank() && key.trim().length() <= 80) {
            res.setHeader(HEADER, key.trim());
        }
        chain.doFilter(req, res);
    }
}
