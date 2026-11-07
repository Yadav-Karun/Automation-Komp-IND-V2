package utils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;

/**
 * Toasts disappear after a few seconds, so a MutationObserver (injected into every new document)
 * records their text. Tests can then assert a confirmation really was displayed.
 */
public final class ToastWatcher {

    private static final String SCRIPT = String.join("\n",
            "(function(){",
            " if(window.__kompToasts) return; window.__kompToasts=[];",
            " const sel='[data-sonner-toast],[role=status],[role=alert],[aria-live]';",
            " const scan=()=>{ document.querySelectorAll(sel).forEach(e=>{",
            "   const t=(e.innerText||'').trim().replace(/\\s+/g,' ');",
            "   if(t && window.__kompToasts[window.__kompToasts.length-1]!==t && !window.__kompToasts.includes(t)) window.__kompToasts.push(t);",
            " });};",
            " new MutationObserver(scan).observe(document,{childList:true,subtree:true,characterData:true});",
            "})();");

    private ToastWatcher() {
    }

    public static void install(WebDriver driver) {
        ((ChromiumDriver) driver).executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", Map.of("source", SCRIPT));
    }

    private static void ensure(WebDriver driver) {
        ((JavascriptExecutor) driver).executeScript(SCRIPT);
    }

    /** Waits until no toast is on screen, so messages of a previous action cannot be mistaken for the next one. */
    public static void waitNoneVisible(WebDriver driver, int seconds) {
        // error toasts can stay on screen and cover buttons: close them first
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "document.querySelectorAll('[data-sonner-toast] button').forEach(b=>{try{b.click()}catch(e){}});");
        } catch (RuntimeException ignored) {
            // page not ready
        }
        Instant end = Instant.now().plus(Duration.ofSeconds(seconds));
        while (Instant.now().isBefore(end)) {
            try {
                Object n = ((JavascriptExecutor) driver).executeScript(
                        "return document.querySelectorAll('[data-sonner-toast]').length;");
                if (n instanceof Number && ((Number) n).intValue() == 0) {
                    return;
                }
            } catch (RuntimeException e) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public static void clear(WebDriver driver) {
        ensure(driver);
        ((JavascriptExecutor) driver).executeScript("window.__kompToasts.length=0;");
    }

    @SuppressWarnings("unchecked")
    public static List<String> all(WebDriver driver) {
        try {
            ensure(driver);
            Object o = ((JavascriptExecutor) driver).executeScript("return window.__kompToasts;");
            return o == null ? List.of() : new ArrayList<>((List<String>) o);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Waits for a toast whose text contains the fragment (case-insensitive); returns its text or null. */
    public static String waitFor(WebDriver driver, String fragment, int seconds) {
        Instant end = Instant.now().plus(Duration.ofSeconds(seconds));
        String f = fragment.toLowerCase();
        do {
            for (String t : all(driver)) {
                if (t.toLowerCase().contains(f)) {
                    return t;
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (Instant.now().isBefore(end));
        return null;
    }
}
