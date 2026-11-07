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
        String browser = ConfigManager.get("Browser", "chrome");
        if (!"chrome".equalsIgnoreCase(browser)) {
            throw new IllegalArgumentException("Unsupported browser '" + browser + "' (only chrome is configured)");
        }
        ChromeOptions o = new ChromeOptions();
        o.addArguments("--disable-notifications", "--no-first-run", "--no-default-browser-check",
                "--password-store=basic", "--remote-allow-origins=*", "--disable-search-engine-choice-screen");
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

        ChromeDriver driver;
        try {
            driver = new ChromeDriver(o);
        } catch (RuntimeException e) {
            deleteQuietly(profile); // do not leave an unused profile behind when Chrome cannot start
            throw e;
        }
        PROFILES.put(driver, profile);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(60));
        if (!ConfigManager.getBool("Headless", false)) {
            driver.manage().window().maximize();
        }
        ToastWatcher.install(driver);
        return driver;
    }

    /** Closes the browser (also when it was already closed by hand) and deletes its temporary profile folder. */
    public static void quit(WebDriver driver) {
        if (driver == null) {
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
