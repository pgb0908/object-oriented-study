package order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OrderItemTest {

    @Test
    void 소계는_단가와_수량의_곱이다() {
        assertEquals(3000, new OrderItem("사과", 1000, 3).subtotal());
    }

    @Test
    void 상품명이_비어_있으면_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(" ", 1000, 1));
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(null, 1000, 1));
    }

    @Test
    void 단가가_음수이면_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> new OrderItem("사과", -1, 1));
    }

    @Test
    void 수량이_1_미만이면_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> new OrderItem("사과", 1000, 0));
    }
}
