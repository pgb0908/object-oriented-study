package order;

import java.time.Clock;

public final class Checkout {

    private final PaymentGateway paymentGateway;
    private final Clock clock;

    public Checkout(PaymentGateway paymentGateway, Clock clock) {
        if (paymentGateway == null) {
            throw new IllegalArgumentException("paymentGateway must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.paymentGateway = paymentGateway;
        this.clock = clock;
    }

    public boolean pay(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("order must not be null");
        }
        if (!order.canPay()) {
            throw new IllegalStateException("order cannot be paid in its current status");
        }
        if (!paymentGateway.approve(order.payableAmount())) {
            return false;
        }
        order.pay(clock.instant());
        return true;
    }
}
