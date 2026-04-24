package com.threadly.payments.charge;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public class ChargeResponse {

    private String id;
    private String status;
    private Long amount;
    private String currency;
    private String last4;
    private String brand;
    private Instant created;
    private String failure_code;
    private String failure_message;

    public static ChargeResponse fromEntity(Charge c) {
        ChargeResponse r = new ChargeResponse();
        r.id = c.getId();
        r.status = c.getStatus();
        r.amount = c.getAmount();
        r.currency = c.getCurrency();
        r.last4 = c.getLast4();
        r.brand = c.getBrand();
        r.created = c.getCreated();
        r.failure_code = c.getFailureCode();
        r.failure_message = c.getFailureMessage();
        return r;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getLast4() { return last4; }
    public void setLast4(String last4) { this.last4 = last4; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public Instant getCreated() { return created; }
    public void setCreated(Instant created) { this.created = created; }

    public String getFailure_code() { return failure_code; }
    public void setFailure_code(String failure_code) { this.failure_code = failure_code; }

    public String getFailure_message() { return failure_message; }
    public void setFailure_message(String failure_message) { this.failure_message = failure_message; }
}
