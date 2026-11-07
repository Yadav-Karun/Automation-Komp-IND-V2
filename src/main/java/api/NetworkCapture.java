package api;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.WebDriver;
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
