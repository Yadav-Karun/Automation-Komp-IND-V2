package pages;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;

import base.BasePage;
import utils.ConfigManager;
import utils.FormFeedback;
import utils.Log;
import utils.ToastWatcher;

/**
 * Single login screen used by admin, EOR and client users.
 *
 * The application rate-limits sign-ins ("Too many sign-in attempts. Please wait a few minutes", HTTP 429). A run must not fail
 * (or, worse, report a false PASS for a rejected login) just because of that, so both {@link #login} and {@link #attempt}
 * wait and try again, at most {@code login.ratelimit.max.wait.seconds} (default 600) in total.
 */
public class LoginPage extends BasePage {

    private static final int RETRY_SECONDS = 30;
    /** How long a freshly loaded login page may take to show its form (the page itself needs ~1 s; see {@link #freshLogin}). */
    private static final int FORM_WAIT_SECONDS = 45;

    private final By email = By.id("email");
    private final By password = By.id("password");
    private final By signIn = By.xpath("//button[@type='submit' and normalize-space()='Sign in']");

    public LoginPage(WebDriver driver) {
        super(driver);
    }

    public void open(String url) {
        driver.get(url);
        visible(email);
    }

    /** Ends any existing session (cookies + web storage) and shows an empty login form. */
    public void freshLogin(String baseUrl) {
        driver.get(baseUrl + "/login");
        ((JavascriptExecutor) driver).executeScript("try{localStorage.clear();sessionStorage.clear();}catch(e){}");
        ((ChromiumDriver) driver).executeCdpCommand("Network.clearBrowserCookies", java.util.Map.of());
        driver.get(baseUrl + "/login");
        if (!isDisplayed(email, FORM_WAIT_SECONDS)) {
            // seen twice in three full runs (B-01, then B-02: 20-40 s without the form, then fine; the page loads in 0.6 s the rest of the time):
            // say what the page was doing, reload once and wait again before giving up
            Log.warn("The login form is not visible after " + FORM_WAIT_SECONDS + " s - " + pageHint() + " - reloading once.");
            driver.navigate().refresh();
            if (!isDisplayed(email, FORM_WAIT_SECONDS)) {
                throw new AssertionError("The login form did not appear within " + 2 * FORM_WAIT_SECONDS + " s (two loads of /login). " + pageHint());
            }
        }
    }

    /** What the login page looks like right now (state, text, slow requests, console errors): evidence for a form that does not appear. */
    private String pageHint() {
        try {
            Object o = ((JavascriptExecutor) driver).executeScript(
                    "const slow=performance.getEntriesByType('resource').filter(r=>r.duration>3000).slice(0,4)"
                            + ".map(r=>r.name.split('?')[0].slice(-60)+' '+Math.round(r.duration)+'ms').join('; ');"
                            + "const e=document.getElementById('email');"
                            + "const st=e?('in DOM, display='+getComputedStyle(e).display+', visibility='+getComputedStyle(e).visibility):'not in DOM';"
                            + "const txt=document.body?document.body.innerText.replace(/\\s+/g,' ').slice(0,140):'(no body)';"
                            + "return 'readyState='+document.readyState+' | path='+location.pathname+' | email field: '+st+' | text: '+txt"
                            + "+' | slow requests: '+(slow||'none');");
            List<String> console = api.NetworkCapture.severeConsole(driver);
            return String.valueOf(o) + (console.isEmpty() ? "" : " | console: " + String.join(" / ", console.subList(0, Math.min(2, console.size()))));
        } catch (RuntimeException e) {
            return "page state unavailable (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** True when any of the messages is the application's sign-in rate-limit message. */
    public static boolean isRateLimited(Collection<String> messages) {
        for (String m : messages) {
            if (m != null && m.toLowerCase().contains("too many")) {
                return true;
            }
        }
        return false;
    }

    private static long budgetEnd() {
        return System.currentTimeMillis() + ConfigManager.getInt("login.ratelimit.max.wait.seconds", 600) * 1000L;
    }

    private static void waitOutRateLimit() {
        Log.warn("The application rate-limits sign-ins ('Too many sign-in attempts'). Waiting " + RETRY_SECONDS
                + " s before trying again (total wait is capped by login.ratelimit.max.wait.seconds).");
        try {
            Thread.sleep(RETRY_SECONDS * 1000L); // documented exception: the limit is time based, nothing in the page to wait for
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Negative-test sign-in: a null value leaves the field empty. Never throws on rejection; reports what the app did.
     * "Accepted" means the app left /login. A rate-limit answer is waited out and the attempt repeated, so the outcome
     * describes the validation under test and not the rate limit (unless the wait budget is used up).
     */
    public FormFeedback.Outcome attempt(String user, String pass) {
        long end = budgetEnd();
        while (true) {
            if (user != null) {
                type(email, user);
            }
            if (pass != null) {
                type(password, pass);
            }
            FormFeedback.Outcome o = FormFeedback.submit(driver, 8, () -> click(signIn), () -> !urlContains("/login"));
            List<String> shown = new ArrayList<>(o.messages);
            shown.addAll(o.toasts);
            if (!o.accepted && isRateLimited(shown) && System.currentTimeMillis() < end) {
                waitOutRateLimit();
                continue;
            }
            return o;
        }
    }

    /** The password input must be a masked field. */
    public String passwordFieldType() {
        return visible(password).getAttribute("type");
    }

    public void typeIntoPasswordOnly(String pass) {
        type(password, pass);
    }

    public boolean isDisplayed() {
        return isDisplayed(email, timeout) && isDisplayedNow(signIn);
    }

    /** Signs in and waits until the app leaves /login; fails with the visible message if it does not. */
    public void login(String user, String pass) {
        long end = budgetEnd();
        while (true) {
            ToastWatcher.clear(driver);
            type(email, user);
            type(password, pass);
            click(signIn);
            try {
                waitUrlNotContains("/login");
                return;
            } catch (TimeoutException e) {
                List<String> shown = new ArrayList<>(ToastWatcher.all(driver));
                shown.addAll(FormFeedback.inlineMessages(driver));
                if (isRateLimited(shown) && System.currentTimeMillis() < end) {
                    waitOutRateLimit();
                    continue;
                }
                throw new AssertionError("Login did not complete for the supplied account. Visible messages: " + shown);
            }
        }
    }
}
