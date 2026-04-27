package com.threadly.totals;

import com.threadly.cart.CartService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class TotalsCalculator {

    public static final BigDecimal SHIPPING_FLAT = new BigDecimal("5.00");
    public static final BigDecimal TAX_RATE = new BigDecimal("0.10");

    public Totals compute(List<CartService.Line> lines) {
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartService.Line l : lines) {
            subtotal = subtotal.add(l.getLineTotal());
        }
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);

        BigDecimal shipping = lines.isEmpty() ? BigDecimal.ZERO.setScale(2) : SHIPPING_FLAT;
        BigDecimal tax = subtotal.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(shipping).add(tax);

        return new Totals(subtotal, shipping, tax, total);
    }

    public static class Totals {
        private final BigDecimal subtotal;
        private final BigDecimal shipping;
        private final BigDecimal tax;
        private final BigDecimal total;

        public Totals(BigDecimal subtotal, BigDecimal shipping, BigDecimal tax, BigDecimal total) {
            this.subtotal = subtotal;
            this.shipping = shipping;
            this.tax = tax;
            this.total = total;
        }

        public BigDecimal getSubtotal() { return subtotal; }
        public BigDecimal getShipping() { return shipping; }
        public BigDecimal getTax() { return tax; }
        public BigDecimal getTotal() { return total; }

        public long totalCents() {
            return total.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
    }
}
