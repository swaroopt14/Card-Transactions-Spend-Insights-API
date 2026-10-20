package com.swaroop.spendinsights.web.dto;

import com.swaroop.spendinsights.domain.Category;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** All request/response DTOs as Java records (immutable data carriers, Java 16+). */
public final class Dtos {
    private Dtos() {}

    public record RegisterRequest(@Email @NotBlank String email,
                                  @NotBlank @Size(min = 8, max = 72) String password) {}

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

    public record TokenResponse(String token, String tokenType, long expiresInSeconds) {}

    public record CreateCardRequest(@NotBlank @Pattern(regexp = "\\d{4}") String last4,
                                    @NotBlank @Size(max = 40) String nickname,
                                    @NotNull @DecimalMin("1000.00") BigDecimal creditLimit) {}

    public record CardResponse(Long id, String last4, String nickname, BigDecimal creditLimit, String status) {}

    public record CreateTransactionRequest(@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                           @NotBlank @Size(max = 80) String merchant,
                                           @NotNull Category category,
                                           Instant occurredAt) {}

    public record TransactionResponse(Long id, Long cardId, BigDecimal amount, String merchant,
                                      Category category, Instant occurredAt, boolean flagged, String flagReason) {}

    /** Stable page contract. {@code page} is zero-based. {@code size} is the capped page size. */
    public record TransactionPage(List<TransactionResponse> content, int page, int size,
                                  long totalElements, int totalPages) {}

    public record CategoryTotal(Category category, BigDecimal total, long count) {}

    public record MonthlySpendResponse(String month, BigDecimal grandTotal, List<CategoryTotal> categories) {}

    public record ErrorResponse(int status, String error, String message, Instant timestamp) {}
}
