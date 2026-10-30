package order;

public final class FirstOrderDiscount implements Discount {

    private static final int DISCOUNT_PERCENT = 5;

    private final boolean firstOrder;

    public FirstOrderDiscount(boolean firstOrder) {
        this.firstOrder = firstOrder;
    }

    @Override
    public long apply(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (!firstOrder) {
            return amount;
        }
        long discount = Math.multiplyExact(amount, (long) DISCOUNT_PERCENT) / 100;
        return amount - discount;
    }
}
