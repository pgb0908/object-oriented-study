package gateway;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public class GatewayExchange {

    private enum State { RECEIVED, AUTHENTICATED, CALLING, COMPLETED, FAILED }

    private final String requestId;
    private final String method;
    private final String path;
    private final Map<String, String> headers;
    private final String body;
    private final Instant startedAt;

    private State state = State.RECEIVED;
    private int retryCount = 0;
    private String authUser;
    private String endpoint;
    private FailureReason failureReason;

    public GatewayExchange(String requestId, String method, String path,
                           Map<String, String> headers, String body, Instant startedAt) {
        this.requestId = requireNotBlank(requestId, "requestId");
        this.method = requireNotBlank(method, "method");
        this.path = requireNotBlank(path, "path");
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.body = body;
        this.startedAt = requireNonNull(startedAt, "startedAt");
    }

    public void authenticate(String user) {
        requireState(State.RECEIVED, "authenticate");
        this.authUser = requireNotBlank(user, "user");
        this.state = State.AUTHENTICATED;
    }

    public void startCall(String endpoint) {
        requireState(State.AUTHENTICATED, "startCall");
        this.endpoint = requireNotBlank(endpoint, "endpoint");
        this.state = State.CALLING;
    }

    public void retryCall(String endpoint) {
        requireState(State.CALLING, "retryCall");
        this.endpoint = requireNotBlank(endpoint, "endpoint");
        this.retryCount++;
    }

    public void complete() {
        requireState(State.CALLING, "complete");
        this.state = State.COMPLETED;
    }

    public void fail(FailureReason reason) {
        if (state == State.COMPLETED || state == State.FAILED) {
            throw new IllegalStateException("fail is not allowed in " + state);
        }
        this.failureReason = requireNonNull(reason, "reason");
        this.state = State.FAILED;
    }

    public boolean isCompleted() {
        return state == State.COMPLETED;
    }

    public boolean isFailed() {
        return state == State.FAILED;
    }

    public boolean isFinished() {
        return isCompleted() || isFailed();
    }

    public String requestId() { return requestId; }

    public String method() { return method; }

    public String path() { return path; }

    public Map<String, String> headers() { return headers; }

    public String body() { return body; }

    public Instant startedAt() { return startedAt; }

    public int retryCount() { return retryCount; }

    public Optional<String> authUser() { return Optional.ofNullable(authUser); }

    public Optional<String> endpoint() { return Optional.ofNullable(endpoint); }

    public Optional<FailureReason> failureReason() { return Optional.ofNullable(failureReason); }

    private void requireState(State expected, String action) {
        if (state != expected) {
            throw new IllegalStateException(action + " requires " + expected + " but was " + state);
        }
    }

    private static String requireNotBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
