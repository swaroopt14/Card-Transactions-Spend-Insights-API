package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.domain.*;
import com.swaroop.spendinsights.repository.CardTransactionRepository;
import com.swaroop.spendinsights.web.dto.Dtos.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class TransactionService {
    /** Timestamps farther ahead than this are rejected. Equal to this skew is accepted. */
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    static final int MAX_PAGE_SIZE = 100;

    private final CardTransactionRepository txns;
    private final CardService cardService;
    private final FraudRuleEngine fraud;

    public TransactionService(CardTransactionRepository txns, CardService cardService, FraudRuleEngine fraud) {
        this.txns = txns; this.cardService = cardService; this.fraud = fraud;
    }

    /**
     * Records one transaction. There is no internal retry: the single {@code save} is inside
     * this transaction, so a failure rolls the insert back instead of writing it twice.
     * <p>
     * Timestamp policy: a missing {@code occurredAt} becomes {@link Instant#now()} (UTC).
     * A supplied instant may be in the past. It is rejected with 400 when it is more than
     * five minutes in the future.
     */
    @Transactional
    public TransactionResponse record(String email, Long cardId, CreateTransactionRequest req) {
        Instant at = req.occurredAt() != null ? req.occurredAt() : Instant.now();
        if (at.isAfter(Instant.now().plus(MAX_FUTURE_SKEW))) {
            throw new BadRequestException("occurredAt cannot be more than 5 minutes in the future");
        }
        Card card = cardService.getOwnedForUpdate(email, cardId);
        if (card.getStatus() == CardStatus.BLOCKED) throw new BusinessRuleException("Card is blocked");
        CardTransaction t = new CardTransaction(card, req.amount(), req.merchant(), req.category(), at);
        List<String> reasons = fraud.evaluate(card, req.amount(), at);
        if (!reasons.isEmpty()) t.flag(String.join(",", reasons));
        return toDto(txns.save(t));
    }

    /**
     * Newest first. {@code page} is zero-based and must be &gt;= 0. {@code size} must be &gt;= 1
     * and is capped at 100. A size above 100 is accepted and silently capped; a non-positive size is 400.
     */
    @Transactional(readOnly = true)
    public TransactionPage list(String email, Long cardId, int page, int size) {
        if (page < 0) throw new BadRequestException("page must be zero or greater");
        if (size < 1) throw new BadRequestException("size must be at least 1");
        cardService.getOwned(email, cardId);
        int capped = Math.min(size, MAX_PAGE_SIZE);
        Page<TransactionResponse> result = txns.findByCardIdOrderByOccurredAtDesc(cardId, PageRequest.of(page, capped))
                .map(TransactionService::toDto);
        return new TransactionPage(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> flagged() {
        return txns.findByFlaggedTrueOrderByOccurredAtDesc().stream().map(TransactionService::toDto).toList();
    }

    static TransactionResponse toDto(CardTransaction t) {
        return new TransactionResponse(t.getId(), t.getCard().getId(), t.getAmount(), t.getMerchant(),
                t.getCategory(), t.getOccurredAt(), t.isFlagged(), t.getFlagReason());
    }
}
