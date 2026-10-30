package order;

import java.util.ArrayList;
import java.util.List;

class FakePaymentGateway implements PaymentGateway {

    private final boolean approves;
    private final List<Long> requestedAmounts = new ArrayList<>();

    private FakePaymentGateway(boolean approves) {
        this.approves = approves;
    }

    static FakePaymentGateway approving() {
        return new FakePaymentGateway(true);
    }

    static FakePaymentGateway rejecting() {
        return new FakePaymentGateway(false);
    }

    @Override
    public boolean approve(long amount) {
        requestedAmounts.add(amount);
        return approves;
    }

    List<Long> requestedAmounts() {
        return requestedAmounts;
    }
}
