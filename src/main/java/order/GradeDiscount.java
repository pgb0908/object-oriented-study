package order;

public final class GradeDiscount implements Discount {

    private final MemberGrade grade;

    public GradeDiscount(MemberGrade grade) {
        if (grade == null) {
            throw new IllegalArgumentException("grade must not be null");
        }
        this.grade = grade;
    }

    @Override
    public long apply(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        long discount = Math.multiplyExact(amount, (long) discountPercent()) / 100;
        return amount - discount;
    }

    private int discountPercent() {
        return switch (grade) {
            case BASIC -> 0;
            case GOLD -> 10;
        };
    }
}
