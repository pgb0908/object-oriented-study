package order;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class Order {

    private static final Duration CANCEL_WINDOW_AFTER_PAYMENT = Duration.ofHours(24);

    private enum Status { CREATED, PAID, SHIPPED, CANCELLED }

    private final List<OrderItem> items;
    private final long payableAmount;
    private Status status = Status.CREATED;
    private Instant paidAt;

    public Order(List<OrderItem> items) {
        this(items, List.of());
    }

    public Order(List<OrderItem> items, List<Discount> discounts) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("order needs at least one item");
        }
        if (discounts == null) {
            throw new IllegalArgumentException("discounts must not be null");
        }
        this.items = List.copyOf(items);
        this.payableAmount = applyAll(List.copyOf(discounts), totalAmount());
    }

    public long totalAmount() {
        return items.stream().mapToLong(OrderItem::subtotal).sum();
    }

    public long payableAmount() {
        return payableAmount;
    }

    public void pay(Instant paidAt) {
        requireStatus(Status.CREATED, "pay");
        if (paidAt == null) {
            throw new IllegalArgumentException("paidAt must not be null");
        }
        this.paidAt = paidAt;
        this.status = Status.PAID;
    }

    public void ship() {
        requireStatus(Status.PAID, "ship");
        this.status = Status.SHIPPED;
    }

    public void cancel(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }
        if (!canCancelAt(now)) {
            throw new IllegalStateException("cannot cancel in " + status + " at " + now);
        }
        this.status = Status.CANCELLED;
    }

    public boolean isPaid() {
        return status == Status.PAID;
    }

    public boolean isShipped() {
        return status == Status.SHIPPED;
    }

    public boolean isCancelled() {
        return status == Status.CANCELLED;
    }

    private static long applyAll(List<Discount> discounts, long amount) {
        long result = amount;
        for (Discount discount : discounts) {
            result = discount.apply(result);
        }
        return result;
    }

    private boolean canCancelAt(Instant now) {
        return switch (status) {
            case CREATED -> true;
            case PAID -> isWithinCancelWindow(now);
            case SHIPPED, CANCELLED -> false;
        };
    }

    private boolean isWithinCancelWindow(Instant now) {
        return !now.isAfter(paidAt.plus(CANCEL_WINDOW_AFTER_PAYMENT));
    }

    private void requireStatus(Status expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(action + " requires " + expected + " but was " + status);
        }
    }
}
