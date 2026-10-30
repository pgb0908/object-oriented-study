package order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CouponDiscountTest {

    @Test
    void 쿠폰_금액만큼_깎는다() {
        assertEquals(9000, new CouponDiscount(1000).apply(10000));
    }

    @Test
    void 쿠폰이_금액보다_크면_0원이_되고_음수가_되지_않는다() {
        assertEquals(0, new CouponDiscount(5000).apply(3000));
    }

    @Test
    void 쿠폰_금액과_같으면_0원이다() {
        assertEquals(0, new CouponDiscount(3000).apply(3000));
    }

    @Test
    void 쿠폰_금액이_음수이면_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> new CouponDiscount(-1));
    }

    @Test
    void 금액이_음수이면_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> new CouponDiscount(1000).apply(-1));
    }
}
