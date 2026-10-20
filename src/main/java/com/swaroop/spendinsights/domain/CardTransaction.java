package com.swaroop.spendinsights.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "card_transactions", indexes = @Index(name = "idx_txn_card_time", columnList = "card_id,occurred_at"))
public class CardTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id")
    private Card card;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String merchant;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Category category;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    private boolean flagged;
    private String flagReason;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private SpendChannel channel = SpendChannel.API;

    /** City or place sent with the purchase. Blank means the client did not say. */
    @Column(length = 40)
    private String location;

    /** AUTHORIZED, FLAGGED, or STEP_UP. Set by the synchronous clearing decision. */
    @Column(name = "clearing_decision", length = 16)
    private String clearingDecision;

    /** Seconds since the previous purchase on this card at or before this instant. Null when this is the first. */
    @Column(name = "time_delta_seconds")
    private Long timeDeltaSeconds;

    @Column(name = "place_changed", nullable = false)
    private boolean placeChanged;

    /** 1 when the category is in the configured high-risk set. 0 otherwise. This is not an MCC from a card network. */
    @Column(name = "high_risk_category", nullable = false)
    private int highRiskCategory;

    protected CardTransaction() {}

    public CardTransaction(Card card, BigDecimal amount, String merchant, Category category, Instant occurredAt) {
        this(card, amount, merchant, category, occurredAt, SpendChannel.API);
    }

    public CardTransaction(Card card, BigDecimal amount, String merchant, Category category, Instant occurredAt,
                           SpendChannel channel) {
        this(card, amount, merchant, category, occurredAt, channel, null);
    }

    public CardTransaction(Card card, BigDecimal amount, String merchant, Category category, Instant occurredAt,
                           SpendChannel channel, String location) {
        this.card = card; this.amount = amount; this.merchant = merchant; this.category = category; this.occurredAt = occurredAt;
        this.channel = channel == null ? SpendChannel.API : channel;
        this.location = location;
    }

    public void flag(String reason) { this.flagged = true; this.flagReason = reason; }

    public void applyClearing(String decision, Long timeDeltaSeconds, boolean placeChanged, int highRiskCategory) {
        this.clearingDecision = decision;
        this.timeDeltaSeconds = timeDeltaSeconds;
        this.placeChanged = placeChanged;
        this.highRiskCategory = highRiskCategory;
    }

    public Long getId() { return id; }
    public Card getCard() { return card; }
    public BigDecimal getAmount() { return amount; }
    public String getMerchant() { return merchant; }
    public Category getCategory() { return category; }
    public Instant getOccurredAt() { return occurredAt; }
    public boolean isFlagged() { return flagged; }
    public String getFlagReason() { return flagReason; }
    public SpendChannel getChannel() { return channel; }
    public String getLocation() { return location; }
    public String getClearingDecision() { return clearingDecision; }
    public Long getTimeDeltaSeconds() { return timeDeltaSeconds; }
    public boolean isPlaceChanged() { return placeChanged; }
    public int getHighRiskCategory() { return highRiskCategory; }
}
