package com.threadly.cart;

import com.threadly.totals.TotalsCalculator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/cart")
public class CartController {

    private final CartService cartService;
    private final TotalsCalculator totalsCalculator;

    public CartController(CartService cartService, TotalsCalculator totalsCalculator) {
        this.cartService = cartService;
        this.totalsCalculator = totalsCalculator;
    }

    @GetMapping
    public String view(HttpSession session, Model model) {
        List<CartService.Line> lines = cartService.items(session);
        model.addAttribute("lines", lines);
        model.addAttribute("totals", totalsCalculator.compute(lines));
        return "cart/view";
    }

    @PostMapping("/add/{id}")
    public String add(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        cartService.add(session, id);
        String referer = request.getHeader("referer");
        if (referer != null && !referer.isBlank()) {
            return "redirect:" + referer;
        }
        return "redirect:/cart";
    }

    @PostMapping("/update")
    public String update(@RequestParam Long id, @RequestParam int qty, HttpSession session) {
        cartService.update(session, id, qty);
        return "redirect:/cart";
    }

    @PostMapping("/remove/{id}")
    public String remove(@PathVariable Long id, HttpSession session) {
        cartService.remove(session, id);
        return "redirect:/cart";
    }
}
