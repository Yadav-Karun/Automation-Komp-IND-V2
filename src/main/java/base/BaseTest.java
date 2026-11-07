package base;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import org.openqa.selenium.NoSuchWindowException;
import org.openqa.selenium.WebDriver;
import org.testng.Assert;
import org.testng.ITestContext;
import org.testng.ITestResult;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;

import model.ClientData;
import reporting.ExcelReportWriter;
import reporting.ExecutionInfo;
import reporting.ExtentManager;
import reporting.ResultCollector;
import reporting.TestReporter;
import utils.ConfigManager;
import utils.Connectivity;
import utils.FormFeedback.Outcome;
import utils.Log;
import utils.TestDataGenerator;

/**
 * Lifecycle shared by the test classes, plus the helpers the negative / edge cases are built from.
 *
 *  - ONE REPORT PER TEST CLASS: reports/&lt;ClassName&gt;_&lt;timestamp&gt;/ with &lt;ClassName&gt;.html, its own screenshots/, logs/,
 *    api-evidence/, the Excel workbook and the Jira bug folders (see {@link ExecutionInfo}).
 *  - ONE BROWSER PER CLASS, started visible and maximized. If the window is closed or crashes in the middle of a run a new
 *    browser is started before the next case (sessions are signed in again when a case needs them).
 *  - Teardown always runs: the browser is closed, its temporary profile deleted and the reports are written even when cases fail.
 */
public abstract class BaseTest {

    protected WebDriver driver;

    // ================================================================== lifecycle

    @BeforeClass(alwaysRun = true)
    public void startClass(ITestContext context) {
        try {
            ConfigManager.env(); // fail fast on a bad / production environment
            DriverFactory.cleanOldProfiles();
            ExecutionInfo.begin(getClass().getSimpleName(), context.getName());
            ResultCollector.reset();
            Log.init(ExecutionInfo.logs().resolve("execution.log"));
            ExtentManager.get();
            Log.info("Report folder " + ExecutionInfo.id() + " | env=" + ConfigManager.env() + " | " + ConfigManager.baseUrl());
            driver = DriverFactory.create();
            Log.info("Browser: " + DriverFactory.browserVersion(driver) + " | OS: " + System.getProperty("os.name"));
            browserReady();
        } catch (RuntimeException | Error e) {
            Log.error("The test class could not start: " + e.getMessage()
                    + " (check config/config.properties, config/secrets.local.properties and config/documents.properties)");
            DriverFactory.quit(driver);
            driver = null;
            throw e;
        }
    }

    /** Hook: a browser was started (or restarted). Create page objects here; sessions of an earlier browser are gone. */
    protected void browserReady() {
        // nothing by default
    }

    @BeforeMethod(alwaysRun = true)
    public void ensureBrowserAlive() {
        if (driver != null && alive(driver)) {
            recoverFromNetworkLoss();
            return;
        }
        Log.warn("The browser is gone (closed by hand or crashed). Starting a new one; admin / client sessions are lost and "
                + "are signed in again when a case needs them.");
        DriverFactory.quit(driver);
        driver = DriverFactory.create();
        browserReady();
    }

    /**
     * Before every case: when the browser was left on Chrome's connection-error page (the network dropped during the previous case), wait until the
     * application answers again and go on from a clean page. Without this a short outage failed every later case ({@link Connectivity}).
     */
    private void recoverFromNetworkLoss() {
        if (driver == null || !Connectivity.onErrorPage(driver)) {
            return;
        }
        int max = ConfigManager.getInt("network.wait.max.seconds", 600);
        Log.warn("The browser shows its connection-error page: the network was lost during the previous case. Waiting for the application "
                + "to answer again (up to " + max + " s) ...");
        boolean back = Connectivity.waitForApp(driver, ConfigManager.baseUrl() + "/login", max);
        Log.warn(back ? "The network is back: going on from the login page; sessions are signed in again when a case needs them."
                : "The application did not answer within the wait budget: the next cases will be reported as skipped (network lost).");
        browserHealed();
    }

    /** Hook: the browser was sent back to the login page after a network loss - every session of this browser is gone. */
    protected void browserHealed() {
        // nothing by default
    }

    private static boolean alive(WebDriver d) {
        try {
            Set<String> handles = d.getWindowHandles();
            if (handles.isEmpty()) {
                return false;
            }
            try {
                d.getTitle();
            } catch (NoSuchWindowException closedTab) {
                d.switchTo().window(handles.iterator().next()); // e.g. the e-mail tab was closed by hand
                d.getTitle();
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @AfterMethod(alwaysRun = true)
    public void finishTest(ITestResult result) {
        TestReporter r = TestReporter.current();
        if (r != null) {
            r.finish(result);
        }
        ExtentManager.flush();
    }

    @AfterClass(alwaysRun = true)
    public void finishClass() {
        DriverFactory.quit(driver);
        driver = null;
        if (ExecutionInfo.root() == null) {
            return;
        }
        try {
            ExtentManager.flush();
            ExcelReportWriter.write();
            ExtentManager.reset();
        } finally {
            summary();
            Log.close();
        }
    }

    /** The result in plain words, at the end of the console output and the log: nobody has to read a stack trace to know the outcome. */
    private void summary() {
        List<ResultCollector.Case> cases = ResultCollector.all();
        long pass = cases.stream().filter(c -> "PASS".equals(c.status)).count();
        long fail = cases.stream().filter(c -> "FAIL".equals(c.status)).count();
        long skip = cases.stream().filter(c -> "SKIP".equals(c.status)).count();
        String bar = "=".repeat(100);
        Log.info(bar);
        Log.info(ExecutionInfo.className() + " finished: " + cases.size() + " test case(s) | " + pass + " passed | " + fail
                + " FAILED (each failure is an application defect or a failed check: see the report) | " + skip + " skipped");
        Log.info("HTML report : " + ExecutionInfo.reportFile().toAbsolutePath());
        Log.info("Screenshots : " + ExecutionInfo.screenshots().toAbsolutePath());
        if (ExcelReportWriter.excelEnabled()) {
            Log.info("Excel report: " + ExecutionInfo.root().resolve(ExecutionInfo.excelFileName()).toAbsolutePath());
        }
        if (ExcelReportWriter.jiraEnabled()) {
            Log.info("Jira bugs   : " + ExecutionInfo.root().resolve("jira-bugs").toAbsolutePath());
        }
        Log.info(bar);
    }

    // ================================================================== helpers for negative / edge cases

    /** Fresh throw-away test data for the negative cases (valid base values, timestamped name, yopmail addresses): not an anime client name. */
    protected ClientData newData() {
        return TestDataGenerator.generateScratch();
    }

    /** True when -Dcases is not given, or lists this id. */
    protected static boolean isSelected(String id) {
        String filter = ConfigManager.get("cases");
        return filter == null || Arrays.stream(filter.split(",")).anyMatch(s -> s.trim().equalsIgnoreCase(id));
    }

    /** True when -Dcases restricts the run to some case ids. */
    protected static boolean casesFilterActive() {
        return ConfigManager.get("cases") != null;
    }

    /**
     * Optional filter: -Dcases=R-30,R-31 runs only those case ids (everything else is skipped without a report row).
     * Lets a single case be re-run without repeating the rest of the suite.
     */
    protected static void onlyIfSelected(String id) {
        if (!isSelected(id)) {
            throw new SkipException("Not selected by -Dcases");
        }
    }

    protected static String slug(String id) {
        return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    /**
     * Verdict rules for a "must reject" case:
     *  - the application moved on (accepted the input)        -> FAIL (defect: invalid input accepted)
     *  - rejected but showed no message at all                 -> FAIL (defect: silent rejection)
     *  - rejected with a message that misses the expected key  -> FAIL (defect: misleading message)
     *  - rejected with a relevant message                      -> PASS
     */
    protected void mustReject(String id, String group, String title, String input, String expected, ClientData data,
            String[] keywords, Callable<Outcome> attempt) {
        TestReporter rep = TestReporter.start(id, group, title, group + " negative test: " + title, input, expected, driver, data);
        rep.phase(slug(id), title, expected, () -> {
            Outcome o = attempt.call();
            if (o.accepted) {
                throw new AssertionError("Application ACCEPTED input that should be rejected. " + o.describe());
            }
            if (!o.hasFeedback()) {
                rep.defect("Input rejected silently: no validation message was shown",
                        "A visible message that explains what is wrong", o.describe());
            } else {
                missingKeywords(rep, o, keywords);
                rawErrorLeak(rep, o);
            }
            return "Rejected. " + o.describe();
        });
        assertNoDefects(rep);
    }

    private static final Pattern RAW_ERROR = Pattern.compile(
            "(?i)(violates|unique constraint|character varying|duplicate key|sqlstate|integrityerror|traceback|"
                    + "DETAIL:|null value in column|syntax error|java\\.|org\\.|\\bexception\\b)");

    /** Internal database / framework text must never be shown to the user. */
    protected static void rawErrorLeak(TestReporter rep, Outcome o) {
        List<String> all = new ArrayList<>(o.messages);
        all.addAll(o.toasts);
        for (String m : all) {
            if (RAW_ERROR.matcher(m).find()) {
                rep.defect("Raw database/internal error text is shown to the user",
                        "A friendly validation message (no SQL / constraint / exception text)", m);
                return;
            }
        }
    }

    protected static void missingKeywords(TestReporter rep, Outcome o, String[] keywords) {
        if (keywords == null || keywords.length == 0) {
            return;
        }
        String all = (String.join(" | ", o.messages) + " | " + String.join(" | ", o.toasts)).toLowerCase(Locale.ROOT);
        List<String> missing = new ArrayList<>();
        for (String k : keywords) {
            if (!all.contains(k.toLowerCase(Locale.ROOT))) {
                missing.add(k);
            }
        }
        if (!missing.isEmpty()) {
            rep.defect("Validation message does not mention the offending field/rule (missing: " + missing + ")",
                    "A message mentioning " + List.of(keywords), o.describe());
        }
    }

    /** Fails the test when the case recorded soft defects (they were all written to the report already). */
    protected static void assertNoDefects(TestReporter rep) {
        Assert.assertTrue(rep.defects().isEmpty(),
                rep.defects().size() + " defect(s) found:\n - " + String.join("\n - ", rep.defects()));
    }
}
