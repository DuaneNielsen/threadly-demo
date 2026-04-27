package com.threadly.payments.charge;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "charges")
public class Charge {

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 4)
    private String last4;

    @Column(length = 16)
    private String brand;

    @Column(length = 255)
    private String description;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "failure_code", length = 32)
    private String failureCode;

    @Column(name = "failure_message", length = 255)
    private String failureMessage;

    @Column(nullable = false)
    private Instant created;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getLast4() { return last4; }
    public void setLast4(String last4) { this.last4 = last4; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }

    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }

    public Instant getCreated() { return created; }
    public void setCreated(Instant created) { this.created = created; }
}
