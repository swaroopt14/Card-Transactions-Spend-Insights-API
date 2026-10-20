package com.swaroop.spendinsights.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Authorization held for a step-up code. The purchase row is written only after the code matches.
 */
@Entity
@Table(name = "step_up_challenges")
public class StepUpChallenge {
    public static final String PENDING = "PENDING";
    public static final String VERIFIED = "VERIFIED";
    public static final String FAILED = "FAILED";
    public static final String METHOD = "BIOMETRIC_STEP_UP";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_email", nullable = false)
    private String ownerEmail;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String merchant;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private Category category;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private SpendChannel channel;

    @Column(length = 40)
    private String location;

    @Column(nullable = false, length = 160)
    private String reasons;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "time_delta_seconds")
    private Long timeDeltaSeconds;

    @Column(name = "place_changed", nullable = false)
    private boolean placeChanged;

    @Column(name = "high_risk_category", nullable = false)
    private int highRiskCategory;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "transaction_id")
    private Long transactionId;

    protected StepUpChallenge() {}

    public StepUpChallenge(String ownerEmail, Long cardId, BigDecimal amount, String merchant, Category category,
                           Instant occurredAt, SpendChannel channel, String location, String reasons, int riskScore,
                           Long timeDeltaSeconds, boolean placeChanged, int highRiskCategory, String codeHash,
                           Instant expiresAt, String idempotencyKey, String requestHash) {
        this.ownerEmail = ownerEmail;
        this.cardId = cardId;
        this.amount = amount;
        this.merchant = merchant;
        this.category = category;
        this.occurredAt = occurredAt;
        this.channel = channel == null ? SpendChannel.API : channel;
        this.location = location;
        this.reasons = reasons;
        this.riskScore = riskScore;
        this.timeDeltaSeconds = timeDeltaSeconds;
        this.placeChanged = placeChanged;
        this.highRiskCategory = highRiskCategory;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = PENDING;
    }

    public void addAttempt() { this.attempts++; }
    public void markFailed() { this.status = FAILED; }
    public void markVerified(Long transactionId) {
        this.status = VERIFIED;
        this.transactionId = transactionId;
    }

    public Long getId() { return id; }
    public String getOwnerEmail() { return ownerEmail; }
    public Long getCardId() { return cardId; }
    public BigDecimal getAmount() { return amount; }
    public String getMerchant() { return merchant; }
    public Category getCategory() { return category; }
    public Instant getOccurredAt() { return occurredAt; }
    public SpendChannel getChannel() { return channel; }
    public String getLocation() { return location; }
    public String getReasons() { return reasons; }
    public int getRiskScore() { return riskScore; }
    public Long getTimeDeltaSeconds() { return timeDeltaSeconds; }
    public boolean isPlaceChanged() { return placeChanged; }
    public int getHighRiskCategory() { return highRiskCategory; }
    public String getStatus() { return status; }
    public String getCodeHash() { return codeHash; }
    public int getAttempts() { return attempts; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public Long getTransactionId() { return transactionId; }
}
