package com.threadly.cart;

import com.threadly.product.Product;
import com.threadly.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    ProductRepository productRepository;

    CartService service;
    MockHttpSession session;

    @BeforeEach
    void setup() {
        service = new CartService(productRepository);
        session = new MockHttpSession();
        lenient().when(productRepository.findAllById(any())).thenAnswer(inv -> {
            Iterable<Long> ids = inv.getArgument(0);
            return List.of(product(1L, "Segfault", "24.00"), product(2L, "Deadlock", "35.00"))
                    .stream()
                    .filter(p -> {
                        for (Long id : ids) if (id.equals(p.getId())) return true;
                        return false;
                    })
                    .toList();
        });
    }

    @Test
    void newSessionHasEmptyCart() {
        assertThat(service.isEmpty(session)).isTrue();
        assertThat(service.totalQty(session)).isZero();
        assertThat(service.items(session)).isEmpty();
    }

    @Test
    void addIncrementsExistingItemQty() {
        service.add(session, 1L);
        service.add(session, 1L);
        service.add(session, 2L);

        assertThat(service.totalQty(session)).isEqualTo(3);
        List<CartService.Line> lines = service.items(session);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getQty()).isEqualTo(2);
        assertThat(lines.get(1).getQty()).isEqualTo(1);
    }

    @Test
    void updateToZeroOrNegativeRemovesItem() {
        service.add(session, 1L);
        service.add(session, 2L);

        service.update(session, 1L, 0);

        assertThat(service.totalQty(session)).isEqualTo(1);
        assertThat(service.items(session)).hasSize(1);
    }

    @Test
    void updateChangesQty() {
        service.add(session, 1L);

        service.update(session, 1L, 5);

        assertThat(service.totalQty(session)).isEqualTo(5);
    }

    @Test
    void removeDropsItem() {
        service.add(session, 1L);
        service.add(session, 2L);

        service.remove(session, 1L);

        assertThat(service.totalQty(session)).isEqualTo(1);
        assertThat(service.items(session)).extracting(l -> l.getProduct().getId()).containsExactly(2L);
    }

    @Test
    void clearEmptiesCart() {
        service.add(session, 1L);
        service.add(session, 2L);

        service.clear(session);

        assertThat(service.isEmpty(session)).isTrue();
    }

    @Test
    void lineTotalIsPriceTimesQty() {
        service.add(session, 1L);
        service.update(session, 1L, 3);

        CartService.Line line = service.items(session).get(0);
        assertThat(line.getLineTotal()).isEqualByComparingTo("72.00");
    }

    @Test
    void itemsSkipsProductsNoLongerInRepository() {
        service.add(session, 1L);
        service.add(session, 999L); // not in stub

        List<CartService.Line> lines = service.items(session);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getProduct().getId()).isEqualTo(1L);
    }

    private static Product product(Long id, String name, String price) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setPrice(new BigDecimal(price));
        return p;
    }
}
