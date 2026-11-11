package api;

/** One observed XHR/Fetch call. Query strings, headers, cookies and bodies are deliberately NOT recorded. */
public class ApiCall {

    public String method;
    public String url;
    public int status = -1;
    public String error;

    // ---- the full record of a call (filled only by NetworkCapture.drainFull, which a scenario report asks for; nothing is masked: user's decision for the AOR report)
    public String requestId;
    public String fullUrl;
    public java.util.Map<String, String> requestHeaders = new java.util.LinkedHashMap<>();
    public String requestBody;
    public java.util.Map<String, String> responseHeaders = new java.util.LinkedHashMap<>();
    public String responseBody;
    public String mimeType;
    public long startMs;
    public long endMs;
    public long durationMs = -1;
    /** The step (phase) of the scenario during which the call was seen. */
    public int phase;

    public ApiCall(String method, String url) {
        this.method = method;
        this.url = url;
    }

    public boolean isServerError() {
        return status >= 500;
    }

    public boolean isClientError() {
        return status >= 400 && status < 500;
    }

    @Override
    public String toString() {
        return method + " " + url + " -> " + (error != null ? "FAILED(" + error + ")" : status);
    }
}
