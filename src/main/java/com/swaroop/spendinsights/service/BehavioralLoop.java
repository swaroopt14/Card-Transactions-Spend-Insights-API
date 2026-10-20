package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.domain.BehavioralSignal;
import com.swaroop.spendinsights.repository.BehavioralSignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Second layer. Runs only after the purchase transaction has committed.
 * A failure here does not undo the ledger row and does not change the HTTP decision.
 */
@Component
public class BehavioralLoop {
    private static final Logger log = LoggerFactory.getLogger(BehavioralLoop.class);

    private final BehavioralSignalRepository signals;
    private final TransactionTemplate tx;

    public BehavioralLoop(BehavioralSignalRepository signals, PlatformTransactionManager manager) {
        this.signals = signals;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCleared(PurchaseCleared event) {
        try {
            tx.executeWithoutResult(status -> {
                if (signals.findByTransactionId(event.transactionId()).isPresent()) return;
                signals.save(new BehavioralSignal(
                        event.transactionId(), event.cardId(), event.amount(), event.timeDeltaSeconds(),
                        event.placeChanged(), event.highRiskCategory(), event.decision(),
                        event.reason(), event.riskScore(), event.outcome(), null));
            });
        } catch (RuntimeException ex) {
            log.warn("behavioral signal was not stored for transaction {}", event.transactionId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeclined(StepUpDeclined event) {
        try {
            tx.executeWithoutResult(status -> {
                if (signals.findByChallengeId(event.challengeId()).isPresent()) return;
                signals.save(new BehavioralSignal(
                        null, event.cardId(), event.amount(), null, false, 0,
                        "STEP_UP", event.reason(), event.riskScore(), "STEP_UP_FAILED", event.challengeId()));
            });
        } catch (RuntimeException ex) {
            log.warn("behavioral signal was not stored for challenge {}", event.challengeId());
        }
    }
}
