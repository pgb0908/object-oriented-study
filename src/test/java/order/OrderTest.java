package order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final Instant PAID_AT = Instant.parse("2026-01-01T00:00:00Z");

    private Order created() {
        return new Order(List.of(new OrderItem("사과", 1000, 2), new OrderItem("배", 3000, 1)));
    }

    private Order paid() {
        Order order = created();
        order.pay(PAID_AT);
        return order;
    }

    private Order shipped() {
        Order order = paid();
        order.ship();
        return order;
    }

    // --- 생성 ---

    @Test
    void 항목이_없으면_주문을_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> new Order(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Order(null));
    }

    @Test
    void 총액은_항목_소계의_합이다() {
        assertEquals(5000, created().totalAmount());
    }

    @Test
    void 만든_뒤_바깥에서_항목_목록을_바꿔도_주문은_영향받지_않는다() {
        List<OrderItem> items = new ArrayList<>(List.of(new OrderItem("사과", 1000, 1)));
        Order order = new Order(items);

        items.add(new OrderItem("배", 3000, 1));

        assertEquals(1000, order.totalAmount());
    }

    @Test
    void 생성_직후에는_결제도_배송도_취소도_아니다() {
        Order order = created();
        assertFalse(order.isPaid());
        assertFalse(order.isShipped());
        assertFalse(order.isCancelled());
    }

    // --- 상태 전이 ---

    @Test
    void 결제하면_PAID가_되고_배송하면_SHIPPED가_된다() {
        Order order = paid();
        assertTrue(order.isPaid());

        order.ship();

        assertTrue(order.isShipped());
    }

    @Test
    void 결제_전에는_배송할_수_없다() {
        assertThrows(IllegalStateException.class, () -> created().ship());
    }

    @Test
    void 이미_결제한_주문은_다시_결제할_수_없다() {
        assertThrows(IllegalStateException.class, () -> paid().pay(PAID_AT));
    }

    @Test
    void 결제_시각이_없으면_결제할_수_없고_상태도_바뀌지_않는다() {
        Order order = created();
        assertThrows(IllegalArgumentException.class, () -> order.pay(null));
        assertFalse(order.isPaid());
    }

    // --- 취소 ---

    @Test
    void 결제_전에는_언제든_취소할_수_있다() {
        Order order = created();
        order.cancel(PAID_AT.plusSeconds(999_999));
        assertTrue(order.isCancelled());
    }

    @Test
    void 결제_후_24시간_이내에는_취소할_수_있다() {
        Order order = paid();
        order.cancel(PAID_AT.plusSeconds(24 * 3600 - 1));
        assertTrue(order.isCancelled());
    }

    @Test
    void 결제_후_정확히_24시간째에는_취소할_수_있다() {
        Order order = paid();
        order.cancel(PAID_AT.plusSeconds(24 * 3600));
        assertTrue(order.isCancelled());
    }

    @Test
    void 결제_후_24시간이_지나면_취소할_수_없고_상태도_그대로다() {
        Order order = paid();
        assertThrows(IllegalStateException.class, () -> order.cancel(PAID_AT.plusSeconds(24 * 3600 + 1)));
        assertTrue(order.isPaid());
    }

    @Test
    void 배송_후에는_취소할_수_없다() {
        Order order = shipped();
        assertThrows(IllegalStateException.class, () -> order.cancel(PAID_AT));
        assertTrue(order.isShipped());
    }

    @Test
    void 취소한_주문은_다시_취소_결제_배송할_수_없다() {
        Order order = created();
        order.cancel(PAID_AT);

        assertThrows(IllegalStateException.class, () -> order.cancel(PAID_AT));
        assertThrows(IllegalStateException.class, () -> order.pay(PAID_AT));
        assertThrows(IllegalStateException.class, order::ship);
    }

    @Test
    void 취소_시각이_없으면_취소할_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> created().cancel(null));
    }
}
