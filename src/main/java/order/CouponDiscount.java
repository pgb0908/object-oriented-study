package order;

public final class CouponDiscount implements Discount {

    private final long discountAmount;

    public CouponDiscount(long discountAmount) {
        if (discountAmount < 0) {
            throw new IllegalArgumentException("discountAmount must not be negative");
        }
        this.discountAmount = discountAmount;
    }

    @Override
    public long apply(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        return Math.max(0, amount - discountAmount);
    }
}
