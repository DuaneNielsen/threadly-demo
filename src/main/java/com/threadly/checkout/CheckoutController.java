package com.threadly.checkout;

import com.threadly.cart.CartService;
import com.threadly.order.Order;
import com.threadly.order.OrderItem;
import com.threadly.order.OrderRepository;
import com.threadly.payment.ChargeResponse;
import com.threadly.payment.PaymentClient;
import com.threadly.payment.PaymentException;
import com.threadly.totals.TotalsCalculator;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.util.List;

@Controller
@RequestMapping("/checkout")
public class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    private final CartService cartService;
    private final TotalsCalculator totalsCalculator;
    private final OrderRepository orderRepository;
    private final PaymentClient paymentClient;

    public CheckoutController(CartService cartService,
                              TotalsCalculator totalsCalculator,
                              OrderRepository orderRepository,
                              PaymentClient paymentClient) {
        this.cartService = cartService;
        this.totalsCalculator = totalsCalculator;
        this.orderRepository = orderRepository;
        this.paymentClient = paymentClient;
    }

    @GetMapping
    public String view(HttpSession session, Model model) {
        if (cartService.isEmpty(session)) {
            return "redirect:/cart";
        }
        List<CartService.Line> lines = cartService.items(session);
        model.addAttribute("lines", lines);
        model.addAttribute("totals", totalsCalculator.compute(lines));
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CheckoutForm());
        }
        return "checkout/form";
    }

    @PostMapping
    @Transactional
    public String submit(@ModelAttribute("form") CheckoutForm form,
                         HttpSession session,
                         Model model) {
        if (cartService.isEmpty(session)) {
            return "redirect:/cart";
        }

        List<CartService.Line> lines = cartService.items(session);
        TotalsCalculator.Totals totals = totalsCalculator.compute(lines);

        Order order = new Order();
        order.setEmail(form.getEmail());
        order.setShipName(form.getShipName());
        order.setShipAddr1(form.getShipAddr1());
        order.setShipAddr2(form.getShipAddr2());
        order.setShipCity(form.getShipCity());
        order.setShipState(form.getShipState());
        order.setShipZip(form.getShipZip());
        order.setSubtotal(totals.getSubtotal());
        order.setShipping(totals.getShipping());
        order.setTax(totals.getTax());
        order.setTotal(totals.getTotal());
        order.setStatus(Order.Status.pending);
        order.setCreatedAt(Instant.now());
        order.setSessionId(session.getId());

        for (CartService.Line line : lines) {
            OrderItem oi = new OrderItem();
            oi.setProductId(line.getProduct().getId());
            oi.setNameSnapshot(line.getProduct().getName());
            oi.setPriceSnapshot(line.getProduct().getPrice());
            oi.setQty(line.getQty());
            order.addItem(oi);
        }

        order = orderRepository.save(order);
        log.info("Order {} created (pending): total={}, items={}", order.getId(), order.getTotal(), order.getItems().size());

        String idempotencyKey = "order-" + order.getId();

        try {
            ChargeResponse charge = paymentClient.charge(
                    totals.totalCents(),
                    form.getCardNumber() == null ? "" : form.getCardNumber().replaceAll("\\s+", ""),
                    form.getCardExpMonth() == null ? 1 : form.getCardExpMonth(),
                    form.getCardExpYear() == null ? 2030 : form.getCardExpYear(),
                    form.getCardCvc() == null ? "" : form.getCardCvc(),
                    "Threadly order #" + order.getId(),
                    idempotencyKey
            );

            if (charge.isSucceeded()) {
                order.setStatus(Order.Status.paid);
                order.setPaymentChargeId(charge.getId());
                orderRepository.save(order);
                cartService.clear(session);
                log.info("Order {} paid: charge={}", order.getId(), charge.getId());
                return "redirect:/orders/" + order.getId();
            } else {
                order.setStatus(Order.Status.failed);
                order.setPaymentChargeId(charge.getId());
                orderRepository.save(order);
                String msg = charge.getFailure_message() != null ? charge.getFailure_message() : "Payment was declined.";
                log.warn("Order {} declined: code={}", order.getId(), charge.getFailure_code());
                return rerenderWithError(model, lines, totals, form, msg);
            }
        } catch (PaymentException e) {
            order.setStatus(Order.Status.failed);
            orderRepository.save(order);
            log.error("Order {} payment failed (network/5xx): {}", order.getId(), e.getMessage());
            return rerenderWithError(model, lines, totals, form,
                    "We couldn't reach the payment processor. Please try again in a moment.");
        }
    }

    private String rerenderWithError(Model model,
                                     List<CartService.Line> lines,
                                     TotalsCalculator.Totals totals,
                                     CheckoutForm form,
                                     String errorMessage) {
        model.addAttribute("lines", lines);
        model.addAttribute("totals", totals);
        model.addAttribute("form", form);
        model.addAttribute("paymentError", errorMessage);
        return "checkout/form";
    }
}
