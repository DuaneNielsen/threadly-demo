package com.threadly.totals;

import com.threadly.cart.CartService;
import com.threadly.product.Product;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TotalsCalculatorTest {

    private final TotalsCalculator calc = new TotalsCalculator();

    @Test
    void singleLineProducesFlatShippingAndTenPercentTax() {
        List<CartService.Line> lines = List.of(line("24.00", 1));

        TotalsCalculator.Totals t = calc.compute(lines);

        assertThat(t.getSubtotal()).isEqualByComparingTo("24.00");
        assertThat(t.getShipping()).isEqualByComparingTo("5.00");
        assertThat(t.getTax()).isEqualByComparingTo("2.40");
        assertThat(t.getTotal()).isEqualByComparingTo("31.40");
    }

    @Test
    void multipleLinesSumQtyByPrice() {
        List<CartService.Line> lines = List.of(
                line("24.00", 2),
                line("32.00", 1)
        );

        TotalsCalculator.Totals t = calc.compute(lines);

        assertThat(t.getSubtotal()).isEqualByComparingTo("80.00");
        assertThat(t.getTax()).isEqualByComparingTo("8.00");
        assertThat(t.getTotal()).isEqualByComparingTo("93.00");
    }

    @Test
    void taxRoundsHalfUpToTwoDecimals() {
        // 24.95 * 0.10 = 2.495 -> 2.50 under HALF_UP
        List<CartService.Line> lines = List.of(line("24.95", 1));

        TotalsCalculator.Totals t = calc.compute(lines);

        assertThat(t.getTax()).isEqualByComparingTo("2.50");
    }

    @Test
    void emptyCartHasZeroShipping() {
        TotalsCalculator.Totals t = calc.compute(List.of());

        assertThat(t.getSubtotal()).isEqualByComparingTo("0.00");
        assertThat(t.getShipping()).isEqualByComparingTo("0.00");
        assertThat(t.getTax()).isEqualByComparingTo("0.00");
        assertThat(t.getTotal()).isEqualByComparingTo("0.00");
    }

    @Test
    void totalCentsConvertsDollarsToCents() {
        List<CartService.Line> lines = List.of(line("24.00", 1));

        TotalsCalculator.Totals t = calc.compute(lines);

        assertThat(t.totalCents()).isEqualTo(3140L);
    }

    private static CartService.Line line(String price, int qty) {
        Product p = new Product();
        p.setPrice(new BigDecimal(price));
        return new CartService.Line(p, qty);
    }
}
