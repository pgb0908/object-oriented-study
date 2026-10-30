package gateway;

public enum FailureReason {
    ROUTE_NOT_FOUND,
    UNAUTHORIZED,
    AUTH_MISCONFIGURED,
    RATE_LIMITED,
    UPSTREAM_UNAVAILABLE
}
