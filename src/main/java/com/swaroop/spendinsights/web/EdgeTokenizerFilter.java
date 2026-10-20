package com.swaroop.spendinsights.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * First stage of the clearing pipeline. Drops pan, cvv, and cvc before the body is bound.
 * A numeric PAN is reduced to its last four so the service can match the card reference.
 * The full number is not logged, stored, or copied onto the request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class EdgeTokenizerFilter extends OncePerRequestFilter {
    public static final String PRESENTED_LAST4 = "edge.presentedLast4";
    public static final String PAN_INVALID = "edge.panInvalid";

    private final ObjectMapper json;

    public EdgeTokenizerFilter(ObjectMapper json) { this.json = json; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        return path == null || !path.matches(".*/api/v1/cards/\\d+/transactions/?");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        byte[] raw = req.getInputStream().readAllBytes();
        byte[] next = raw;
        if (raw.length > 0) {
            try {
                JsonNode node = json.readTree(raw);
                if (node instanceof ObjectNode obj && (obj.has("pan") || obj.has("cvv") || obj.has("cvc"))) {
                    if (obj.has("pan") && !obj.get("pan").isNull()) {
                        String pan = obj.get("pan").asText("");
                        if (pan.matches("\\d{13,19}")) {
                            req.setAttribute(PRESENTED_LAST4, pan.substring(pan.length() - 4));
                        } else if (!pan.isBlank()) {
                            req.setAttribute(PAN_INVALID, Boolean.TRUE);
                        }
                    }
                    obj.remove("pan");
                    obj.remove("cvv");
                    obj.remove("cvc");
                    next = json.writeValueAsBytes(obj);
                }
            } catch (JsonProcessingException ignored) {
                next = raw;
            }
        }
        chain.doFilter(new BodyRequest(req, next), res);
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener readListener) {}
                @Override public int read() { return in.read(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }
}
