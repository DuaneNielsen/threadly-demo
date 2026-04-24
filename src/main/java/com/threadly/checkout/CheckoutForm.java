package com.threadly.checkout;

public class CheckoutForm {
    private String email;
    private String shipName;
    private String shipAddr1;
    private String shipAddr2;
    private String shipCity;
    private String shipState;
    private String shipZip;

    private String cardNumber;
    private Integer cardExpMonth;
    private Integer cardExpYear;
    private String cardCvc;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getShipName() { return shipName; }
    public void setShipName(String shipName) { this.shipName = shipName; }

    public String getShipAddr1() { return shipAddr1; }
    public void setShipAddr1(String shipAddr1) { this.shipAddr1 = shipAddr1; }

    public String getShipAddr2() { return shipAddr2; }
    public void setShipAddr2(String shipAddr2) { this.shipAddr2 = shipAddr2; }

    public String getShipCity() { return shipCity; }
    public void setShipCity(String shipCity) { this.shipCity = shipCity; }

    public String getShipState() { return shipState; }
    public void setShipState(String shipState) { this.shipState = shipState; }

    public String getShipZip() { return shipZip; }
    public void setShipZip(String shipZip) { this.shipZip = shipZip; }

    public String getCardNumber() { return cardNumber; }
    public void setCardNumber(String cardNumber) { this.cardNumber = cardNumber; }

    public Integer getCardExpMonth() { return cardExpMonth; }
    public void setCardExpMonth(Integer cardExpMonth) { this.cardExpMonth = cardExpMonth; }

    public Integer getCardExpYear() { return cardExpYear; }
    public void setCardExpYear(Integer cardExpYear) { this.cardExpYear = cardExpYear; }

    public String getCardCvc() { return cardCvc; }
    public void setCardCvc(String cardCvc) { this.cardCvc = cardCvc; }
}
