package com.swaroop.spendinsights.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Remembers one successful write so a client retry does not create a second purchase. */
@Entity
@Table(name = "idempotency_keys",
        uniqueConstraints = @UniqueConstraint(name = "uk_idem_owner_key", columnNames = {"owner_email", "idempotency_key"}))
public class IdempotencyRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_email", nullable = false)
    private String ownerEmail;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    private Instant createdAt = Instant.now();

    protected IdempotencyRecord() {}

    public IdempotencyRecord(String ownerEmail, String idempotencyKey, String requestHash, Long transactionId) {
        this.ownerEmail = ownerEmail;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.transactionId = transactionId;
    }

    public String getRequestHash() { return requestHash; }
    public Long getTransactionId() { return transactionId; }
}
