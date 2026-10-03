package gateway.legacy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LegacyHttpTransport {

    public Map<String, Object> send(String url, String method, Map<String, String> headers, String body)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(1000);
        conn.setReadTimeout(2000);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }

        if (body != null && !body.isEmpty()) {
            conn.setDoOutput(true);
            OutputStream out = conn.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
        }

        int status = conn.getResponseCode();

        InputStream in;
        if (status >= 400) {
            in = conn.getErrorStream();
        } else {
            in = conn.getInputStream();
        }

        String responseBody = "";
        if (in != null) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
            in.close();
            responseBody = buffer.toString(StandardCharsets.UTF_8);
        }

        Map<String, String> responseHeaders = new HashMap<>();
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            if (e.getKey() != null && !e.getValue().isEmpty()) {
                responseHeaders.put(e.getKey(), e.getValue().get(0));
            }
        }

        conn.disconnect();

        Map<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("body", responseBody);
        result.put("headers", responseHeaders);
        return result;
    }
}
