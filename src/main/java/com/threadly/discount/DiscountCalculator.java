package com.threadly.discount;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class DiscountCalculator {

    /**
     * Compute the discount percentage: (originalPrice - salePrice) / originalPrice * 100
     * Returns 0 if there is no original price (no markdown).
     */
    public BigDecimal percentOff(BigDecimal originalPrice, BigDecimal salePrice) {
        BigDecimal diff = originalPrice.subtract(salePrice);
        return diff.divide(originalPrice, 4, RoundingMode.HALF_UP)
                   .multiply(BigDecimal.valueOf(100))
                   .setScale(0, RoundingMode.HALF_UP);
    }
}
