package com.threadly.product;

import com.threadly.discount.DiscountCalculator;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;

@Controller
public class ProductController {

    private final ProductRepository productRepository;
    private final DiscountCalculator discountCalculator;

    public ProductController(ProductRepository productRepository, DiscountCalculator discountCalculator) {
        this.productRepository = productRepository;
        this.discountCalculator = discountCalculator;
    }

    @GetMapping("/")
    public String index() {
        return "redirect:/products";
    }

    @GetMapping("/products")
    public String list(Model model) {
        model.addAttribute("products", productRepository.findAll());
        return "products/list";
    }

    @GetMapping("/products/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));
        BigDecimal percentOff = discountCalculator.percentOff(product.getOriginalPrice(), product.getPrice());
        model.addAttribute("product", product);
        model.addAttribute("percentOff", percentOff);
        return "products/detail";
    }
}
