package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.domain.*;
import com.swaroop.spendinsights.repository.*;
import com.swaroop.spendinsights.web.dto.Dtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
public class CardService {
    private final CardRepository cards;
    private final AppUserRepository users;
    private final String tokenPepper;

    public CardService(CardRepository cards, AppUserRepository users,
                       @Value("${app.fraud.token-pepper}") String tokenPepper) {
        this.cards = cards;
        this.users = users;
        this.tokenPepper = tokenPepper == null ? "" : tokenPepper;
    }

    @Transactional
    public CardResponse create(String email, CreateCardRequest req) {
        AppUser owner = users.findByEmail(email).orElseThrow(() -> new NotFoundException("User not found"));
        Card card = new Card(owner, req.last4(), req.nickname(), req.creditLimit());
        card.setAccountReferenceId(UUID.randomUUID().toString());
        Card saved = cards.saveAndFlush(card);
        saved.setCardToken(CardTokenHasher.hash(tokenPepper, email, saved.getId(), saved.getLast4(),
                saved.getAccountReferenceId()));
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<CardResponse> list(String email) {
        return cards.findByOwnerEmail(email).stream().map(CardService::toDto).toList();
    }

    @Transactional
    public CardResponse setStatus(String email, Long cardId, CardStatus status) {
        Card c = getOwned(email, cardId);
        c.setStatus(status);
        return toDto(c);
    }

    /** Ownership check: a user can only touch their own cards (prevents IDOR). */
    public Card getOwned(String email, Long cardId) {
        return cards.findByIdAndOwnerEmail(cardId, email).orElseThrow(() -> new NotFoundException("Card not found"));
    }

    /**
     * Same ownership check as {@link #getOwned}, then locks the card row.
     * Call this inside an existing write transaction before the velocity count.
     */
    public Card getOwnedForUpdate(String email, Long cardId) {
        getOwned(email, cardId);
        return cards.findByIdForUpdate(cardId).orElseThrow(() -> new NotFoundException("Card not found"));
    }

    static CardResponse toDto(Card c) {
        return new CardResponse(c.getId(), c.getLast4(), c.getNickname(), c.getCreditLimit(), c.getStatus().name(),
                c.getAccountReferenceId(), c.getCardToken());
    }
}
