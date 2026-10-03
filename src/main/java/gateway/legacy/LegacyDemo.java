package gateway.legacy;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class LegacyDemo {

    public static void main(String[] args) throws Exception {
        HttpServer users1 = upstream(9001, "users-1");
        HttpServer users2 = upstream(9002, "users-2");
        HttpServer orders = flakyUpstream(9003, "orders-1", 2);

        ApiGateway gateway = new ApiGateway();

        call(gateway, "GET", "/health", null, "인증 없는 라우트");
        call(gateway, "GET", "/users", "key-abc", "정상 요청 (첫 엔드포인트)");
        call(gateway, "GET", "/users", "key-abc", "정상 요청 (라운드로빈으로 다음 엔드포인트)");
        call(gateway, "GET", "/users", null, "API 키 없음");
        call(gateway, "GET", "/unknown", "key-abc", "라우트 없음");
        call(gateway, "GET", "/orders", "key-abc", "업스트림이 503을 두 번 반환 후 성공 (재시도)");
        call(gateway, "GET", "/orders", "key-xyz", "rps=2 중 1번째");
        call(gateway, "GET", "/orders", "key-xyz", "rps=2 중 2번째");
        call(gateway, "GET", "/orders", "key-xyz", "rps=2 초과");

        System.out.println();
        System.out.println("=== 감사 로그 ===");
        for (String line : ApiGateway.auditLog) {
            System.out.println(line);
        }
        System.out.println();
        System.out.println("총 요청 " + ApiGateway.totalRequests + "건, 실패 " + ApiGateway.totalFailures + "건");

        users1.stop(0);
        users2.stop(0);
        orders.stop(0);
    }

    private static void call(ApiGateway gateway, String method, String path, String apiKey, String note) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        if (apiKey != null) {
            headers.put("X-Api-Key", apiKey);
        }

        Map<String, Object> response = gateway.handle(method, path, headers, null);

        System.out.printf("%-6s %-10s %-9s status=%-4s %s%n",
                method,
                path,
                apiKey == null ? "(no key)" : apiKey,
                response.get("status"),
                note);
        System.out.println("         body=" + response.get("body"));
    }

    private static HttpServer upstream(int port, String name) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            byte[] payload = ("{\"served_by\":\"" + name + "\",\"path\":\""
                    + exchange.getRequestURI().getPath() + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            OutputStream out = exchange.getResponseBody();
            out.write(payload);
            out.close();
        });
        server.start();
        return server;
    }

    private static HttpServer flakyUpstream(int port, String name, int failuresBeforeSuccess) throws Exception {
        AtomicInteger remainingFailures = new AtomicInteger(failuresBeforeSuccess);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            boolean shouldFail = remainingFailures.getAndDecrement() > 0;
            int status = shouldFail ? 503 : 200;
            byte[] payload = shouldFail
                    ? "{\"error\":\"service unavailable\"}".getBytes(StandardCharsets.UTF_8)
                    : ("{\"served_by\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            OutputStream out = exchange.getResponseBody();
            out.write(payload);
            out.close();
        });
        server.start();
        return server;
    }
}
