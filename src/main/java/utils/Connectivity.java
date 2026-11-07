package utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;

/**
 * A lost internet connection is not an application result. Seen once: the machine was offline for 13 seconds in the middle of a 17-minute run,
 * the browser stayed on Chrome's "This site can't be reached" page while the suite believed it was still signed in, and 150 cases failed or were
 * skipped for 13 minutes after the network was back.
 * <ul>
 *   <li>{@link #isNetworkFailure}: did this failure come from a lost connection (Chrome net:: error in the message, or the browser sits on the error page)?
 *       The case is then reported as SKIP "network lost - run it again", never as FAIL.</li>
 *   <li>{@link #waitForApp}: before the next case the suite waits until the application answers again (the BROWSER is the probe: it navigates to the
 *       login page every 10 s, so a proxy or VPN that only the browser knows is no problem), then goes on from a clean page.</li>
 * </ul>
 * Budget: -Dnetwork.wait.max.seconds (default 600) for the first outage; after one outage that was never over, later waits are short (30 s) so a
 * dead network cannot stretch the run for hours.
 */
public final class Connectivity {

    private static final Pattern NET_ERROR = Pattern.compile(
            "net::ERR_(INTERNET_DISCONNECTED|NAME_NOT_RESOLVED|NETWORK_CHANGED|CONNECTION_(RESET|CLOSED|REFUSED|TIMED_OUT|ABORTED)|TIMED_OUT"
                    + "|ADDRESS_UNREACHABLE|PROXY_CONNECTION_FAILED|TUNNEL_CONNECTION_FAILED|NETWORK_ACCESS_DENIED|SOCKET_NOT_CONNECTED|EMPTY_RESPONSE)");

    /** Chrome's own error page (the DOM of "This site can't be reached"). */
    private static final String ERROR_PAGE_JS =
            "return !!document.getElementById('main-frame-error') || (document.body && document.body.classList.contains('neterror'));";

    private static boolean gaveUp;

    private Connectivity() {
    }

    /** True when the exception (or one of its causes) carries a Chrome network error such as net::ERR_INTERNET_DISCONNECTED. */
    public static boolean isNetworkError(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) {
            if (x.getMessage() != null && NET_ERROR.matcher(x.getMessage()).find()) {
                return true;
            }
        }
        return false;
    }

    /** True when the browser shows Chrome's connection-error page (false when it cannot be asked). */
    public static boolean onErrorPage(WebDriver driver) {
        if (driver == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(((JavascriptExecutor) driver).executeScript(ERROR_PAGE_JS));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The failure was caused by a lost connection: a net:: error in the message, or the browser is left on the connection-error page. */
    public static boolean isNetworkFailure(WebDriver driver, Throwable t) {
        return isNetworkError(t) || onErrorPage(driver);
    }

    /** Short wording for a report: the net::ERR_ code when there is one. */
    public static String describe(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) {
            if (x.getMessage() != null) {
                Matcher m = NET_ERROR.matcher(x.getMessage());
                if (m.find()) {
                    return m.group();
                }
            }
        }
        return "the browser shows its connection-error page";
    }

    /**
     * Waits until the browser can open {@code url} again (it navigates there every 10 s). Returns true when the application answered,
     * false when it did not within the budget. The page that finally loads is left open.
     */
    public static boolean waitForApp(WebDriver driver, String url, int maxSeconds) {
        int budget = gaveUp ? Math.min(maxSeconds, 30) : maxSeconds;
        long end = System.currentTimeMillis() + budget * 1000L;
        int tries = 0;
        while (true) {
            tries++;
            try {
                driver.get(url);
                if (!onErrorPage(driver)) {
                    if (tries > 1) {
                        Log.warn("The application answers again (after " + tries + " attempts).");
                    }
                    gaveUp = false;
                    return true;
                }
            } catch (WebDriverException stillDown) {
                // not reachable yet: try again
            }
            if (System.currentTimeMillis() >= end) {
                gaveUp = true;
                return false;
            }
            try {
                Thread.sleep(10_000L); // documented wait: polling an external outage, every 10 s
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
