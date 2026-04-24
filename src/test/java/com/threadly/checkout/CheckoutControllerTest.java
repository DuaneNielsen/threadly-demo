package com.threadly.checkout;

import com.threadly.order.Order;
import com.threadly.order.OrderRepository;
import com.threadly.payment.ChargeResponse;
import com.threadly.payment.PaymentClient;
import com.threadly.payment.PaymentException;
import com.threadly.product.Product;
import com.threadly.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckoutControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    OrderRepository orderRepository;

    @MockBean
    PaymentClient paymentClient;

    Product segfault;

    @BeforeEach
    void seedProducts() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        segfault = new Product();
        segfault.setName("Segfault");
        segfault.setPrice(new BigDecimal("24.00"));
        segfault.setStock(100);
        segfault.setCategory("tees");
        segfault.setColor("black");
        segfault.setSize("M");
        segfault.setImageUrl("/images/segfault.png");
        segfault = productRepository.save(segfault);
    }

    @Test
    void getCheckoutRedirectsToCartWhenEmpty() throws Exception {
        mvc.perform(get("/checkout"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cart"));
    }

    @Test
    void getCheckoutRendersFormWhenCartHasItems() throws Exception {
        MockHttpSession session = addToCartViaPost();

        mvc.perform(get("/checkout").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("checkout/form"))
                .andExpect(model().attributeExists("form", "lines", "totals"));
    }

    @Test
    void successfulPaymentRedirectsToOrderConfirmationAndClearsCart() throws Exception {
        MockHttpSession session = addToCartViaPost();
        when(paymentClient.charge(anyLong(), anyString(), anyInt(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(chargeResponse("ch_ok_123", "succeeded", null, null));

        MvcResult result = mvc.perform(post("/checkout").session(session)
                .param("email", "test@example.com")
                .param("shipName", "Test")
                .param("shipAddr1", "123 Main")
                .param("shipCity", "SF")
                .param("shipState", "CA")
                .param("shipZip", "94105")
                .param("cardNumber", "4242424242424242")
                .param("cardExpMonth", "12")
                .param("cardExpYear", "2030")
                .param("cardCvc", "123"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("/orders/");
        Long orderId = Long.parseLong(location.substring("/orders/".length()));

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(Order.Status.paid);
        assertThat(order.getPaymentChargeId()).isEqualTo("ch_ok_123");
        assertThat(order.getItems()).hasSize(1);
        assertThat(order.getSubtotal()).isEqualByComparingTo("24.00");
        assertThat(order.getTotal()).isEqualByComparingTo("31.40"); // 24 + 5 + 2.40

        // Cart should be empty after paid checkout
        mvc.perform(get("/cart").session(session))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Your cart is empty")));
    }

    @Test
    void declinedPaymentRerendersFormWithErrorBannerAndMarksOrderFailed() throws Exception {
        MockHttpSession session = addToCartViaPost();
        when(paymentClient.charge(anyLong(), anyString(), anyInt(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(chargeResponse("ch_decl_456", "failed", "card_declined", "Your card was declined."));

        mvc.perform(post("/checkout").session(session)
                .param("email", "test@example.com")
                .param("shipName", "Test")
                .param("shipAddr1", "123 Main")
                .param("shipCity", "SF")
                .param("shipState", "CA")
                .param("shipZip", "94105")
                .param("cardNumber", "4000000000000002")
                .param("cardExpMonth", "12")
                .param("cardExpYear", "2030")
                .param("cardCvc", "123"))
                .andExpect(status().isOk())
                .andExpect(view().name("checkout/form"))
                .andExpect(model().attribute("paymentError", "Your card was declined."));

        List<Order> orders = orderRepository.findAll();
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getStatus()).isEqualTo(Order.Status.failed);
        assertThat(orders.get(0).getPaymentChargeId()).isEqualTo("ch_decl_456");
    }

    @Test
    void paymentExceptionRerendersFormWithFriendlyError() throws Exception {
        MockHttpSession session = addToCartViaPost();
        when(paymentClient.charge(anyLong(), anyString(), anyInt(), anyInt(), anyString(), anyString(), anyString()))
                .thenThrow(new PaymentException("connection refused"));

        mvc.perform(post("/checkout").session(session)
                .param("email", "test@example.com")
                .param("shipName", "Test")
                .param("shipAddr1", "123 Main")
                .param("shipCity", "SF")
                .param("shipState", "CA")
                .param("shipZip", "94105")
                .param("cardNumber", "4242424242424242")
                .param("cardExpMonth", "12")
                .param("cardExpYear", "2030")
                .param("cardCvc", "123"))
                .andExpect(status().isOk())
                .andExpect(view().name("checkout/form"))
                .andExpect(model().attribute("paymentError",
                        org.hamcrest.Matchers.containsString("couldn't reach")));

        List<Order> orders = orderRepository.findAll();
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getStatus()).isEqualTo(Order.Status.failed);
        assertThat(orders.get(0).getPaymentChargeId()).isNull();
    }

    @Test
    void postCheckoutWithEmptyCartRedirectsToCart() throws Exception {
        mvc.perform(post("/checkout")
                .param("email", "x@y.com")
                .param("shipName", "Test")
                .param("shipAddr1", "1")
                .param("shipCity", "SF")
                .param("shipState", "CA")
                .param("shipZip", "94105")
                .param("cardNumber", "4242424242424242")
                .param("cardExpMonth", "12")
                .param("cardExpYear", "2030")
                .param("cardCvc", "123"))
                .andExpect(redirectedUrl("/cart"));

        assertThat(orderRepository.findAll()).isEmpty();
    }

    private MockHttpSession addToCartViaPost() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/cart/add/" + segfault.getId()).session(session))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    private static ChargeResponse chargeResponse(String id, String status, String failureCode, String failureMessage) {
        ChargeResponse r = new ChargeResponse();
        r.setId(id);
        r.setStatus(status);
        r.setAmount(3140L);
        r.setCurrency("usd");
        r.setLast4("4242");
        r.setBrand("visa");
        r.setFailure_code(failureCode);
        r.setFailure_message(failureMessage);
        return r;
    }
}
