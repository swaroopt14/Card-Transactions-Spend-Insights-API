package com.swaroop.spendinsights.web;

import com.swaroop.spendinsights.domain.CardStatus;
import com.swaroop.spendinsights.service.CardService;
import com.swaroop.spendinsights.service.ClearingResult;
import com.swaroop.spendinsights.service.IdempotencyRace;
import com.swaroop.spendinsights.service.NetworkGuard;
import com.swaroop.spendinsights.service.TransactionService;
import com.swaroop.spendinsights.web.dto.Dtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/cards")
public class CardController {
    private final CardService cards;
    private final TransactionService txns;
    private final NetworkGuard network;
    private final long clearingBudgetMs;

    public CardController(CardService cards, TransactionService txns, NetworkGuard network,
                          @org.springframework.beans.factory.annotation.Value("${app.network.clearing-budget-ms:50}") long clearingBudgetMs) {
        this.cards = cards;
        this.txns = txns;
        this.network = network;
        this.clearingBudgetMs = clearingBudgetMs;
    }

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

    @PostMapping("/{id}/dynamic-cvv")
    public DynamicCvvResponse dynamicCvv(Authentication auth, @PathVariable Long id) {
        return network.issue(auth.getName(), id);
    }

    @PostMapping("/{id}/transactions")
    public ResponseEntity<?> addTxn(Authentication auth, @PathVariable Long id,
                                      @Valid @RequestBody CreateTransactionRequest req,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                      HttpServletRequest request) {
        String presentedLast4 = (String) request.getAttribute(EdgeTokenizerFilter.PRESENTED_LAST4);
        boolean panInvalid = Boolean.TRUE.equals(request.getAttribute(EdgeTokenizerFilter.PAN_INVALID));
        try {
            ClearingResult result = txns.record(auth.getName(), id, req, idempotencyKey, presentedLast4, panInvalid);
            if (result.stepUp() != null) {
                return clearing(HttpStatus.ACCEPTED, result).body(result.stepUp());
            }
            HttpStatus status = result.posted().replayed() ? HttpStatus.OK : HttpStatus.CREATED;
            return clearing(status, result).body(result.posted().body());
        } catch (IdempotencyRace race) {
            return ResponseEntity.ok(txns.replay(auth.getName(), id, req, idempotencyKey));
        }
    }

    @PostMapping("/{id}/step-up/{challengeId}")
    public ResponseEntity<TransactionResponse> verifyStepUp(Authentication auth, @PathVariable Long id,
                                                            @PathVariable Long challengeId,
                                                            @Valid @RequestBody StepUpVerifyRequest req) {
        ClearingResult result = txns.verifyStepUp(auth.getName(), id, challengeId, req.challengeCode());
        return clearing(HttpStatus.CREATED, result).body(result.posted().body());
    }

    private ResponseEntity.BodyBuilder clearing(HttpStatus status, ClearingResult result) {
        return ResponseEntity.status(status)
                .header(ClearingResult.BUDGET_HEADER, Long.toString(clearingBudgetMs))
                .header(ClearingResult.LATENCY_HEADER, Long.toString(result.latencyMs()));
    }

    @GetMapping("/{id}/transactions")
    public TransactionPage listTxns(Authentication auth, @PathVariable Long id,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return txns.list(auth.getName(), id, page, size);
    }
}
