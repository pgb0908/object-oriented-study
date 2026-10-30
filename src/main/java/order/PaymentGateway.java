package order;

public interface PaymentGateway {

    boolean approve(long amount);
}
