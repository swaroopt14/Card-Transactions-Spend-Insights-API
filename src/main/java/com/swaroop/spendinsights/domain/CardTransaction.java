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

    protected CardTransaction() {}

    public CardTransaction(Card card, BigDecimal amount, String merchant, Category category, Instant occurredAt) {
        this.card = card; this.amount = amount; this.merchant = merchant; this.category = category; this.occurredAt = occurredAt;
    }

    public void flag(String reason) { this.flagged = true; this.flagReason = reason; }

    public Long getId() { return id; }
    public Card getCard() { return card; }
    public BigDecimal getAmount() { return amount; }
    public String getMerchant() { return merchant; }
    public Category getCategory() { return category; }
    public Instant getOccurredAt() { return occurredAt; }
    public boolean isFlagged() { return flagged; }
    public String getFlagReason() { return flagReason; }
}
