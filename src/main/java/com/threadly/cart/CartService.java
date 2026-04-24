package com.threadly.cart;

import com.threadly.product.Product;
import com.threadly.product.ProductRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CartService {

    public static final String SESSION_KEY = "cart";

    private final ProductRepository productRepository;

    public CartService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @SuppressWarnings("unchecked")
    private LinkedHashMap<Long, Integer> cart(HttpSession session) {
        LinkedHashMap<Long, Integer> c = (LinkedHashMap<Long, Integer>) session.getAttribute(SESSION_KEY);
        if (c == null) {
            c = new LinkedHashMap<>();
            session.setAttribute(SESSION_KEY, c);
        }
        return c;
    }

    public void add(HttpSession session, Long productId) {
        LinkedHashMap<Long, Integer> c = cart(session);
        c.merge(productId, 1, Integer::sum);
    }

    public void update(HttpSession session, Long productId, int qty) {
        LinkedHashMap<Long, Integer> c = cart(session);
        if (qty <= 0) {
            c.remove(productId);
        } else {
            c.put(productId, qty);
        }
    }

    public void remove(HttpSession session, Long productId) {
        cart(session).remove(productId);
    }

    public void clear(HttpSession session) {
        cart(session).clear();
    }

    public int totalQty(HttpSession session) {
        return cart(session).values().stream().mapToInt(Integer::intValue).sum();
    }

    public boolean isEmpty(HttpSession session) {
        return cart(session).isEmpty();
    }

    public List<Line> items(HttpSession session) {
        LinkedHashMap<Long, Integer> c = cart(session);
        if (c.isEmpty()) return List.of();
        List<Product> products = productRepository.findAllById(c.keySet());
        Map<Long, Product> byId = new LinkedHashMap<>();
        for (Product p : products) byId.put(p.getId(), p);
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : c.entrySet()) {
            Product p = byId.get(e.getKey());
            if (p != null) lines.add(new Line(p, e.getValue()));
        }
        return lines;
    }

    public static class Line {
        private final Product product;
        private final int qty;

        public Line(Product product, int qty) {
            this.product = product;
            this.qty = qty;
        }

        public Product getProduct() { return product; }
        public int getQty() { return qty; }
        public java.math.BigDecimal getLineTotal() {
            return product.getPrice().multiply(java.math.BigDecimal.valueOf(qty));
        }
    }
}
