package order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class FirstOrderDiscountTest {

    @Test
    void 첫_주문이면_5퍼센트를_할인한다() {
        assertEquals(9500, new FirstOrderDiscount(true).apply(10000));
    }

    @Test
    void 첫_주문이_아니면_할인하지_않는다() {
        assertEquals(10000, new FirstOrderDiscount(false).apply(10000));
    }

    @Test
    void 할인액의_원_미만은_버린다() {
        assertEquals(950, new FirstOrderDiscount(true).apply(999));
    }

    @Test
    void 금액이_음수이면_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> new FirstOrderDiscount(true).apply(-1));
    }

    @Test
    void Order를_고치지_않고_기존_할인과_함께_쓸_수_있다() {
        Order order = new Order(List.of(new OrderItem("사과", 10000, 1)),
                List.of(new GradeDiscount(MemberGrade.GOLD), new FirstOrderDiscount(true)));

        assertEquals(8550, order.payableAmount());
    }
}
