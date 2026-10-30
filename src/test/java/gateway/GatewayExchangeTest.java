package gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class GatewayExchangeTest {

    private static final String EP = "http://127.0.0.1:9001";

    private GatewayExchange received() {
        return new GatewayExchange("id-1", "GET", "/users", Map.of(), null, Instant.EPOCH);
    }

    private GatewayExchange authenticated() {
        GatewayExchange e = received();
        e.authenticate("alice");
        return e;
    }

    private GatewayExchange calling() {
        GatewayExchange e = authenticated();
        e.startCall(EP);
        return e;
    }

    private GatewayExchange completed() {
        GatewayExchange e = calling();
        e.complete();
        return e;
    }

    private GatewayExchange failed() {
        GatewayExchange e = received();
        e.fail(FailureReason.ROUTE_NOT_FOUND);
        return e;
    }

    // --- 생성 불변식 (A1~A4) ---

    @Test
    void 생성_직후_RECEIVED이고_재시도는_0이며_인증_엔드포인트_실패사유가_없다() {
        GatewayExchange e = received();
        assertFalse(e.isFinished());
        assertEquals(0, e.retryCount());
        assertTrue(e.authUser().isEmpty());
        assertTrue(e.endpoint().isEmpty());
        assertTrue(e.failureReason().isEmpty());
    }

    @Test
    void requestId_method_path가_비어_있으면_생성을_거부한다() {
        assertThrows(IllegalArgumentException.class,
                () -> new GatewayExchange(" ", "GET", "/a", Map.of(), null, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> new GatewayExchange("id", null, "/a", Map.of(), null, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> new GatewayExchange("id", "GET", "", Map.of(), null, Instant.EPOCH));
    }

    @Test
    void 요청_헤더를_바깥에서_바꿔도_교환은_영향받지_않는다() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Api-Key", "key-abc");
        GatewayExchange e = new GatewayExchange("id", "GET", "/a", headers, null, Instant.EPOCH);

        headers.put("X-Api-Key", "tampered");

        assertEquals("key-abc", e.headers().get("X-Api-Key"));
        assertThrows(UnsupportedOperationException.class, () -> e.headers().put("a", "b"));
    }

    // --- 허용 전이 7가지 ---

    @Test
    void 인증하면_사용자가_기록된다() {
        GatewayExchange e = authenticated();
        assertEquals("alice", e.authUser().orElseThrow());
    }

    @Test
    void 인증_전_RECEIVED에서_실패할_수_있다() {
        GatewayExchange e = failed();
        assertTrue(e.isFailed());
        assertEquals(FailureReason.ROUTE_NOT_FOUND, e.failureReason().orElseThrow());
        assertTrue(e.authUser().isEmpty());
    }

    @Test
    void 인증_후_호출을_시작하면_엔드포인트가_기록된다() {
        GatewayExchange e = calling();
        assertEquals(EP, e.endpoint().orElseThrow());
    }

    @Test
    void 인증_후_속도제한으로_실패해도_인증_사용자는_남는다() {
        GatewayExchange e = authenticated();
        e.fail(FailureReason.RATE_LIMITED);
        assertTrue(e.isFailed());
        assertEquals("alice", e.authUser().orElseThrow());
    }

    @Test
    void 재시도하면_횟수가_1_늘고_엔드포인트가_바뀐다() {
        GatewayExchange e = calling();
        e.retryCall("http://127.0.0.1:9002");
        e.retryCall(EP);
        assertEquals(2, e.retryCount());
        assertEquals(EP, e.endpoint().orElseThrow());
        assertFalse(e.isFinished());
    }

    @Test
    void 호출_중_완료할_수_있다() {
        assertTrue(completed().isCompleted());
    }

    @Test
    void 호출_중_실패할_수_있다() {
        GatewayExchange e = calling();
        e.fail(FailureReason.UPSTREAM_UNAVAILABLE);
        assertTrue(e.isFailed());
    }

    // --- 거부해야 하는 호출 (설계 로그 4번) ---

    private void assertRejectedInEveryStateExcept(String allowed, Consumer<GatewayExchange> call) {
        for (String name : ALL_STATES) {
            if (name.equals(allowed)) {
                continue;
            }
            GatewayExchange e = freshIn(name);
            assertThrows(IllegalStateException.class, () -> call.accept(e), name);
        }
    }

    private GatewayExchange freshIn(String state) {
        return switch (state) {
            case "RECEIVED" -> received();
            case "AUTHENTICATED" -> authenticated();
            case "CALLING" -> calling();
            case "COMPLETED" -> completed();
            case "FAILED" -> failed();
            default -> throw new IllegalArgumentException(state);
        };
    }

    private static final List<String> ALL_STATES =
            List.of("RECEIVED", "AUTHENTICATED", "CALLING", "COMPLETED", "FAILED");

    @Test
    void authenticate는_RECEIVED가_아니면_거부한다() {
        assertRejectedInEveryStateExcept("RECEIVED", e -> e.authenticate("bob"));
    }

    @Test
    void startCall은_AUTHENTICATED가_아니면_거부한다() {
        assertRejectedInEveryStateExcept("AUTHENTICATED", e -> e.startCall(EP));
    }

    @Test
    void retryCall은_CALLING이_아니면_거부한다() {
        assertRejectedInEveryStateExcept("CALLING", e -> e.retryCall(EP));
    }

    @Test
    void complete는_CALLING이_아니면_거부한다() {
        assertRejectedInEveryStateExcept("CALLING", e -> e.complete());
    }

    @Test
    void fail은_종료_상태에서_거부한다() {
        assertThrows(IllegalStateException.class, () -> completed().fail(FailureReason.RATE_LIMITED));
        assertThrows(IllegalStateException.class, () -> failed().fail(FailureReason.UNAUTHORIZED));
    }

    @Test
    void 이미_끝난_교환은_재시도할_수_없다() {
        GatewayExchange e = completed();
        assertThrows(IllegalStateException.class, () -> e.retryCall(EP));
        assertEquals(0, e.retryCount());
    }

    // --- 인자 검증 ---

    @Test
    void fail은_사유가_없으면_거부하고_상태를_바꾸지_않는다() {
        GatewayExchange e = received();
        assertThrows(IllegalArgumentException.class, () -> e.fail(null));
        assertFalse(e.isFailed());
    }

    @Test
    void authenticate는_사용자가_비어_있으면_거부하고_상태를_바꾸지_않는다() {
        GatewayExchange e = received();
        assertThrows(IllegalArgumentException.class, () -> e.authenticate(" "));
        assertTrue(e.authUser().isEmpty());
        assertDoesNotThrow(() -> e.authenticate("alice"));
    }

    @Test
    void 엔드포인트가_비어_있으면_거부하고_횟수를_늘리지_않는다() {
        GatewayExchange e = calling();
        assertThrows(IllegalArgumentException.class, () -> e.retryCall(null));
        assertEquals(0, e.retryCount());
        assertEquals(EP, e.endpoint().orElseThrow());
    }

    // --- 종료 후 불변 (C2) ---

    @Test
    void 종료된_교환의_값은_더_이상_바뀌지_않는다() {
        GatewayExchange e = completed();
        assertThrows(IllegalStateException.class, () -> e.authenticate("bob"));
        assertThrows(IllegalStateException.class, () -> e.startCall("x"));
        assertEquals("alice", e.authUser().orElseThrow());
        assertEquals(EP, e.endpoint().orElseThrow());
    }
}
