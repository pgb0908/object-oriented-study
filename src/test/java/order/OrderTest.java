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

    // --- 결제 금액 ---

    @Test
    void 할인이_없으면_결제_금액은_총액과_같다() {
        Order order = created();
        assertEquals(order.totalAmount(), order.payableAmount());
    }

    @Test
    void 등급_할인을_적용한_결제_금액을_답한다() {
        Order order = new Order(List.of(new OrderItem("사과", 10000, 1)),
                List.of(new GradeDiscount(MemberGrade.GOLD)));
        assertEquals(9000, order.payableAmount());
        assertEquals(10000, order.totalAmount());
    }

    @Test
    void 할인은_목록_순서대로_적용한다() {
        List<OrderItem> items = List.of(new OrderItem("사과", 10000, 1));
        Discount gold = new GradeDiscount(MemberGrade.GOLD);
        Discount coupon = new CouponDiscount(1000);

        assertEquals(8000, new Order(items, List.of(gold, coupon)).payableAmount());
        assertEquals(8100, new Order(items, List.of(coupon, gold)).payableAmount());
    }

    @Test
    void 쿠폰이_총액보다_커도_결제_금액은_0_미만이_되지_않는다() {
        Order order = new Order(List.of(new OrderItem("사과", 3000, 1)),
                List.of(new CouponDiscount(5000)));
        assertEquals(0, order.payableAmount());
    }

    @Test
    void 직접_구현한_할인도_똑같이_다룬다() {
        Discount flat500 = amount -> amount - 500;
        Order order = new Order(List.of(new OrderItem("사과", 3000, 1)), List.of(flat500));
        assertEquals(2500, order.payableAmount());
    }

    @Test
    void 만든_뒤_바깥에서_할인_목록을_바꿔도_결제_금액은_변하지_않는다() {
        List<Discount> discounts = new ArrayList<>(List.of(new CouponDiscount(1000)));
        Order order = new Order(List.of(new OrderItem("사과", 3000, 1)), discounts);

        discounts.add(new CouponDiscount(1000));

        assertEquals(2000, order.payableAmount());
    }

    @Test
    void 할인_목록이_없으면_주문을_만들_수_없다() {
        assertThrows(IllegalArgumentException.class,
                () -> new Order(List.of(new OrderItem("사과", 3000, 1)), null));
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
