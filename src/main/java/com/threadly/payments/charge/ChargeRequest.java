package com.threadly.payments.charge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class ChargeRequest {

    @NotNull
    @Min(1)
    private Long amount;

    @NotBlank
    private String currency;

    @NotNull
    @Valid
    private Card card;

    private String idempotency_key;

    private String description;

    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Card getCard() { return card; }
    public void setCard(Card card) { this.card = card; }

    public String getIdempotency_key() { return idempotency_key; }
    public void setIdempotency_key(String idempotency_key) { this.idempotency_key = idempotency_key; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public static class Card {
        @NotBlank
        private String number;

        @NotNull
        @Min(1)
        private Integer exp_month;

        @NotNull
        @Min(2000)
        private Integer exp_year;

        @NotBlank
        private String cvc;

        public String getNumber() { return number; }
        public void setNumber(String number) { this.number = number; }

        public Integer getExp_month() { return exp_month; }
        public void setExp_month(Integer exp_month) { this.exp_month = exp_month; }

        public Integer getExp_year() { return exp_year; }
        public void setExp_year(Integer exp_year) { this.exp_year = exp_year; }

        public String getCvc() { return cvc; }
        public void setCvc(String cvc) { this.cvc = cvc; }
    }
}
