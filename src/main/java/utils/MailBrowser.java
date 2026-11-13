package utils;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

/**
 * The browser window that reads the Yopmail inboxes (user, 2026-11-13: "do something about the Yopmail bot check").
 * Yopmail puts a human check ("Please confirm you're human") over the inbox. It is NOT bypassed: a person clicks it. To keep that to a minimum the mail is read in its OWN
 * browser window with a SAVED profile ({@code -Dyopmail.profile.dir}, default {@code .yopmail-profile} in the project, git-ignored): the window stays open for the whole run (every
 * employee uses it), so the check is shown at most once per run, and what the check accepts is kept in the profile for the next runs.
 */
public final class MailBrowser {

    private static WebDriver driver;

    private MailBrowser() {
    }

    public static synchronized WebDriver get() {
        if (driver != null) {
            try {
                driver.getWindowHandles(); // still alive?
                return driver;
            } catch (RuntimeException closedByHand) {
                driver = null;
            }
        }
        Path profile = Paths.get(ConfigManager.get("yopmail.profile.dir", ".yopmail-profile")).toAbsolutePath();
        ChromeOptions o = new ChromeOptions();
        o.addArguments("--user-data-dir=" + profile, "--start-maximized", "--no-first-run", "--no-default-browser-check");
        if (ConfigManager.getBool("Headless", false)) {
            o.addArguments("--headless=new");
        }
        driver = new ChromeDriver(o);
        Log.info("The mail browser is open (profile " + profile + "). If Yopmail shows \"Please confirm you're human\", click the check in that window.");
        Runtime.getRuntime().addShutdownHook(new Thread(MailBrowser::quit));
        return driver;
    }

    public static synchronized void quit() {
        if (driver != null) {
            try {
                driver.quit();
            } catch (RuntimeException ignored) {
                // already closed
            }
            driver = null;
        }
    }
}
