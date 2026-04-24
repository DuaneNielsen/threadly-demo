package com.threadly.payments.charge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChargeServiceTest {

    @Mock
    ChargeRepository repository;

    @InjectMocks
    ChargeService service;

    @BeforeEach
    void echoSave() {
        lenient().when(repository.save(any(Charge.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void visaSuccessCardReturnsSucceeded() {
        Charge c = service.create(req("4242424242424242"));

        assertThat(c.getStatus()).isEqualTo("succeeded");
        assertThat(c.getFailureCode()).isNull();
        assertThat(c.getLast4()).isEqualTo("4242");
        assertThat(c.getBrand()).isEqualTo("visa");
        assertThat(c.getId()).startsWith("ch_");
    }

    @Test
    void declineCardReturnsFailedCardDeclined() {
        Charge c = service.create(req("4000000000000002"));

        assertThat(c.getStatus()).isEqualTo("failed");
        assertThat(c.getFailureCode()).isEqualTo("card_declined");
        assertThat(c.getFailureMessage()).contains("declined");
        assertThat(c.getLast4()).isEqualTo("0002");
    }

    @Test
    void insufficientFundsCardReturnsFailedInsufficientFunds() {
        Charge c = service.create(req("4000000000009995"));

        assertThat(c.getStatus()).isEqualTo("failed");
        assertThat(c.getFailureCode()).isEqualTo("insufficient_funds");
    }

    @Test
    void expiredCardReturnsFailedExpired() {
        Charge c = service.create(req("4000000000000069"));

        assertThat(c.getStatus()).isEqualTo("failed");
        assertThat(c.getFailureCode()).isEqualTo("expired_card");
    }

    @Test
    void trigger0119CardThrowsRuntimeException() {
        assertThatThrownBy(() -> service.create(req("4000000000000119")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("internal error");

        verify(repository, never()).save(any(Charge.class));
    }

    @Test
    void cardNumberWithWhitespaceIsNormalizedBeforeClassification() {
        Charge c = service.create(req("4242 4242 4242 4242"));

        assertThat(c.getStatus()).isEqualTo("succeeded");
        assertThat(c.getLast4()).isEqualTo("4242");
    }

    @Test
    void idempotencyKeyReplayReturnsExistingCharge() {
        Charge existing = new Charge();
        existing.setId("ch_existing");
        existing.setStatus("succeeded");
        existing.setIdempotencyKey("key-1");
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        ChargeRequest r = req("4242424242424242");
        r.setIdempotency_key("key-1");
        Charge c = service.create(r);

        assertThat(c).isSameAs(existing);
        verify(repository, never()).save(any(Charge.class));
    }

    @Test
    void newIdempotencyKeyFlowsThroughToSave() {
        when(repository.findByIdempotencyKey("fresh")).thenReturn(Optional.empty());

        ChargeRequest r = req("4242424242424242");
        r.setIdempotency_key("fresh");
        service.create(r);

        ArgumentCaptor<Charge> captor = ArgumentCaptor.forClass(Charge.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("fresh");
    }

    @Test
    void brandDetectionCoversVisaMastercardAmex() {
        assertThat(service.create(req("4242424242424242")).getBrand()).isEqualTo("visa");
        assertThat(service.create(req("5454545454545454")).getBrand()).isEqualTo("mastercard");
        assertThat(service.create(req("378282246310005")).getBrand()).isEqualTo("amex");
    }

    private static ChargeRequest req(String cardNumber) {
        ChargeRequest r = new ChargeRequest();
        r.setAmount(4500L);
        r.setCurrency("usd");
        r.setDescription("test");
        ChargeRequest.Card card = new ChargeRequest.Card();
        card.setNumber(cardNumber);
        card.setExp_month(12);
        card.setExp_year(2030);
        card.setCvc("123");
        r.setCard(card);
        return r;
    }
}
