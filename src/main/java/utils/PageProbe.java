package utils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

/** Opens a URL and classifies where the application really lands (login page, denied, admin content, blank...). */
public final class PageProbe {

    /** Text that only appears on admin / EOR console pages. */
    private static final List<String> ADMIN_MARKERS = List.of("Register Client", "Every client registered", "Contracts & MSA",
            "Roles & Permissions", "EOR Financials", "AOR Financials", "Template Management");
    private static final Pattern DENIED = Pattern.compile("(?i)(not authori[sz]ed|unauthori[sz]ed|forbidden|access denied|"
            + "permission|don't have access|do not have access|\\b403\\b|\\b404\\b|not found|page.*(doesn't|does not) exist)");

    public static final class Landing {
        public final String requested;
        public final String url;
        public final String path;
        public final String text;
        public final boolean loginForm;
        public final boolean adminContent;
        public final boolean blank;
        public final boolean denied;

        Landing(String requested, String url, String text, boolean loginForm) {
            this.requested = requested;
            this.url = url;
            this.path = pathOf(url);
            this.text = text;
            this.loginForm = loginForm;
            this.blank = text.trim().length() < 12;
            this.adminContent = ADMIN_MARKERS.stream().anyMatch(text::contains);
            this.denied = DENIED.matcher(text).find();
        }

        /** Where the browser ended up and what is on the page, in plain sentences. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (requested.startsWith("http")) {
                sb.append("The address ").append(requested).append(" was opened and the browser ended up on the page ").append(path).append(". ");
            } else {
                sb.append("After ").append(requested).append(" the browser is on the page ").append(path).append(". ");
            }
            sb.append(loginForm ? "The login form is shown. " : "The login form is not shown. ");
            sb.append(adminContent ? "The page shows content of the admin area. " : "No content of the admin area is shown. ");
            if (blank) {
                sb.append("The page is empty. ");
            } else {
                sb.append("The page starts with: \"").append(PlainText.oneLine(text, 100)).append("\".");
            }
            return sb.toString().trim();
        }
    }

    private PageProbe() {
    }

    /** Navigates (full page load) and waits until the page has settled (login shown, or text stable for 1.5 s). */
    public static Landing open(WebDriver driver, String url, int maxSeconds) {
        driver.get(url);
        return settle(driver, url, maxSeconds);
    }

    public static Landing settle(WebDriver driver, String requested, int maxSeconds) {
        Instant end = Instant.now().plus(Duration.ofSeconds(maxSeconds));
        String last = null;
        Instant stableSince = Instant.now();
        while (Instant.now().isBefore(end)) {
            boolean login = !driver.findElements(By.id("password")).isEmpty() && driver.getCurrentUrl().contains("/login");
            String text = bodyText(driver);
            if (login && text.length() > 30) {
                return new Landing(requested, driver.getCurrentUrl(), text, true);
            }
            if (text.equals(last)) {
                if (text.trim().length() > 40 && Duration.between(stableSince, Instant.now()).toMillis() > 1500) {
                    break;
                }
            } else {
                last = text;
                stableSince = Instant.now();
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        String text = bodyText(driver);
        boolean login = !driver.findElements(By.id("password")).isEmpty();
        return new Landing(requested, driver.getCurrentUrl(), text, login);
    }

    private static String bodyText(WebDriver driver) {
        try {
            Object o = ((JavascriptExecutor) driver).executeScript("return document.body ? document.body.innerText : ''");
            return o == null ? "" : o.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String pathOf(String url) {
        int i = url.indexOf("://");
        int slash = i < 0 ? url.indexOf('/') : url.indexOf('/', i + 3);
        return slash < 0 ? "/" : url.substring(slash);
    }
}
