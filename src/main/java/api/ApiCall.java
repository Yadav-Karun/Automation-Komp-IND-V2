package api;

/** One observed XHR/Fetch call. Query strings, headers, cookies and bodies are deliberately NOT recorded. */
public class ApiCall {

    public String method;
    public String url;
    public int status = -1;
    public String error;

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
