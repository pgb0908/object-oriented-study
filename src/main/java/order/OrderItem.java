package order;

public final class OrderItem {

    private final String productName;
    private final long unitPrice;
    private final int quantity;

    public OrderItem(String productName, long unitPrice, int quantity) {
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName must not be blank");
        }
        if (unitPrice < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public long subtotal() {
        return Math.multiplyExact(unitPrice, (long) quantity);
    }

    public String productName() {
        return productName;
    }
}
