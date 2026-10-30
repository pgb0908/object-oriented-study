package order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheckoutTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private Order order() {
        return new Order(List.of(new OrderItem("사과", 10000, 1)),
                List.of(new GradeDiscount(MemberGrade.GOLD)));
    }

    @Test
    void 승인되면_주문이_PAID가_되고_true를_돌려준다() {
        Order order = order();

        boolean paid = new Checkout(FakePaymentGateway.approving(), FIXED_CLOCK).pay(order);

        assertTrue(paid);
        assertTrue(order.isPaid());
    }

    @Test
    void 승인을_물을_때_할인이_적용된_결제_금액을_넘긴다() {
        FakePaymentGateway gateway = FakePaymentGateway.approving();

        new Checkout(gateway, FIXED_CLOCK).pay(order());

        assertEquals(List.of(9000L), gateway.requestedAmounts());
    }

    @Test
    void 승인이_거절되면_false를_돌려주고_주문_상태는_그대로다() {
        Order order = order();

        boolean paid = new Checkout(FakePaymentGateway.rejecting(), FIXED_CLOCK).pay(order);

        assertFalse(paid);
        assertFalse(order.isPaid());
        assertTrue(order.canPay());
    }

    @Test
    void 결제_시각은_주입한_시계의_시각이다() {
        Order withinWindow = order();
        Order afterWindow = order();
        Checkout checkout = new Checkout(FakePaymentGateway.approving(), FIXED_CLOCK);
        checkout.pay(withinWindow);
        checkout.pay(afterWindow);

        withinWindow.cancel(NOW.plusSeconds(24 * 3600 - 60));
        assertTrue(withinWindow.isCancelled());

        assertThrows(IllegalStateException.class, () -> afterWindow.cancel(NOW.plusSeconds(24 * 3600 + 60)));
        assertTrue(afterWindow.isPaid());
    }

    @Test
    void 이미_결제한_주문은_승인을_묻지_않고_거부한다() {
        FakePaymentGateway gateway = FakePaymentGateway.approving();
        Checkout checkout = new Checkout(gateway, FIXED_CLOCK);
        Order order = order();
        checkout.pay(order);

        assertThrows(IllegalStateException.class, () -> checkout.pay(order));

        assertEquals(1, gateway.requestedAmounts().size());
    }

    @Test
    void 취소한_주문은_승인을_묻지_않고_거부한다() {
        FakePaymentGateway gateway = FakePaymentGateway.approving();
        Order order = order();
        order.cancel(NOW);

        assertThrows(IllegalStateException.class, () -> new Checkout(gateway, FIXED_CLOCK).pay(order));

        assertTrue(gateway.requestedAmounts().isEmpty());
    }

    @Test
    void 주문이_없거나_의존성이_없으면_거부한다() {
        assertThrows(IllegalArgumentException.class,
                () -> new Checkout(FakePaymentGateway.approving(), FIXED_CLOCK).pay(null));
        assertThrows(IllegalArgumentException.class, () -> new Checkout(null, FIXED_CLOCK));
        assertThrows(IllegalArgumentException.class, () -> new Checkout(FakePaymentGateway.approving(), null));
    }
}
