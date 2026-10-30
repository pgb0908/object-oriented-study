package order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GradeDiscountTest {

    @Test
    void 골드_등급은_10퍼센트를_할인한다() {
        assertEquals(9000, new GradeDiscount(MemberGrade.GOLD).apply(10000));
    }

    @Test
    void 일반_등급은_할인하지_않는다() {
        assertEquals(10000, new GradeDiscount(MemberGrade.BASIC).apply(10000));
    }

    @Test
    void 할인액의_원_미만은_버린다() {
        assertEquals(900, new GradeDiscount(MemberGrade.GOLD).apply(999));
    }

    @Test
    void 금액이_0원이면_0원이다() {
        assertEquals(0, new GradeDiscount(MemberGrade.GOLD).apply(0));
    }

    @Test
    void 금액이_음수이면_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> new GradeDiscount(MemberGrade.GOLD).apply(-1));
    }

    @Test
    void 등급이_없으면_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> new GradeDiscount(null));
    }
}
