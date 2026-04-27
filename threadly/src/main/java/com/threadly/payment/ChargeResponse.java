package com.threadly.payment;

public class ChargeResponse {
    private String id;
    private String status;
    private Long amount;
    private String currency;
    private String last4;
    private String brand;
    private String created;
    private String failure_code;
    private String failure_message;

    public boolean isSucceeded() {
        return "succeeded".equalsIgnoreCase(status);
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

    public String getCreated() { return created; }
    public void setCreated(String created) { this.created = created; }

    public String getFailure_code() { return failure_code; }
    public void setFailure_code(String failure_code) { this.failure_code = failure_code; }

    public String getFailure_message() { return failure_message; }
    public void setFailure_message(String failure_message) { this.failure_message = failure_message; }
}
