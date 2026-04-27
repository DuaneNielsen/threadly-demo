package com.threadly.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    private final RestClient client;
    private final String baseUrl;

    public PaymentClient(@Value("${payments.url:http://localhost:8181}") String baseUrl) {
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public ChargeResponse charge(long amountCents,
                                 String cardNumber,
                                 int expMonth,
                                 int expYear,
                                 String cvc,
                                 String description,
                                 String idempotencyKey) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("number", cardNumber);
        card.put("exp_month", expMonth);
        card.put("exp_year", expYear);
        card.put("cvc", cvc);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountCents);
        body.put("currency", "usd");
        body.put("card", card);
        body.put("description", description);
        if (idempotencyKey != null) body.put("idempotency_key", idempotencyKey);

        try {
            return client.post()
                    .uri("/v1/charges")
                    .body(body)
                    .retrieve()
                    .body(ChargeResponse.class);
        } catch (Exception e) {
            log.error("Payment call to {} failed: {}", baseUrl, e.getMessage());
            throw new PaymentException("Payment service call failed: " + e.getMessage(), e);
        }
    }
}
