package com.swaroop.spendinsights.service;

import com.swaroop.spendinsights.domain.*;
import com.swaroop.spendinsights.repository.CardTransactionRepository;
import com.swaroop.spendinsights.repository.IdempotencyRepository;
import com.swaroop.spendinsights.repository.StepUpChallengeRepository;
import com.swaroop.spendinsights.web.dto.Dtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class TransactionService {
    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
    /** Timestamps farther ahead than this are rejected. Equal to this skew is accepted. */
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    static final int MAX_PAGE_SIZE = 100;
    private static final int STEP_UP_ATTEMPTS = 3;

    private final CardTransactionRepository txns;
    private final IdempotencyRepository idempotency;
    private final StepUpChallengeRepository challenges;
    private final CardService cardService;
    private final InferenceEngine inference;
    private final NetworkGuard network;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate attempts;
    private final String pepper;
    private final long stepUpTtlSeconds;
    private final long clearingBudgetMs;
    private final SecureRandom random = new SecureRandom();

    public TransactionService(CardTransactionRepository txns, IdempotencyRepository idempotency,
                              StepUpChallengeRepository challenges, CardService cardService, InferenceEngine inference,
                              NetworkGuard network, ApplicationEventPublisher events, PlatformTransactionManager manager,
                              @Value("${app.fraud.token-pepper}") String pepper,
                              @Value("${app.network.step-up-ttl-seconds:300}") long stepUpTtlSeconds,
                              @Value("${app.network.clearing-budget-ms:50}") long clearingBudgetMs) {
        this.txns = txns;
        this.idempotency = idempotency;
        this.challenges = challenges;
        this.cardService = cardService;
        this.inference = inference;
        this.network = network;
        this.events = events;
        this.pepper = pepper == null ? "" : pepper;
        this.stepUpTtlSeconds = stepUpTtlSeconds;
        this.clearingBudgetMs = clearingBudgetMs;
        this.attempts = new TransactionTemplate(manager);
        this.attempts.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Records one purchase. A missing {@code occurredAt} becomes the current UTC instant.
     * A value more than five minutes ahead is rejected. Past values are accepted.
     * <p>
     * When {@code idempotencyKey} is present, a repeat of the same request returns the original
     * row. The same key with a different body is a conflict. The key is stored only after the
     * purchase insert succeeds, in the same database transaction. A client retry of a failed
     * request is free to try again. The server does not retry the insert itself.
     */
    @Transactional
    public ClearingResult record(String email, Long cardId, CreateTransactionRequest req, String idempotencyKey,
                                 String presentedLast4, boolean panInvalid) {
        long started = System.nanoTime();
        String key = normalizeKey(idempotencyKey);
        String hash = key == null ? null : requestHash(cardId, req);
        if (key != null) {
            var prior = idempotency.findByOwnerEmailAndIdempotencyKey(email, key);
            if (prior.isPresent()) {
                return timed(new PostedTransaction(replay(prior.get(), cardId, hash), true), null, started);
            }
        }
        if (panInvalid) throw new BadRequestException("PAN must be 13 to 19 digits");
        Instant at = req.occurredAt() != null ? req.occurredAt() : Instant.now();
        if (at.isAfter(Instant.now().plus(MAX_FUTURE_SKEW))) {
            throw new BadRequestException("occurredAt cannot be more than 5 minutes in the future");
        }
        Card card = cardService.getOwnedForUpdate(email, cardId);
        if (card.getStatus() == CardStatus.BLOCKED) throw new BusinessRuleException("Card is blocked");
        if (presentedLast4 != null && !presentedLast4.equals(card.getLast4())) {
            throw new BusinessRuleException("PAN does not match this card reference");
        }
        if (key != null) {
            var open = challenges.findFirstByOwnerEmailAndIdempotencyKeyAndStatus(email, key, StepUpChallenge.PENDING);
            if (open.isPresent()) {
                if (open.get().getRequestHash() != null && !open.get().getRequestHash().equals(hash)) {
                    throw new ConflictException("This Idempotency-Key was already used for a different request");
                }
                return timed(null, stepUpView(open.get(), null), started);
            }
        }
        network.checkDynamicCvv(card.getCardToken(), req.dynamicCvv());
        network.observe(card.getCardToken());
        SpendChannel channel = req.channel() == null ? SpendChannel.API : req.channel();
        String location = FraudRuleEngine.place(req.location());
        CardTransaction prior = txns.findAtOrBefore(card.getId(), at, PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
        Long timeDelta = prior == null ? null : Duration.between(prior.getOccurredAt(), at).getSeconds();
        String previousPlace = prior == null ? null : FraudRuleEngine.place(prior.getLocation());
        boolean placeChanged = location != null && previousPlace != null && !previousPlace.equalsIgnoreCase(location);
        int highRisk = inference.highRisk(req.category()) ? 1 : 0;
        FraudAssessment assessment = inference.evaluate(card, req.amount(), at, location, req.category());
        if (inference.hold(assessment)) {
            return timed(null, openChallenge(email, card, req.amount(), req.merchant(), req.category(), at, channel,
                    location, assessment, timeDelta, placeChanged, highRisk, key, hash), started);
        }
        PostedTransaction posted = persist(email, card, req.amount(), req.merchant(), req.category(), at, channel,
                location, assessment, timeDelta, placeChanged, highRisk, key, hash, "CLEARED");
        return timed(posted, null, started);
    }

    /** Completes a step-up hold. A matching code writes the purchase. A wrong code writes nothing. */
    @Transactional
    public ClearingResult verifyStepUp(String email, Long cardId, Long challengeId, String code) {
        long started = System.nanoTime();
        Card card = cardService.getOwnedForUpdate(email, cardId);
        if (card.getStatus() == CardStatus.BLOCKED) throw new BusinessRuleException("Card is blocked");
        StepUpChallenge challenge = challenges.findByIdAndOwnerEmailAndCardId(challengeId, email, cardId)
                .orElseThrow(() -> new NotFoundException("Step-up challenge not found"));
        if (!StepUpChallenge.PENDING.equals(challenge.getStatus())) {
            throw new BusinessRuleException("Step-up challenge failed");
        }
        if (Instant.now().isAfter(challenge.getExpiresAt())) {
            closeChallenge(challenge.getId(), false);
            throw new BusinessRuleException("Step-up challenge has expired");
        }
        byte[] expected = HexFormat.of().parseHex(challenge.getCodeHash());
        byte[] actual = HexFormat.of().parseHex(stepUpHash(code));
        if (!MessageDigest.isEqual(expected, actual)) {
            boolean closed = closeChallenge(challenge.getId(), true);
            throw new BusinessRuleException(closed ? "Step-up challenge failed" : "Step-up challenge was not verified");
        }
        FraudAssessment assessment = assessmentOf(challenge);
        PostedTransaction posted = persist(email, card, challenge.getAmount(), challenge.getMerchant(),
                challenge.getCategory(), challenge.getOccurredAt(), challenge.getChannel(), challenge.getLocation(),
                assessment, challenge.getTimeDeltaSeconds(), challenge.isPlaceChanged(), challenge.getHighRiskCategory(),
                challenge.getIdempotencyKey(), challenge.getRequestHash(), "STEP_UP_VERIFIED");
        challenge.markVerified(posted.body().id());
        return timed(posted, null, started);
    }

    private PostedTransaction persist(String email, Card card, BigDecimal amount, String merchant, Category category,
                                      Instant at, SpendChannel channel, String location, FraudAssessment assessment,
                                      Long timeDelta, boolean placeChanged, int highRisk, String key, String hash,
                                      String outcome) {
        CardTransaction t = new CardTransaction(card, amount, merchant, category, at, channel, location);
        if (assessment.flagged()) t.flag(String.join(",", assessment.reasons()));
        String decision = decisionOf(assessment);
        t.applyClearing(decision, timeDelta, placeChanged, highRisk);
        CardTransaction saved = txns.saveAndFlush(t);
        if (key != null) {
            try {
                idempotency.saveAndFlush(new IdempotencyRecord(email, key, hash, saved.getId()));
            } catch (DataIntegrityViolationException ex) {
                throw new IdempotencyRace();
            }
        }
        events.publishEvent(new PurchaseCleared(
                saved.getId(), card.getId(), saved.getAmount(), timeDelta, placeChanged, highRisk,
                decision, assessment.reasons().isEmpty() ? "clean" : String.join(",", assessment.reasons()),
                assessment.riskScore(), outcome));
        return new PostedTransaction(toDto(saved, assessment), false);
    }

    private StepUpChallengeResponse openChallenge(String email, Card card, BigDecimal amount, String merchant,
                                                  Category category, Instant at, SpendChannel channel, String location,
                                                  FraudAssessment assessment, Long timeDelta, boolean placeChanged,
                                                  int highRisk, String key, String hash) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        String reasons = assessment.reasons().isEmpty() ? "clean" : String.join(",", assessment.reasons());
        StepUpChallenge saved = challenges.saveAndFlush(new StepUpChallenge(
                email, card.getId(), amount, merchant, category, at, channel, location, reasons,
                assessment.riskScore(), timeDelta, placeChanged, highRisk, stepUpHash(code),
                Instant.now().plusSeconds(stepUpTtlSeconds), key, hash));
        return stepUpView(saved, code);
    }

    /** @return true when the challenge is now closed */
    private boolean closeChallenge(Long challengeId, boolean countAttempt) {
        return Boolean.TRUE.equals(attempts.execute(status -> {
            StepUpChallenge current = challenges.findById(challengeId)
                    .orElseThrow(() -> new NotFoundException("Step-up challenge not found"));
            if (countAttempt) current.addAttempt();
            boolean closed = !countAttempt || current.getAttempts() >= STEP_UP_ATTEMPTS;
            if (closed) {
                current.markFailed();
                events.publishEvent(new StepUpDeclined(current.getId(), current.getCardId(), current.getAmount(),
                        current.getRiskScore(), current.getReasons()));
            }
            return closed;
        }));
    }

    private static FraudAssessment assessmentOf(StepUpChallenge challenge) {
        List<String> reasons = "clean".equals(challenge.getReasons())
                ? List.of() : List.of(challenge.getReasons().split(","));
        return new FraudAssessment(reasons, challenge.getRiskScore(), reasons.contains("STEP_UP"));
    }

    private static StepUpChallengeResponse stepUpView(StepUpChallenge challenge, String code) {
        return new StepUpChallengeResponse(challenge.getId(), challenge.getCardId(), "STEP_UP_REQUIRED",
                StepUpChallenge.METHOD, challenge.getRiskScore(), challenge.getReasons(), challenge.getExpiresAt(), code);
    }

    private String stepUpHash(String code) {
        return CardTokenHasher.sha256Hex(pepper + "|step-up|" + code);
    }

    private ClearingResult timed(PostedTransaction posted, StepUpChallengeResponse stepUp, long started) {
        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
        if (latencyMs > clearingBudgetMs) {
            log.warn("clearing pipeline took {} ms; budget is {} ms", latencyMs, clearingBudgetMs);
        }
        return new ClearingResult(posted, stepUp, latencyMs);
    }

    /** Used after a concurrent insert won the unique key. Runs in a fresh transaction. */
    @Transactional(readOnly = true)
    public TransactionResponse replay(String email, Long cardId, CreateTransactionRequest req, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        IdempotencyRecord prior = idempotency.findByOwnerEmailAndIdempotencyKey(email, key)
                .orElseThrow(() -> new ConflictException("Idempotency key is in use. Retry this request."));
        return replay(prior, cardId, requestHash(cardId, req));
    }

    private TransactionResponse replay(IdempotencyRecord prior, Long cardId, String hash) {
        if (!prior.getRequestHash().equals(hash)) {
            throw new ConflictException("This Idempotency-Key was already used for a different request");
        }
        CardTransaction existing = txns.findById(prior.getTransactionId())
                .orElseThrow(() -> new NotFoundException("Transaction not found"));
        if (!existing.getCard().getId().equals(cardId)) {
            throw new ConflictException("This Idempotency-Key was already used for a different card");
        }
        return toDto(existing);
    }

    private static String normalizeKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String key = raw.trim();
        if (!key.matches("[A-Za-z0-9_.:\\-]{8,80}")) {
            throw new BadRequestException("Idempotency-Key must be 8 to 80 letters, digits, or . _ : -");
        }
        return key;
    }

    private static String requestHash(Long cardId, CreateTransactionRequest req) {
        String occurred = req.occurredAt() == null ? "" : req.occurredAt().toString();
        String channel = req.channel() == null ? SpendChannel.API.name() : req.channel().name();
        String location = FraudRuleEngine.place(req.location());
        String dcvv = req.dynamicCvv() == null ? "" : req.dynamicCvv();
        String canonical = cardId + "|" + req.amount().toPlainString() + "|" + req.merchant()
                + "|" + req.category() + "|" + occurred + "|" + channel + "|" + (location == null ? "" : location)
                + "|" + dcvv;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
        FraudAssessment assessment = t.isFlagged() && t.getFlagReason() != null
                ? new FraudAssessment(List.of(t.getFlagReason().split(",")),
                FraudRuleEngine.score(List.of(t.getFlagReason().split(","))),
                t.getFlagReason().contains("STEP_UP"))
                : new FraudAssessment(List.of(), 0, false);
        return toDto(t, assessment);
    }

    static TransactionResponse toDto(CardTransaction t, FraudAssessment assessment) {
        String channel = t.getChannel() == null ? SpendChannel.API.name() : t.getChannel().name();
        Card card = t.getCard();
        return new TransactionResponse(t.getId(), card.getId(), t.getAmount(), t.getMerchant(),
                t.getCategory(), t.getOccurredAt(), t.isFlagged(), t.getFlagReason(), channel, t.getLocation(),
                card.getAccountReferenceId(), card.getCardToken(),
                assessment == null ? 0 : assessment.riskScore(),
                assessment != null && assessment.stepUpSuggested(),
                t.getClearingDecision() != null ? t.getClearingDecision() : decisionOf(assessment),
                t.getTimeDeltaSeconds(), t.isPlaceChanged(), t.getHighRiskCategory());
    }

    static String decisionOf(FraudAssessment assessment) {
        if (assessment != null && assessment.stepUpSuggested()) return "STEP_UP";
        if (assessment != null && assessment.flagged()) return "FLAGGED";
        return "AUTHORIZED";
    }
}
