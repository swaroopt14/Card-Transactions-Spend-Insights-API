package com.swaroop.spendinsights.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Asynchronous record of one cleared purchase. It does not authorize or decline anything.
 * The ledger row is already committed when this row is written.
 */
@Entity
@Table(name = "behavioral_signals",
        uniqueConstraints = @UniqueConstraint(name = "uk_signal_txn", columnNames = "transaction_id"))
public class BehavioralSignal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null when a step-up failed and no ledger row was written. */
    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "challenge_id")
    private Long challengeId;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "time_delta_seconds")
    private Long timeDeltaSeconds;

    @Column(name = "place_changed", nullable = false)
    private boolean placeChanged;

    @Column(name = "high_risk_category", nullable = false)
    private int highRiskCategory;

    @Column(nullable = false, length = 16)
    private String decision;

    @Column(nullable = false, length = 160)
    private String reason;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    /** CLEARED, STEP_UP_VERIFIED, or STEP_UP_FAILED. */
    @Column(nullable = false, length = 24)
    private String outcome;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected BehavioralSignal() {}

    public BehavioralSignal(Long transactionId, Long cardId, BigDecimal amount, Long timeDeltaSeconds,
                            boolean placeChanged, int highRiskCategory, String decision, String reason, int riskScore,
                            String outcome, Long challengeId) {
        this.transactionId = transactionId;
        this.cardId = cardId;
        this.amount = amount;
        this.timeDeltaSeconds = timeDeltaSeconds;
        this.placeChanged = placeChanged;
        this.highRiskCategory = highRiskCategory;
        this.decision = decision;
        this.reason = reason;
        this.riskScore = riskScore;
        this.outcome = outcome;
        this.challengeId = challengeId;
        this.recordedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getTransactionId() { return transactionId; }
    public Long getCardId() { return cardId; }
    public BigDecimal getAmount() { return amount; }
    public Long getTimeDeltaSeconds() { return timeDeltaSeconds; }
    public boolean isPlaceChanged() { return placeChanged; }
    public int getHighRiskCategory() { return highRiskCategory; }
    public String getDecision() { return decision; }
    public String getReason() { return reason; }
    public int getRiskScore() { return riskScore; }
    public String getOutcome() { return outcome; }
    public Long getChallengeId() { return challengeId; }
    public Instant getRecordedAt() { return recordedAt; }
}
