package com.swaroop.spendinsights.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

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

    /** Stable id for this card reference. Survives nickname edits. */
    @Column(name = "account_reference_id", nullable = false, unique = true, length = 36)
    private String accountReferenceId;

    /** SHA-256 fingerprint of owner + card id + last4 + account reference. Not a PAN hash. */
    @Column(name = "card_token", nullable = false, length = 64)
    private String cardToken;

    protected Card() {}

    public Card(AppUser owner, String last4, String nickname, BigDecimal creditLimit) {
        this.owner = owner; this.last4 = last4; this.nickname = nickname; this.creditLimit = creditLimit;
    }

    @PrePersist
    void assignTokens() {
        if (accountReferenceId == null || accountReferenceId.isBlank()) {
            accountReferenceId = UUID.randomUUID().toString();
        }
        if (cardToken == null || cardToken.isBlank()) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest((accountReferenceId + "|" + last4).getBytes(StandardCharsets.UTF_8));
                cardToken = HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public Long getId() { return id; }
    public AppUser getOwner() { return owner; }
    public String getLast4() { return last4; }
    public String getNickname() { return nickname; }
    public BigDecimal getCreditLimit() { return creditLimit; }
    public CardStatus getStatus() { return status; }
    public void setStatus(CardStatus status) { this.status = status; }
    public String getAccountReferenceId() { return accountReferenceId; }
    public void setAccountReferenceId(String accountReferenceId) { this.accountReferenceId = accountReferenceId; }
    public String getCardToken() { return cardToken; }
    public void setCardToken(String cardToken) { this.cardToken = cardToken; }
}
