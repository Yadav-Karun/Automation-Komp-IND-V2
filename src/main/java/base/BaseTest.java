package base;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.lang.reflect.Method;
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
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import model.ClientData;
import reporting.ExcelReportWriter;
import reporting.ExecutionInfo;
import reporting.ExtentManager;
import reporting.ResultCollector;
import reporting.RunBanner;
import reporting.TestReporter;
import utils.ConfigManager;
import utils.Connectivity;
import utils.FormFeedback.Outcome;
import utils.Log;
import utils.PlainText;
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

    /** Test cases of this class (planned) and finished so far: shown in the bar of the browser ({@link RunBanner}). */
    private int plannedCases = -1; // counted just before the first case: the test class has decided by then which cases exist (e.g. a reused client has no first-login cases)
    private int finishedCases;
    private boolean scenarioMode;
    /** The list in the bar: {label, state} per planned case; the state is p / f / s / r / w (see {@link RunBanner}). */
    private final java.util.List<String[]> board = new java.util.ArrayList<>();
    private long runStartMs = System.currentTimeMillis(); // the timer of the bar counts from here

    // ================================================================== lifecycle

    @BeforeClass(alwaysRun = true)
    public void startClass(ITestContext context) {
        try {
            ConfigManager.env(); // fail fast on a bad / production environment
            DriverFactory.cleanOldProfiles();
            ExecutionInfo.begin(getClass().getSimpleName(), context.getName(), module());
            ResultCollector.reset();
            PlainText.resetWordingNotes();
            Log.init(ExecutionInfo.logs().resolve("execution.log"));
            ExtentManager.get();
            reporting.ScenarioBoard.reset();
            reporting.ScenarioBoard.openOverallSummary(); // the first entry of every report
            Log.info("Report folder " + ExecutionInfo.id() + " | env=" + ConfigManager.env() + " | " + ConfigManager.baseUrl());
            driver = DriverFactory.create();
            Log.info("Browser: " + DriverFactory.browserVersion(driver) + " | OS: " + System.getProperty("os.name"));
            browserReady();
            finishedCases = 0;
            runStartMs = System.currentTimeMillis();
        } catch (RuntimeException | Error e) {
            Log.error("The test class could not start: " + e.getMessage()
                    + " (check config/config.properties, config/secrets.local.properties and config/documents.properties)");
            DriverFactory.quit(driver);
            driver = null;
            throw e;
        }
    }

    /**
     * The module of the test class, taken from its package (tests.client -> Client-Onboarding, tests.project -> Project-Management, ...): it is the first tag
     * of every case in the report. Every module has its own folder under tests/.
     */
    protected String module() {
        String pkg = getClass().getPackageName();
        String last = pkg.substring(pkg.lastIndexOf('.') + 1);
        return switch (last) {
            case "client" -> ExecutionInfo.MODULE_CLIENT;
            case "project" -> "Project-Management";
            case "aor" -> "Employee-AOR"; // tests.employee.aor
            case "eor" -> "Employee-EOR"; // tests.employee.eor
            default -> last.isEmpty() ? ExecutionInfo.MODULE_CLIENT : Character.toUpperCase(last.charAt(0)) + last.substring(1);
        };
    }

    /** Hook: a browser was started (or restarted). Create page objects here; sessions of an earlier browser are gone. */
    /** Closes the browser and opens a new one (the sessions are lost; {@link #browserReady()} runs again). Used when the application says "Too many sign-in attempts". */
    protected void restartBrowser() {
        Log.warn("Closing the browser and opening a new one.");
        DriverFactory.quit(driver);
        driver = DriverFactory.create();
        browserReady();
        RunBanner.update(driver, plannedCases, finishedCases, runStartMs, board);
    }

    protected void browserReady() {
        // nothing by default
    }

    @BeforeMethod(alwaysRun = true)
    public void ensureBrowserAlive() {
        if (plannedCases < 0) {
            List<String> scenarios = plannedScenarios();
            if (scenarios != null && !scenarios.isEmpty()) {
                scenarioMode = true; // a class of independent scenarios: the bar counts and lists SCENARIOS, not test methods
                plannedCases = scenarios.size();
                board.clear();
                scenarios.forEach(label -> board.add(new String[] { label, "w" }));
            } else {
                plannedCases = countPlannedCases();
                buildBoard();
            }
            Log.info((scenarioMode ? "Scenarios planned for this run: " : "Test cases planned for this run: ") + plannedCases);
            RunBanner.update(driver, plannedCases, finishedCases, runStartMs, board);
        }
        if (!scenarioMode) {
            markRunning();
        }
        if (driver != null && alive(driver)) {
            recoverFromNetworkLoss();
            RunBanner.update(driver, plannedCases, finishedCases, runStartMs, board);
            return;
        }
        Log.warn("The browser is gone (closed by hand or crashed). Starting a new one; admin / client sessions are lost and "
                + "are signed in again when a case needs them.");
        DriverFactory.quit(driver);
        driver = DriverFactory.create();
        browserReady();
        RunBanner.update(driver, plannedCases, finishedCases, runStartMs, board);
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
        boolean notSelected = result.getThrowable() != null && "Not selected by -Dcases".equals(result.getThrowable().getMessage());
        if (!notSelected) {
            if (!scenarioMode) {
                finishedCases++; // a case that -Dcases filtered out does not count as run
                markFinished(result, r == null ? null : r.name());
            }
        }
        RunBanner.update(driver, plannedCases, finishedCases, runStartMs, board); // before the report file is written (that takes longer with every case): the bar must not lag
        ExtentManager.flush();
    }

    /** A class of independent scenarios returns the label of every scenario ("TC | id | title") before the first one runs: the progress box then counts scenarios. Null = one entry per test method. */
    protected List<String> plannedScenarios() {
        return null;
    }

    /** Called when the class ends, before the report is written: the place to fill the summary entries. */
    protected void beforeReportClosed() {
        // nothing by default
    }

    /** A scenario starts (the label starts with {@code prefix}, e.g. "TC-AOR-EDGE-01 | W04"): its line in the progress box is highlighted. */
    protected void scenarioStarted(String prefix) {
        if (!scenarioMode) {
            return;
        }
        for (String[] c : board) {
            if ("w".equals(c[1]) && c[0].startsWith(prefix)) {
                c[1] = "r";
                break;
            }
        }
        RunBanner.update(driver, plannedCases, finishedCases, runStartMs, board);
    }

    /** A scenario ended; {@code state}: p = passed, f = failed, s = not run / blocked / not judged. */
    protected void scenarioFinished(String prefix, String state) {
        if (!scenarioMode) {
            return;
        }
        for (String[] c : board) {
            if ("r".equals(c[1]) && c[0].startsWith(prefix)) {
                c[1] = state;
                break;
            }
        }
        finishedCases++;
        RunBanner.update(driver, plannedCases, finishedCases, runStartMs, board);
    }

    /** The list of the bar before the first case: with -Dcases the ids that were asked for, else every test method (a data-driven one once per row). */
    private void buildBoard() {
        board.clear();
        try {
            String filter = ConfigManager.get("cases");
            if (filter != null) {
                for (String id : filter.split(",")) {
                    if (!id.isBlank()) {
                        board.add(new String[] { id.trim(), "w" });
                    }
                }
                return;
            }
            java.util.List<Method> methods = new java.util.ArrayList<>();
            for (Method m : getClass().getMethods()) {
                Test t = m.getAnnotation(Test.class);
                if (t != null && t.enabled()) {
                    methods.add(m);
                }
            }
            methods.sort(java.util.Comparator.comparingInt((Method m) -> m.getAnnotation(Test.class).priority()).thenComparing(Method::getName));
            for (Method m : methods) {
                Test t = m.getAnnotation(Test.class);
                String name = m.getName().replaceAll("([a-z0-9])([A-Z])", "$1 $2");
                if (t.dataProvider().isEmpty()) {
                    for (int i = 0; i < Math.max(1, t.invocationCount()); i++) {
                        board.add(new String[] { name, "w" });
                    }
                } else {
                    for (Object row : providerRowObjects(t.dataProvider())) {
                        board.add(new String[] { name + " " + row, "w" });
                    }
                }
            }
        } catch (RuntimeException e) {
            // the list is a convenience: the counts still work
        }
    }

    private java.util.List<Object> providerRowObjects(String name) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (Method p : getClass().getMethods()) {
            DataProvider dp = p.getAnnotation(DataProvider.class);
            if (dp != null && name.equals(dp.name().isEmpty() ? p.getName() : dp.name()) && p.getParameterCount() == 0) {
                try {
                    Object rows = p.invoke(this);
                    java.util.Iterator<?> it = rows instanceof Object[] ? java.util.Arrays.asList((Object[]) rows).iterator()
                            : rows instanceof java.util.Iterator ? (java.util.Iterator<?>) rows : null;
                    while (it != null && it.hasNext()) {
                        Object row = it.next();
                        out.add(row instanceof Object[] && ((Object[]) row).length > 0 ? ((Object[]) row)[0] : row);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    out.add("");
                }
            }
        }
        return out;
    }

    /** The first case that has not run yet is the one running now. */
    private void markRunning() {
        for (String[] c : board) {
            if ("r".equals(c[1])) {
                return;
            }
        }
        for (String[] c : board) {
            if ("w".equals(c[1])) {
                c[1] = "r";
                return;
            }
        }
    }

    private void markFinished(ITestResult result, String realName) {
        String[] entry = null;
        for (String[] c : board) {
            if ("r".equals(c[1])) {
                entry = c;
                break;
            }
        }
        if (entry == null) {
            for (String[] c : board) {
                if ("w".equals(c[1])) {
                    entry = c;
                    break;
                }
            }
        }
        if (entry != null) {
            entry[1] = result.getStatus() == ITestResult.SUCCESS ? "p" : result.getStatus() == ITestResult.SKIP ? "s" : "f";
            if (realName != null && !realName.isBlank()) {
                entry[0] = realName;
            }
        }
    }

    /**
     * How many test cases this run will go through: every @Test method, a data-driven one once per row of its data provider (the number TestNG reports as "Tests run").
     * With -Dcases it is the number of ids that were asked for.
     */
    private int countPlannedCases() {
        try {
            String filter = ConfigManager.get("cases");
            if (filter != null) {
                return (int) java.util.Arrays.stream(filter.split(",")).filter(s -> !s.isBlank()).count();
            }
            int n = 0;
            for (Method m : getClass().getMethods()) {
                Test t = m.getAnnotation(Test.class);
                if (t == null || !t.enabled()) {
                    continue;
                }
                n += t.dataProvider().isEmpty() ? Math.max(1, t.invocationCount()) : providerRows(t.dataProvider());
            }
            return n;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private int providerRows(String name) {
        for (Method p : getClass().getMethods()) {
            DataProvider dp = p.getAnnotation(DataProvider.class);
            if (dp != null && name.equals(dp.name().isEmpty() ? p.getName() : dp.name()) && p.getParameterCount() == 0) {
                try {
                    Object rows = p.invoke(this);
                    if (rows instanceof Object[]) {
                        return ((Object[]) rows).length;
                    }
                    if (rows instanceof java.util.Iterator) {
                        int c = 0;
                        for (java.util.Iterator<?> it = (java.util.Iterator<?>) rows; it.hasNext(); it.next()) {
                            c++;
                        }
                        return c;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return 1;
                }
            }
        }
        return 1;
    }

    @AfterClass(alwaysRun = true)
    public void finishClass() {
        try {
            beforeReportClosed();
            reporting.ScenarioBoard.closeOverallSummary();
        } catch (RuntimeException e) {
            Log.warn("The summaries of the report could not be written: " + e.getMessage());
        }
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
        Log.info("Browser bar     : planned " + plannedCases + " test cases, finished " + finishedCases);
        Log.info("HTML report : " + ExecutionInfo.reportFile().toAbsolutePath());
        Log.info("Screenshots : " + ExecutionInfo.screenshots().toAbsolutePath());
        List<String> wording = PlainText.wordingNotes();
        if (!wording.isEmpty()) {
            // the reports are read by non-technical people: these sentences used technical words and were translated automatically
            Log.warn("Report wording: " + wording.size() + " sentence(s) used technical words (translated automatically in the report) - reword them at the source:");
            wording.stream().limit(15).forEach(w -> Log.warn("   - " + w));
        }
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
                String shown = o.quotedTexts();
                throw new AssertionError("The application accepted an entry that it should have refused: it moved on"
                        + (shown.isEmpty() ? " and showed no message." : " and showed: " + shown + "."));
            }
            if (!o.hasFeedback()) {
                rep.defect("The application refused the entry but did not say why: no message was shown to the user",
                        "A clear message that tells the user what is wrong", o.describe());
            } else {
                rawErrorLeak(rep, o);
                missingKeywords(rep, o, keywords);
            }
            return o.describe();
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
                rep.defect("The application showed technical error text meant for developers instead of a friendly message",
                        "A short, friendly message in everyday words", "The application showed: \"" + m.replaceAll("\\s+", " ").trim() + "\"");
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
            rep.defect("The message does not say which field is wrong (it should mention " + PlainText.quote(missing) + ")",
                    "A message that mentions " + PlainText.quote(List.of(keywords)), o.describe());
        }
    }

    /** Fails the test when the case recorded soft problems (they were all written to the report already). */
    protected static void assertNoDefects(TestReporter rep) {
        Assert.assertTrue(rep.defects().isEmpty(),
                rep.defects().size() + " problem(s) found:\n - " + String.join("\n - ", rep.defects()));
    }
}
