package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.domain.*;
import com.swaroop.spendinsights.repository.CardTransactionRepository;
import com.swaroop.spendinsights.web.dto.Dtos.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
public class TransactionService {
    private final CardTransactionRepository txns;
    private final CardService cardService;
    private final FraudRuleEngine fraud;

    public TransactionService(CardTransactionRepository txns, CardService cardService, FraudRuleEngine fraud) {
        this.txns = txns; this.cardService = cardService; this.fraud = fraud;
    }

    @Transactional
    public TransactionResponse record(String email, Long cardId, CreateTransactionRequest req) {
        Card card = cardService.getOwned(email, cardId);
        if (card.getStatus() == CardStatus.BLOCKED) throw new BusinessRuleException("Card is blocked");
        Instant at = req.occurredAt() != null ? req.occurredAt() : Instant.now();
        CardTransaction t = new CardTransaction(card, req.amount(), req.merchant(), req.category(), at);
        List<String> reasons = fraud.evaluate(card, req.amount(), at);
        if (!reasons.isEmpty()) t.flag(String.join(",", reasons));
        return toDto(txns.save(t));
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> list(String email, Long cardId, int page, int size) {
        cardService.getOwned(email, cardId);
        return txns.findByCardIdOrderByOccurredAtDesc(cardId, PageRequest.of(page, Math.min(size, 100)))
                   .map(TransactionService::toDto);
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
