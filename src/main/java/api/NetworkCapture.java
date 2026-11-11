package api;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import utils.ConfigManager;
import utils.SecretMasker;

/**
 * Observes the app's own XHR/Fetch traffic through Chrome's performance log. Only method, path and
 * status are kept; query strings, headers, cookies and bodies are never stored.
 */
public final class NetworkCapture {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NetworkCapture() {
    }

    /** Returns calls observed since the previous drain. */
    public static List<ApiCall> drain(WebDriver driver) {
        if (!ConfigManager.getBool("capture.network", true)) {
            return List.of();
        }
        String root = rootDomain(URI.create(ConfigManager.baseUrl()).getHost());
        Map<String, ApiCall> byId = new LinkedHashMap<>();
        try {
            for (LogEntry le : driver.manage().logs().get(LogType.PERFORMANCE)) {
                JsonNode msg = MAPPER.readTree(le.getMessage()).path("message");
                String method = msg.path("method").asText();
                JsonNode p = msg.path("params");
                String id = p.path("requestId").asText();
                switch (method) {
                    case "Network.requestWillBeSent": {
                        String type = p.path("type").asText();
                        if (!type.equals("XHR") && !type.equals("Fetch")) {
                            break;
                        }
                        String url = p.path("request").path("url").asText();
                        if (!url.contains(root)) {
                            break;
                        }
                        byId.put(id, new ApiCall(p.path("request").path("method").asText(), stripQuery(url)));
                        break;
                    }
                    case "Network.responseReceived": {
                        ApiCall c = byId.get(id);
                        if (c != null) {
                            c.status = p.path("response").path("status").asInt();
                        }
                        break;
                    }
                    case "Network.loadingFailed": {
                        ApiCall c = byId.get(id);
                        if (c != null) {
                            c.error = p.path("errorText").asText();
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Like {@link #drain}, but keeps everything the browser shows about each call: the full URL, request headers (also the ones the browser adds, such as cookies), the request body,
     * the response status, headers and body, and the times. Only what the page's own requests really did is recorded (no request is sent by the test). Bodies are cut at 30 000
     * characters; a body the browser no longer holds (or a file upload) is noted as such.
     */
    public static List<ApiCall> drainFull(WebDriver driver) {
        if (!ConfigManager.getBool("capture.network", true)) {
            return List.of();
        }
        String root = rootDomain(URI.create(ConfigManager.baseUrl()).getHost());
        Map<String, ApiCall> byId = new LinkedHashMap<>();
        Map<String, double[]> ts = new LinkedHashMap<>(); // start, response, end (browser clock, seconds)
        Map<String, Boolean> hasPost = new LinkedHashMap<>();
        try {
            for (LogEntry le : driver.manage().logs().get(LogType.PERFORMANCE)) {
                JsonNode msg = MAPPER.readTree(le.getMessage()).path("message");
                String method = msg.path("method").asText();
                JsonNode p = msg.path("params");
                String id = p.path("requestId").asText();
                switch (method) {
                    case "Network.requestWillBeSent": {
                        String type = p.path("type").asText();
                        String url = p.path("request").path("url").asText();
                        if ((!type.equals("XHR") && !type.equals("Fetch")) || !url.contains(root)) {
                            break;
                        }
                        ApiCall c = new ApiCall(p.path("request").path("method").asText(), stripQuery(url));
                        c.requestId = id;
                        c.fullUrl = url;
                        p.path("request").path("headers").fields().forEachRemaining(h -> c.requestHeaders.put(h.getKey(), h.getValue().asText()));
                        if (p.path("request").hasNonNull("postData")) {
                            c.requestBody = p.path("request").path("postData").asText();
                        }
                        hasPost.put(id, p.path("request").path("hasPostData").asBoolean(false));
                        c.startMs = (long) (p.path("wallTime").asDouble(0) * 1000);
                        ts.put(id, new double[] { p.path("timestamp").asDouble(0), 0, 0 });
                        byId.put(id, c);
                        break;
                    }
                    case "Network.requestWillBeSentExtraInfo": {
                        ApiCall c = byId.get(id);
                        if (c != null) {
                            p.path("headers").fields().forEachRemaining(h -> c.requestHeaders.put(h.getKey(), h.getValue().asText()));
                        }
                        break;
                    }
                    case "Network.responseReceived": {
                        ApiCall c = byId.get(id);
                        if (c != null) {
                            c.status = p.path("response").path("status").asInt();
                            c.mimeType = p.path("response").path("mimeType").asText();
                            p.path("response").path("headers").fields().forEachRemaining(h -> c.responseHeaders.put(h.getKey(), h.getValue().asText()));
                            ts.get(id)[1] = p.path("timestamp").asDouble(0);
                        }
                        break;
                    }
                    case "Network.responseReceivedExtraInfo": {
                        ApiCall c = byId.get(id);
                        if (c != null) {
                            p.path("headers").fields().forEachRemaining(h -> c.responseHeaders.put(h.getKey(), h.getValue().asText()));
                        }
                        break;
                    }
                    case "Network.loadingFinished": {
                        if (byId.containsKey(id)) {
                            ts.get(id)[2] = p.path("timestamp").asDouble(0);
                        }
                        break;
                    }
                    case "Network.loadingFailed": {
                        ApiCall c = byId.get(id);
                        if (c != null) {
                            c.error = p.path("errorText").asText();
                            ts.get(id)[2] = p.path("timestamp").asDouble(0);
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        for (ApiCall c : byId.values()) {
            double[] t = ts.get(c.requestId);
            double end = t[2] > 0 ? t[2] : t[1];
            if (t[0] > 0 && end >= t[0]) {
                c.durationMs = Math.round((end - t[0]) * 1000);
                c.endMs = c.startMs + c.durationMs;
            }
            if (driver instanceof ChromiumDriver) {
                ChromiumDriver cd = (ChromiumDriver) driver;
                if (c.requestBody == null && Boolean.TRUE.equals(hasPost.get(c.requestId))) {
                    try {
                        c.requestBody = String.valueOf(cd.executeCdpCommand("Network.getRequestPostData", Map.<String, Object>of("requestId", c.requestId)).get("postData"));
                    } catch (RuntimeException e) {
                        c.requestBody = "(the request body is no longer held by the browser)";
                    }
                }
                if (c.status > 0 && c.error == null) {
                    try {
                        Map<String, Object> b = cd.executeCdpCommand("Network.getResponseBody", Map.<String, Object>of("requestId", c.requestId));
                        boolean binary = Boolean.TRUE.equals(b.get("base64Encoded"));
                        c.responseBody = binary ? "(binary response, " + c.mimeType + ")" : String.valueOf(b.get("body"));
                    } catch (RuntimeException e) {
                        c.responseBody = "(the response body is no longer held by the browser)";
                    }
                }
            }
            c.requestBody = cut(c.requestBody);
            c.responseBody = cut(c.responseBody);
        }
        return new ArrayList<>(byId.values());
    }

    private static String cut(String s) {
        return s != null && s.length() > 30_000 ? s.substring(0, 30_000) + "... (cut: " + s.length() + " characters)" : s;
    }

    /** Severe browser-console messages, secret-masked, for failure diagnostics. */
    public static List<String> severeConsole(WebDriver driver) {
        List<String> out = new ArrayList<>();
        try {
            for (LogEntry le : driver.manage().logs().get(LogType.BROWSER)) {
                out.add(SecretMasker.mask(le.getMessage()));
            }
        } catch (Exception ignored) {
            // console log not available
        }
        return out;
    }

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    private static String rootDomain(String host) {
        String[] parts = host.split("\\.");
        return parts.length >= 2 ? parts[parts.length - 2] + "." + parts[parts.length - 1] : host;
    }
}
