package base;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.logging.LogType;

import utils.ConfigManager;
import utils.ToastWatcher;

public final class DriverFactory {

    private static final String PROFILE_PREFIX = "komp-ind-profile-";
    /** Temporary browser profile of every open browser, so quit() can delete it (they are ~70 MB each). */
    private static final Map<WebDriver, Path> PROFILES = new ConcurrentHashMap<>();

    private DriverFactory() {
    }

    public static ChromeDriver create() {
        return create(false);
    }

    /**
     * {@code parallel = true}: one of SEVERAL browsers that run at the same time (the EOR happy flow runs its two candidates in parallel, each in its own browser with its own temporary
     * profile): Chrome must not slow down a window that another window covers, else its pages stop loading.
     */
    public static ChromeDriver create(boolean parallel) {
        String browser = ConfigManager.get("Browser", "chrome");
        if (!"chrome".equalsIgnoreCase(browser)) {
            throw new IllegalArgumentException("Unsupported browser '" + browser + "' (only chrome is configured)");
        }
        ChromeOptions o = new ChromeOptions();
        o.addArguments("--disable-notifications", "--no-first-run", "--no-default-browser-check",
                "--password-store=basic", "--remote-allow-origins=*", "--disable-search-engine-choice-screen");
        if (parallel) {
            o.addArguments("--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding", "--disable-background-timer-throttling");
        }
        Path profile;
        try {
            profile = Files.createTempDirectory(PROFILE_PREFIX).toAbsolutePath();
            o.addArguments("--user-data-dir=" + profile);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create temporary browser profile", e);
        }
        if (ConfigManager.getBool("Headless", false)) {
            o.addArguments("--headless=new", "--window-size=1920,1080");
        } else {
            o.addArguments("--start-maximized"); // visible runs use the whole screen
        }
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("credentials_enable_service", false);
        prefs.put("profile.password_manager_enabled", false);
        prefs.put("profile.password_manager_leak_detection", false);
        o.setExperimentalOption("prefs", prefs);

        if (ConfigManager.getBool("capture.network", true)) {
            LoggingPreferences logs = new LoggingPreferences();
            logs.enable(LogType.PERFORMANCE, Level.ALL);
            logs.enable(LogType.BROWSER, Level.SEVERE);
            o.setCapability("goog:loggingPrefs", logs);
            Map<String, Object> perf = new HashMap<>();
            perf.put("enableNetwork", true);
            perf.put("enablePage", false);
            o.setExperimentalOption("perfLoggingPrefs", perf);
        }

        ChromeDriver driver = null;
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= 3 && driver == null; attempt++) {
            try {
                driver = new ChromeDriver(o);
            } catch (RuntimeException e) {
                // seen 2026-11-14: "session not created: Chrome instance exited" at the very start (Chrome had just been updated); a second try starts normally
                lastError = e;
                utils.Log.warn("Chrome did not start (try " + attempt + " of 3): " + String.valueOf(e.getMessage()).split("\\R")[0]);
                try {
                    Thread.sleep(3000); // documented: Chrome needs a moment after it exited at start
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (driver == null) {
            deleteQuietly(profile); // do not leave an unused profile behind when Chrome cannot start
            throw lastError;
        }
        PROFILES.put(driver, profile);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(60));
        if (!ConfigManager.getBool("Headless", false)) {
            driver.manage().window().maximize();
        }
        ToastWatcher.install(driver);
        utils.PageReady.install(driver); // every picture waits until the page has finished (no request on its way, no loading sign)
        return driver;
    }

    /** Closes the browser (also when it was already closed by hand) and deletes its temporary profile folder. */
    /** Set by a run that was stopped on purpose (-Deor.stop.after=...): the person wants to look at the browser, so it is not closed at the end of the run. */
    private static volatile boolean keepOpen;

    /** The browser stays open when the run ends (also {@code -Dkeep.browser.open=true}); its temporary profile is not deleted either. */
    public static void keepBrowserOpen() {
        keepOpen = true;
    }

    public static void quit(WebDriver driver) {
        if (driver == null) {
            return;
        }
        if (keepOpen || ConfigManager.getBool("keep.browser.open", false)) {
            utils.Log.info("The browser is left open on purpose (the run was stopped for a look at the page); close it by hand when you are done.");
            return;
        }
        try {
            driver.quit();
        } catch (RuntimeException ignored) {
            // browser already gone
        }
        Path profile = PROFILES.remove(driver);
        if (profile != null) {
            deleteQuietly(profile);
        }
    }

    /** Removes browser profiles older than a day that earlier runs left behind (never touches a profile in use today). */
    public static void cleanOldProfiles() {
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        long cutoff = System.currentTimeMillis() - Duration.ofDays(1).toMillis();
        try (java.util.stream.Stream<Path> s = Files.list(tmp)) {
            s.filter(p -> p.getFileName().toString().startsWith(PROFILE_PREFIX))
                    .filter(p -> p.toFile().lastModified() < cutoff)
                    .forEach(DriverFactory::deleteQuietly);
        } catch (IOException | RuntimeException ignored) {
            // housekeeping only
        }
    }

    private static void deleteQuietly(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Chrome may still hold a file for a moment: the rest is removed, the next clean-up gets the remainder
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // housekeeping only
        }
    }

    public static String browserVersion(WebDriver driver) {
        return ((ChromeDriver) driver).getCapabilities().getBrowserName() + " "
                + ((ChromeDriver) driver).getCapabilities().getBrowserVersion();
    }
}
