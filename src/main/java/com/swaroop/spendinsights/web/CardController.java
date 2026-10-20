package com.swaroop.spendinsights.web;

import com.swaroop.spendinsights.domain.CardStatus;
import com.swaroop.spendinsights.service.CardService;
import com.swaroop.spendinsights.service.TransactionService;
import com.swaroop.spendinsights.web.dto.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/cards")
public class CardController {
    private final CardService cards;
    private final TransactionService txns;

    public CardController(CardService cards, TransactionService txns) { this.cards = cards; this.txns = txns; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public CardResponse create(Authentication auth, @Valid @RequestBody CreateCardRequest req) {
        return cards.create(auth.getName(), req);
    }

    @GetMapping
    public List<CardResponse> list(Authentication auth) { return cards.list(auth.getName()); }

    @PatchMapping("/{id}/block")
    public CardResponse block(Authentication auth, @PathVariable Long id) {
        return cards.setStatus(auth.getName(), id, CardStatus.BLOCKED);
    }

    @PatchMapping("/{id}/unblock")
    public CardResponse unblock(Authentication auth, @PathVariable Long id) {
        return cards.setStatus(auth.getName(), id, CardStatus.ACTIVE);
    }

    @PostMapping("/{id}/transactions") @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse addTxn(Authentication auth, @PathVariable Long id,
                                      @Valid @RequestBody CreateTransactionRequest req) {
        return txns.record(auth.getName(), id, req);
    }

    @GetMapping("/{id}/transactions")
    public TransactionPage listTxns(Authentication auth, @PathVariable Long id,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return txns.list(auth.getName(), id, page, size);
    }
}
