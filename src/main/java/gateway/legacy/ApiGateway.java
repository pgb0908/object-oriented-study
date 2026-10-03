package gateway.legacy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ApiGateway {

    public static final String ROUTE_CONFIG =
            "/users -> http://127.0.0.1:9001,http://127.0.0.1:9002 | auth=apikey | retry=3 | rps=5\n"
            + "/orders -> http://127.0.0.1:9003 | auth=apikey | retry=2 | rps=2\n"
            + "/health -> http://127.0.0.1:9001 | auth=none | retry=0 | rps=100";

    public static final Map<String, String> API_KEYS = new HashMap<>();

    static {
        API_KEYS.put("key-abc", "alice");
        API_KEYS.put("key-xyz", "bob");
    }

    public static Map<String, List<Long>> rateLimitBuckets = new HashMap<>();
    public static Map<String, Integer> roundRobinIndex = new HashMap<>();
    public static int totalRequests = 0;
    public static int totalFailures = 0;
    public static List<String> auditLog = new ArrayList<>();

    public Map<String, Object> handle(String method, String path, Map<String, String> headers, String body) {
        Map<String, Object> exchange = new HashMap<>();
        exchange.put("requestId", UUID.randomUUID().toString());
        exchange.put("method", method);
        exchange.put("path", path);
        exchange.put("headers", headers);
        exchange.put("body", body);
        exchange.put("state", "RECEIVED");
        exchange.put("retryCount", 0);
        exchange.put("startedAt", System.currentTimeMillis());
        totalRequests++;

        Map<String, Object> response = new HashMap<>();

        String authType = null;
        for (String line : ROUTE_CONFIG.split("\n")) {
            String prefix = line.split("->")[0].trim();
            if (path.startsWith(prefix)) {
                String[] parts = line.split("\\|");
                for (int i = 1; i < parts.length; i++) {
                    String p = parts[i].trim();
                    if (p.startsWith("auth=")) {
                        authType = p.substring(5);
                    }
                }
                break;
            }
        }

        if (authType == null) {
            exchange.put("state", "FAILED");
            totalFailures++;
            response.put("status", 404);
            response.put("body", "{\"error\":\"no route\"}");
            auditLog.add(exchange.get("requestId") + " " + method + " " + path + " -> 404 no route");
            return response;
        }

        if (authType.equals("apikey")) {
            String key = headers.get("X-Api-Key");
            if (key == null || !API_KEYS.containsKey(key)) {
                exchange.put("state", "FAILED");
                totalFailures++;
                response.put("status", 401);
                response.put("body", "{\"error\":\"unauthorized\"}");
                auditLog.add(exchange.get("requestId") + " " + method + " " + path + " key=" + key + " -> 401");
                return response;
            }
            exchange.put("authUser", API_KEYS.get(key));
            exchange.put("state", "AUTHENTICATED");
        } else if (authType.equals("none")) {
            exchange.put("authUser", "anonymous");
            exchange.put("state", "AUTHENTICATED");
        } else {
            exchange.put("state", "FAILED");
            totalFailures++;
            response.put("status", 500);
            response.put("body", "{\"error\":\"bad auth config\"}");
            return response;
        }

        String matchedPrefix = null;
        List<String> endpoints = new ArrayList<>();
        int maxRetry = 0;
        int rps = 0;
        for (String line : ROUTE_CONFIG.split("\n")) {
            String prefix = line.split("->")[0].trim();
            if (path.startsWith(prefix)) {
                matchedPrefix = prefix;
                String rest = line.split("->")[1];
                String[] parts = rest.split("\\|");
                for (String e : parts[0].trim().split(",")) {
                    endpoints.add(e.trim());
                }
                for (int i = 1; i < parts.length; i++) {
                    String p = parts[i].trim();
                    if (p.startsWith("retry=")) {
                        maxRetry = Integer.parseInt(p.substring(6));
                    } else if (p.startsWith("rps=")) {
                        rps = Integer.parseInt(p.substring(4));
                    }
                }
                break;
            }
        }
        exchange.put("routeName", matchedPrefix);

        String bucketKey = exchange.get("authUser") + ":" + matchedPrefix;
        long now = System.currentTimeMillis();
        List<Long> bucket = rateLimitBuckets.get(bucketKey);
        if (bucket == null) {
            bucket = new ArrayList<>();
            rateLimitBuckets.put(bucketKey, bucket);
        }
        List<Long> kept = new ArrayList<>();
        for (Long t : bucket) {
            if (now - t < 1000) {
                kept.add(t);
            }
        }
        rateLimitBuckets.put(bucketKey, kept);
        if (kept.size() >= rps) {
            exchange.put("state", "FAILED");
            totalFailures++;
            response.put("status", 429);
            response.put("body", "{\"error\":\"rate limited\"}");
            auditLog.add(exchange.get("requestId") + " " + method + " " + path
                    + " user=" + exchange.get("authUser") + " -> 429");
            return response;
        }
        kept.add(now);

        int attempt = 0;
        int status = 0;
        String respBody = "";
        Map<String, String> respHeaders = new HashMap<>();
        while (true) {
            Integer idx = roundRobinIndex.get(matchedPrefix);
            if (idx == null) {
                idx = 0;
            }
            String endpoint = endpoints.get(idx % endpoints.size());
            roundRobinIndex.put(matchedPrefix, idx + 1);
            exchange.put("endpoint", endpoint);
            exchange.put("state", "CALLING");

            LegacyHttpTransport transport = new LegacyHttpTransport();
            Map<String, Object> raw;
            try {
                raw = transport.send(endpoint + path, method, headers, body);
            } catch (Exception e) {
                raw = new HashMap<>();
                raw.put("status", 599);
                raw.put("body", "");
                raw.put("headers", new HashMap<String, String>());
            }
            status = (Integer) raw.get("status");
            respBody = (String) raw.get("body");
            respHeaders = (Map<String, String>) raw.get("headers");

            if (status == 500 || status == 502 || status == 503 || status == 599) {
                if (attempt < maxRetry) {
                    exchange.put("state", "RETRYING");
                    exchange.put("retryCount", (Integer) exchange.get("retryCount") + 1);
                    attempt++;
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                    }
                    continue;
                }
            }
            break;
        }

        if (status >= 500) {
            exchange.put("state", "FAILED");
            totalFailures++;
            response.put("status", 502);
            response.put("body", "{\"error\":\"upstream unavailable\"}");
        } else {
            exchange.put("state", "COMPLETED");
            response.put("status", status);
            if (respBody == null) {
                respBody = "";
            }
            if (respBody.length() > 10000) {
                respBody = respBody.substring(0, 10000);
            }
            response.put("body", respBody);
        }

        Map<String, String> outHeaders = new HashMap<>();
        for (Map.Entry<String, String> e : respHeaders.entrySet()) {
            String k = e.getKey();
            if (k == null) {
                continue;
            }
            if (k.equalsIgnoreCase("Server") || k.equalsIgnoreCase("Set-Cookie")
                    || k.equalsIgnoreCase("Transfer-Encoding")) {
                continue;
            }
            outHeaders.put(k, e.getValue());
        }
        outHeaders.put("X-Request-Id", (String) exchange.get("requestId"));
        outHeaders.put("X-Upstream", (String) exchange.get("endpoint"));
        response.put("headers", outHeaders);

        long elapsed = System.currentTimeMillis() - (Long) exchange.get("startedAt");
        auditLog.add(exchange.get("requestId") + " " + method + " " + path
                + " user=" + exchange.get("authUser")
                + " route=" + exchange.get("routeName")
                + " endpoint=" + exchange.get("endpoint")
                + " retry=" + exchange.get("retryCount")
                + " state=" + exchange.get("state")
                + " -> " + response.get("status")
                + " (" + elapsed + "ms)");

        return response;
    }
}
