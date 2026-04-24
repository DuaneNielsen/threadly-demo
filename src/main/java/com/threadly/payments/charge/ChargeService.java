package com.threadly.payments.charge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ChargeService {

    private static final Logger log = LoggerFactory.getLogger(ChargeService.class);

    private final ChargeRepository repository;

    public ChargeService(ChargeRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Charge create(ChargeRequest request) {
        String idem = request.getIdempotency_key();
        if (idem != null && !idem.isBlank()) {
            Optional<Charge> existing = repository.findByIdempotencyKey(idem);
            if (existing.isPresent()) {
                log.info("Idempotent replay for key={}, charge_id={}", idem, existing.get().getId());
                return existing.get();
            }
        }

        String number = request.getCard().getNumber().replaceAll("\\s+", "");
        TestCard outcome = classify(number);

        if (outcome.throwException) {
            log.error("Payment processor internal error for card ending {}", last4(number));
            throw new RuntimeException("Payment processor internal error");
        }

        Charge c = new Charge();
        c.setId(generateId());
        c.setAmount(request.getAmount());
        c.setCurrency(request.getCurrency() == null ? "usd" : request.getCurrency().toLowerCase());
        c.setDescription(request.getDescription());
        c.setIdempotencyKey(idem);
        c.setLast4(last4(number));
        c.setBrand(brand(number));
        c.setCreated(Instant.now());

        if (outcome.succeeded) {
            c.setStatus("succeeded");
            log.info("Charge {} succeeded: amount={} {}, last4={}", c.getId(), c.getAmount(), c.getCurrency(), c.getLast4());
        } else {
            c.setStatus("failed");
            c.setFailureCode(outcome.failureCode);
            c.setFailureMessage(outcome.failureMessage);
            log.warn("Charge {} failed: code={}, last4={}", c.getId(), outcome.failureCode, c.getLast4());
        }

        return repository.save(c);
    }

    public Optional<Charge> findById(String id) {
        return repository.findById(id);
    }

    private static String last4(String number) {
        return number.length() >= 4 ? number.substring(number.length() - 4) : number;
    }

    private static String brand(String number) {
        if (number.startsWith("4")) return "visa";
        if (number.startsWith("5")) return "mastercard";
        if (number.startsWith("34") || number.startsWith("37")) return "amex";
        return "unknown";
    }

    private static String generateId() {
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder("ch_");
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 24; i++) sb.append(hex[r.nextInt(16)]);
        return sb.toString();
    }

    private static TestCard classify(String number) {
        switch (number) {
            case "4000000000000002":
                return TestCard.fail("card_declined", "Your card was declined.");
            case "4000000000009995":
                return TestCard.fail("insufficient_funds", "Your card has insufficient funds.");
            case "4000000000000069":
                return TestCard.fail("expired_card", "Your card has expired.");
            case "4000000000000119":
                return TestCard.boom();
            default:
                return TestCard.ok();
        }
    }

    private static class TestCard {
        boolean succeeded;
        boolean throwException;
        String failureCode;
        String failureMessage;

        static TestCard ok() {
            TestCard t = new TestCard();
            t.succeeded = true;
            return t;
        }

        static TestCard fail(String code, String msg) {
            TestCard t = new TestCard();
            t.succeeded = false;
            t.failureCode = code;
            t.failureMessage = msg;
            return t;
        }

        static TestCard boom() {
            TestCard t = new TestCard();
            t.throwException = true;
            return t;
        }
    }
}
