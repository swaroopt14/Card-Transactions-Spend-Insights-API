package com.swaroop.spendinsights.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "cards")
public class Card {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    /** Only the last 4 digits are stored - never a full card number. */
    @Column(nullable = false, length = 4)
    private String last4;

    @Column(nullable = false)
    private String nickname;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal creditLimit;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private CardStatus status = CardStatus.ACTIVE;

    protected Card() {}

    public Card(AppUser owner, String last4, String nickname, BigDecimal creditLimit) {
        this.owner = owner; this.last4 = last4; this.nickname = nickname; this.creditLimit = creditLimit;
    }

    public Long getId() { return id; }
    public AppUser getOwner() { return owner; }
    public String getLast4() { return last4; }
    public String getNickname() { return nickname; }
    public BigDecimal getCreditLimit() { return creditLimit; }
    public CardStatus getStatus() { return status; }
    public void setStatus(CardStatus status) { this.status = status; }
}
