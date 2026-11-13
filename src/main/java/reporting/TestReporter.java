package reporting;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.WebDriver;
import org.testng.ITestResult;

import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.MediaEntityBuilder;
import com.aventstack.extentreports.Status;
import com.aventstack.extentreports.model.Media;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import api.ApiCall;
import api.NetworkCapture;
import base.DriverFactory;
import model.ClientData;
import utils.ConfigManager;
import utils.Log;
import utils.PlainText;
import utils.SecretMasker;

/**
 * Runs the journey as ordered "phases". Each phase becomes its own report step with expected vs actual,
 * an ordered screenshot (NN_slug_PASS / _FAIL / _DEFECT) and the API calls observed during it.
 * A hard failure stops the journey; a soft "defect" is recorded but lets dependent steps continue.
 */
public final class TestReporter {

    @FunctionalInterface
    public interface PhaseAction {
        /** Performs the phase; returns a short description of the ACTUAL result. */
        String run() throws Exception;
    }

    private static final ThreadLocal<TestReporter> CURRENT = new ThreadLocal<>();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private WebDriver driver;
    private final ExtentTest test;
    private final ClientData data;
    private final LocalDateTime started = LocalDateTime.now();
    private final List<String[]> results = new ArrayList<>();      // idx, title, status, screenshot
    private final List<String> defects = new ArrayList<>();
    /** One plain sentence per problem found: they explain the failure in the header of the case. */
    private final List<String> defectHeadlines = new ArrayList<>();
    /** The DEFECT screenshot taken during the current phase (the phase result row reuses it). */
    private String phaseDefectShot;
    /** HTML shown under Expected / Actual of the current phase (the table of {@link ValueChecks}). */
    private String phaseHtml = "";
    private String testName;
    private String description;
    private final Map<String, List<String>> network = new LinkedHashMap<>();
    private final List<String> apiObservations = new ArrayList<>();

    private final ResultCollector.Case record = new ResultCollector.Case();

    private int seq;
    private int evidenceCount;
    private ExtentTest node;
    private String slug = "setup";
    /** The case that was current when this one started (a set-up case can run inside another case). */
    private TestReporter previous;
    private boolean finished;

    // ---- scenario reporting: every scenario of a class is ONE report entry (see ScenarioBoard)
    private String scenarioId;
    private int scenarioNo;
    private String parentTc;
    /** The steps to reproduce, in plain words (set by the test class; without them the titles of the steps are used). */
    private List<String> scenarioSteps;
    /** Pictures of this scenario in the order they were taken (shown again under "Artifacts" at the top of the entry). */
    private final List<String> shots = new ArrayList<>();
    private String scenarioTestData = "";
    private String scenarioExpected = "";
    private final Map<String, String> scenarioFiles = new LinkedHashMap<>();
    private String scenarioImpact = "";
    private boolean fullApi;
    private final List<ApiCall> allCalls = new ArrayList<>();
    private Status statusOverride;
    private String resultLabel;
    private String skipWord;
    private boolean classified;

    /** "ID - title" of this case (for the list in the progress bar). */
    public String name() {
        return testName;
    }

    /** After the browser was replaced (restart): pictures and server requests come from the new browser. */
    public void setDriver(WebDriver newDriver) {
        this.driver = newDriver;
    }

    private TestReporter(WebDriver driver, ExtentTest test, ClientData data) {
        this.driver = driver;
        this.test = test;
        this.data = data;
    }

    /** Starts one test case: one Extent test + one Excel row per case. */
    public static TestReporter start(String id, String group, String title, String description, String input,
            String expected, WebDriver driver, ClientData data) {
        return start(id, group, title, description, input, expected, driver, data, null);
    }

    /**
     * Starts one test case of a module that is not about a client (e.g. the project happy flow). {@code headerLine} replaces the "Client: ... | login ..."
     * line of the case header and of the execution summary; it is asked again when the case ends, so it can show data that was generated during the case.
     * The tag of the module comes from {@link ExecutionInfo#module()}.
     */
    public static TestReporter start(String id, String group, String title, String description, String input,
            String expected, WebDriver driver, ClientData data, java.util.function.Supplier<String> headerLine) {
        title = PlainText.simplify(title);
        description = cleanDescription(PlainText.simplify(description));
        String testName = plain(id + " - " + title);
        ExtentTest t = ExtentManager.get().createTest(testName, plain(description));
        // the report strips spaces from a tag ("E2E profile check" -> "E2Eprofilecheck"): hyphens keep it readable
        t.assignCategory(ExecutionInfo.module(), group == null ? "General" : group.trim().replaceAll("\\s+", "-"));
        TestReporter r = new TestReporter(driver, t, data);
        r.headerLine = headerLine;
        r.testName = testName;
        r.description = plain(description);
        r.fullApi = ConfigManager.getBool("capture.api.full", true);
        r.record.id = id;
        r.record.group = group;
        r.record.title = title;
        r.record.input = input;
        r.record.expected = expected;
        r.record.started = r.started;
        r.record.run = ExecutionInfo.id();
        t.info(esc("Project: " + ExecutionInfo.PROJECT) + "<br>"
                + (ExecutionInfo.isClientModule() ? "" : "Test: " + esc(ExecutionInfo.testName()) + "<br>Module: " + esc(ExecutionInfo.module()) + "<br>")
                + "Environment: " + esc(ConfigManager.env()) + " (" + esc(ConfigManager.baseUrl()) + ")<br>"
                + "Browser: " + esc(DriverFactory.browserVersion(driver)) + "<br>"
                + "Started: " + TS.format(r.started) + "<br>"
                + esc(r.header()));
        r.previous = CURRENT.get();
        CURRENT.set(r);
        Log.info("Test started: " + testName + " | client=" + data.legalName + " type=" + data.clientType);
        return r;
    }

    public static TestReporter current() {
        return CURRENT.get();
    }

    // ------------------------------------------------------------------ phases

    public void phase(String slug, String title, String expected, PhaseAction action) {
        this.slug = slug;
        this.evidenceCount = 0;
        int idx = ++seq;
        node = test.createNode(plain(String.format("Step %d - %s", idx, PlainText.simplify(title))));
        Log.info(String.format("Phase %02d START - %s", idx, title));
        NetworkCapture.drain(driver); // discard anything before this phase
        RunBanner.ensure(driver); // the progress box is on the screen for the person who watches the run
        carried.clear();
        int defectsBefore = defects.size();
        phaseDefectShot = null;
        phaseHtml = "";
        try {
            String actual = action.run();
            List<ApiCall> calls = collectCalls();
            if (defects.size() > defectsBefore) {
                // the phase ran to the end but found a product defect (soft check): the phase result is FAIL, never PASS
                String shot = phaseDefectShot != null ? phaseDefectShot : ScreenshotManager.capture(driver, fileName(idx, "FAIL"), true);
                shots.add(shot);
                node.fail(detail(expected, "PROBLEM FOUND - " + actual) + phaseHtml + technical(apiHtml(idx, title, calls)), media(shot));
                results.add(new String[] { String.valueOf(idx), title, "FAIL", shot });
                recordPhase(idx, title, expected, "PROBLEM FOUND - " + SecretMasker.mask(actual), "FAIL", shot);
                Log.error(String.format("Phase %02d FAIL (defect) - %s | %s", idx, title, actual));
                return;
            }
            String shot = ScreenshotManager.capture(driver, fileName(idx, "PASS"), false);
            shots.add(shot);
            node.pass(detail(expected, actual) + phaseHtml + technical(apiHtml(idx, title, calls)), media(shot));
            results.add(new String[] { String.valueOf(idx), title, "PASS", shot });
            recordPhase(idx, title, expected, SecretMasker.mask(actual), "PASS", shot);
            Log.info(String.format("Phase %02d PASS - %s | %s", idx, title, actual));
        } catch (Throwable t) {
            if (!(t instanceof org.testng.SkipException) && utils.Connectivity.isNetworkFailure(driver, t)) {
                // a lost connection is not an application result: SKIP with the reason, never FAIL (the next case waits for the network)
                t = new org.testng.SkipException("The internet connection to the application was lost during this step, so the result is unknown "
                        + "(this is not a problem of the application). Run the test again. [" + utils.Connectivity.describe(t) + "]");
            }
            if (t instanceof org.testng.SkipException) {
                // a case can skip itself mid-way ("only one page of clients"): that is a SKIP with its reason, not a failure
                List<ApiCall> skipCalls = collectCalls();
                String skipReason = SecretMasker.mask(t.getMessage() == null ? "skipped" : t.getMessage());
                String skipShot = ScreenshotManager.capture(driver, fileName(idx, "SKIP"), false);
                shots.add(skipShot);
                node.skip(detail(expected, "NOT RUN - " + skipReason) + phaseHtml + technical(apiHtml(idx, title, skipCalls)), media(skipShot));
                results.add(new String[] { String.valueOf(idx), title, "SKIP", skipShot });
                recordPhase(idx, title, expected, "NOT RUN - " + skipReason, "SKIP", skipShot);
                Log.info(String.format("Phase %02d SKIP - %s | %s", idx, title, skipReason));
                throw (org.testng.SkipException) t;
            }
            List<ApiCall> calls = collectCalls();
            String shot = ScreenshotManager.capture(driver, fileName(idx, "FAIL"), true);
            String technicalText = SecretMasker.mask(t.getMessage() == null ? t.toString() : t.getMessage());
            // a failed check of the test (AssertionError) already says in words what is wrong; anything else (a button was not found, a timeout ...)
            // is a problem of the test run, not a sentence for the reader: the words stay in the folded technical block
            String reason = t instanceof AssertionError ? PlainText.assertion(technicalText) : STEP_NOT_COMPLETED;
            String console = String.join("<br>", NetworkCapture.severeConsole(driver).stream().map(TestReporter::esc).toList());
            String label = t instanceof AssertionError ? "PROBLEM FOUND - " : "THE TEST COULD NOT COMPLETE THIS STEP - ";
            shots.add(shot);
            node.fail(detail(expected, label + reason) + phaseHtml
                    + technical("<b>Technical message:</b> " + esc(PlainText.oneLine(technicalText, 700))
                            + "<br><b>Error trace:</b><pre>" + esc(SecretMasker.mask(stack(t))) + "</pre>"
                            + (console.isEmpty() ? "" : "<b>Errors written by the browser:</b><br>" + console)
                            + apiHtml(idx, title, calls)), media(shot));
            results.add(new String[] { String.valueOf(idx), title, "FAIL", shot });
            recordPhase(idx, title, expected, label + reason, "FAIL", shot);
            Log.error(String.format("Phase %02d FAIL - %s | %s", idx, title, technicalText));
            throw t instanceof Error ? (Error) t : new AssertionError(reason, t);
        }
    }

    /** The sentence for a step that could not be carried out (not a result of the application): the technical words are in the folded block. */
    private static final String STEP_NOT_COMPLETED = "The page did not show what the test needed (for example a button, a field or a message did not appear in time). "
            + "The technical message is in the folded details below.";

    /** Fills the table of checked values of one page: the test adds one row per value ({@link ValueChecks#eq} / {@link ValueChecks#check}). */
    @FunctionalInterface
    public interface CheckAction {
        void run(ValueChecks checks) throws Exception;
    }

    /**
     * A phase that checks many values of ONE page. The values are shown as one table (wrong ones in red and listed first), the page gets ONE screenshot,
     * and the verdict comes from the table: any wrong value = the phase and the case FAIL with "N of M values are wrong - the other values are correct".
     */
    public void phaseChecks(String slug, String title, String expected, CheckAction action) {
        phase(slug, title, expected, () -> {
            ValueChecks checks = new ValueChecks();
            try {
                action.run(checks);
            } finally {
                attachHtml(checks.html()); // also when a step threw: show what was checked until then
            }
            if (checks.failed() > 0) {
                defect(title, expected, checks.failureText(), false, checks.failureText());
            }
            return checks.summary();
        });
    }

    /** HTML (e.g. a table of checked values) shown under Expected / Actual of the current phase. */
    public void attachHtml(String html) {
        phaseHtml = html == null ? "" : html;
    }

    private final List<ApiCall> carried = new ArrayList<>();

    /** API calls seen so far in the current phase; the same calls still appear in the phase's API evidence afterwards. */
    public List<ApiCall> observeApi() {
        carried.addAll(NetworkCapture.drain(driver));
        return new ArrayList<>(carried);
    }

    private List<ApiCall> collectCalls() {
        List<ApiCall> all = new ArrayList<>(carried);
        carried.clear();
        List<ApiCall> fresh = fullApi ? NetworkCapture.drainFull(driver) : NetworkCapture.drain(driver);
        if (fullApi) {
            fresh.forEach(c -> c.phase = seq);
            allCalls.addAll(fresh);
        }
        all.addAll(fresh);
        return all;
    }

    /** Extra screenshot inside the current phase (e.g. second page of a long form). */
    public void evidence(String label) {
        evidence(label, label.replace('_', ' '));
    }

    /** Extra screenshot with a plain caption ({@code label} only names the file). */
    public void evidence(String label, String caption) {
        String shot = ScreenshotManager.capture(driver,
                fileName(seq, "PASS") .replace("_PASS.png", "_" + (++evidenceCount) + "_" + label + "_PASS.png"), false);
        shots.add(shot);
        node.info("Extra screenshot: " + esc(PlainText.simplify(caption)), media(shot));
    }

    /** Soft check: a genuine problem of the application that does not block the rest of the journey. Write {@code title} as a plain sentence. */
    public void defect(String title, String expected, String actual) {
        defect(title, expected, actual, true, title);
    }

    /**
     * @param screenshot false when the phase itself takes the one screenshot of the page and shows the details (a table of values): no extra picture,
     *                   no extra red line - the problem is only registered (the phase and the case turn FAIL, the report header says why)
     * @param headline   the sentence that explains the problem in the header of the failed case (the title, or for a table of values the list of wrong values)
     */
    private void defect(String title, String expected, String actual, boolean screenshot, String headline) {
        title = PlainText.simplify(title);
        String line = "Step " + seq + " - " + title + " | What should happen: " + PlainText.simplify(expected) + " | What happened: " + PlainText.simplify(actual);
        defects.add(line);
        defectHeadlines.add(SecretMasker.mask(PlainText.simplify(headline)));
        record.defects.add(title + " | expected: " + expected + " | actual: " + SecretMasker.mask(actual));
        if (screenshot) {
            String shot = ScreenshotManager.capture(driver, fileName(seq, "DEFECT"), true);
            record.defectScreenshot = shot;
            phaseDefectShot = shot;
            node.fail("<b>PROBLEM FOUND:</b> " + esc(title) + detail(expected, actual), media(shot));
        }
        Log.error("DEFECT " + line);
    }

    public void note(String message) {
        if (node != null) {
            node.info(esc(PlainText.simplify(message)));
        }
        Log.info(message);
    }

    public List<String> defects() {
        return defects;
    }

    // ------------------------------------------------------------------ finish

    public void finish(ITestResult result) {
        String overall = switch (result.getStatus()) {
            case ITestResult.SUCCESS -> "PASS";
            case ITestResult.SKIP -> "SKIP";
            default -> "FAIL";
        };
        finish(overall, result.getThrowable());
    }

    /**
     * Finishes the case explicitly: PASS | FAIL | SKIP. Used for set-up cases that run INSIDE another test method
     * (their row must be written before the outer case continues). Calling it twice is harmless.
     */
    public void finish(String overall, Throwable failure) {
        if (finished) {
            return;
        }
        finished = true;
        if ("PASS".equals(overall) && !defects.isEmpty()) {
            overall = "FAIL"; // a case that found a defect is never reported as passed
        }
        if (!classified && scenarioId == null) {
            overall = classifyLegacy(overall, failure);
        }
        LocalDateTime ended = LocalDateTime.now();
        StringBuilder sb = new StringBuilder();
        sb.append("<b>Result of this test: ").append(resultLabel != null ? resultLabel : resultWord(overall)).append("</b><br>")
                .append("Started: ").append(TS.format(started)).append(" | Ended: ").append(TS.format(ended))
                .append(" | Duration: ").append(duration(Duration.between(started, ended))).append("<br>")
                .append(esc(header())).append("<br>")
                .append("<table border='1' cellpadding='4'><tr><th>#</th><th>Step</th><th>Result</th><th>Screenshot</th></tr>");
        for (String[] r : results) {
            sb.append("<tr><td>").append(r[0]).append("</td><td>").append(esc(PlainText.simplify(r[1]))).append("</td><td>").append(resultWord(r[2]))
                    .append("</td><td>").append(r[3] == null ? "-" : "<a href='" + r[3] + "'>" + r[3] + "</a>").append("</td></tr>");
        }
        sb.append("</table>");
        if (!defects.isEmpty()) {
            sb.append("<br><b>Problems found (").append(defects.size()).append("):</b><ul>");
            defects.forEach(d -> sb.append("<li>").append(esc(d)).append("</li>"));
            sb.append("</ul>");
        }
        StringBuilder tech = new StringBuilder("<br>The requests the pages sent to the server are recorded for information only (no description of the "
                + "server interface was supplied). The results above are based on what a user sees on the screen.");
        if (!apiObservations.isEmpty()) {
            tech.append("<br><b>Server replies that were not successful (4xx = request refused, 5xx = server error):</b><ul>");
            apiObservations.forEach(o -> tech.append("<li>").append(esc(o)).append("</li>"));
            tech.append("</ul>");
        }
        sb.append(technical(tech.toString()));
        Status s = statusOverride != null ? statusOverride : "PASS".equals(overall) ? Status.PASS : "SKIP".equals(overall) ? Status.SKIP : Status.FAIL;
        ExtentTest summary = test.createNode("Summary of this test");
        summary.log(s, sb.toString());
        if (failure != null && !"PASS".equals(overall) && (defects.isEmpty() || "SKIP".equals(overall))) {
            // a failure with problems listed above says the same twice: the sentence is only added when it is the one explanation
            summary.log(Status.INFO, (("SKIP".equals(overall)) ? "Why it was not run: " : "Why it failed: ")
                    + esc(PlainText.simplify(PlainText.assertion(SecretMasker.mask(String.valueOf(failure.getMessage()))))));
        }
        writeNetworkSummary(overall);
        if ("FAIL".equals(overall)) {
            nameTheFailure(failure);
        } else if ("SKIP".equals(overall)) {
            nameTheSkip(failure);
        }
        record.status = overall;
        record.ended = ended;
        record.failure = failure == null ? null : SecretMasker.mask(String.valueOf(failure.getMessage()));
        record.actual = record.phases.isEmpty()
                ? ("SKIP".equals(overall) && failure != null ? SecretMasker.mask(String.valueOf(failure.getMessage())) : "")
                : record.phases.get(record.phases.size() - 1).actual;
        ResultCollector.add(record);
        Log.info("Test finished: " + overall + " in " + duration(Duration.between(started, ended)));
        if (previous != null && !previous.finished) {
            CURRENT.set(previous);
        } else {
            CURRENT.remove();
        }
    }

    /**
     * Test names and descriptions go into the Extent report UNESCAPED - as HTML in the sidebar and as text inside the report's own script
     * block. A name such as "Search text '<script>alert(1)</script>'" (cases X-07, X-72) then ran the script when the report was opened and could
     * break the page with its literal end-tag. A zero-width space after every '<' keeps the text looking the same but makes it inert.
     */
    static String plain(String s) {
        return s == null ? null : s.replace("<", "<​");
    }

    /** Supplies the line under the case title for modules that are not about a client (null = the client line). */
    private java.util.function.Supplier<String> headerLine;

    private String header() {
        if (headerLine != null) {
            try {
                return headerLine.get();
            } catch (RuntimeException e) {
                Log.warn("Case header line not written: " + e.getMessage());
            }
        }
        return clientLine(data);
    }

    /** "Client: Black Clover | type EOR | login asta@yopmail.com | password ... | temporary password ..." (secrets shown unless masking is on). */
    private static String clientLine(ClientData d) {
        StringBuilder sb = new StringBuilder("Client: ").append(d.legalName).append(" | type ").append(d.clientType)
                .append(" | login e-mail ").append(PlainText.oneLine(SecretMasker.maskEmail(d.email), 70));
        if (d.password != null) {
            sb.append(" | password ").append(SecretMasker.reveal(d.password));
        }
        if (d.temporaryPassword != null) {
            sb.append(" | temporary password ").append(SecretMasker.reveal(d.temporaryPassword));
        }
        return sb.toString();
    }

    /**
     * A case title says what the case CHECKS ("... documents are shown"), which reads like a false statement next to a red Fail.
     * So a failed case gets the reason in front of its description line (right under the title), in plain sentences:
     * "FAILED because: <what was wrong> | What was checked: <what the case checks>". The name itself is left alone (a longer name wraps and crowds the header).
     */
    private void nameTheFailure(Throwable failure) {
        try {
            String why;
            if (!defectHeadlines.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String h : defectHeadlines.subList(0, Math.min(2, defectHeadlines.size()))) {
                    String one = h.replaceAll("\\s+", " ").trim();
                    sb.append(sb.length() == 0 ? "" : " ").append(one).append(one.endsWith(".") ? "" : ".");
                }
                why = sb.toString();
            } else if (failure != null && failure.getMessage() != null) {
                why = PlainText.firstSentences(PlainText.assertion(SecretMasker.mask(failure.getMessage())), 2);
            } else {
                why = "see the steps below";
            }
            why = PlainText.oneLine(PlainText.simplify(why), 480);
            test.getModel().setDescription(plain("FAILED because: " + why + "  |  What was checked: " + description));
        } catch (RuntimeException e) {
            Log.warn("Failed case not renamed in the report: " + e.getClass().getSimpleName());
        }
    }

    /** A skipped case says why right under its title (the reason is not hidden in the folded summary). */
    private void nameTheSkip(Throwable skip) {
        try {
            String why = skip == null || skip.getMessage() == null ? "see the summary below" : PlainText.assertion(SecretMasker.mask(skip.getMessage()));
            why = PlainText.oneLine(PlainText.simplify(why), 480);
            test.getModel().setDescription(plain((skipWord == null ? "NOT RUN" : skipWord) + " because: " + why + "  |  What was checked: " + description));
        } catch (RuntimeException e) {
            Log.warn("Skipped case not renamed in the report: " + e.getClass().getSimpleName());
        }
    }

    /** The description of the case without the internal group code ("A1 Register client negative test: Email ..." -> "Register client - Email ..."). */
    static String cleanDescription(String d) {
        if (d == null) {
            return "";
        }
        return d.replaceFirst("^[A-Z][0-9]+\\s+", "").replace(" negative test: ", " - ");
    }

    /** PASS / FAIL / SKIP in words. */
    private static String resultWord(String status) {
        return "PASS".equals(status) ? "Passed" : "SKIP".equals(status) ? "Not run" : "Failed";
    }

    /** Evidence for developers (server requests, error traces) goes into a folded block, so the text above it stays readable for everybody. */
    private static String technical(String inner) {
        if (inner == null || inner.isEmpty()) {
            return "";
        }
        return "<details style='margin-top:10px'><summary style='cursor:pointer;color:#555'><b>Technical details (only for developers - click to open)</b></summary>"
                + inner + "</details>";
    }

    private void writeNetworkSummary(String overall) {
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("executionId", ExecutionInfo.id());
            doc.put("overall", overall);
            doc.put("apiValidation", "NOT CONFIGURED (observation only; no documented API contract)");
            doc.put("note", "Only method, path and status are recorded. No query strings, headers, cookies or bodies.");
            doc.put("phases", network);
            doc.put("observations4xx5xx", apiObservations);
            ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            om.writeValue(Files.newOutputStream(ExecutionInfo.apiEvidence().resolve("network-summary.json")), doc);
            // one file per test case, so every bug keeps its own API evidence (network-summary.json only holds the last test)
            doc.put("testCase", record.id);
            om.writeValue(Files.newOutputStream(ExecutionInfo.apiEvidence().resolve("network-" + String.valueOf(record.id).replaceAll("[^A-Za-z0-9._-]+", "_") + ".json")), doc);
        } catch (IOException e) {
            Log.warn("network-summary.json not written: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private void recordPhase(int idx, String title, String expected, String actual, String status, String shot) {
        ResultCollector.Phase p = new ResultCollector.Phase();
        p.index = idx;
        p.title = title;
        p.expected = expected;
        p.actual = actual;
        p.status = status;
        p.screenshot = shot;
        record.phases.add(p);
    }

    private String fileName(int idx, String status) {
        return String.format("%02d_%s_%s.png", idx, slug, status);
    }

    private static Media media(String relPath) {
        return relPath == null ? null : MediaEntityBuilder.createScreenCaptureFromPath(relPath).build();
    }

    private static String detail(String expected, String actual) {
        return "<br><b>What should happen:</b> " + esc(PlainText.simplify(expected)) + "<br><b>What happened:</b> " + esc(SecretMasker.mask(PlainText.simplify(actual)));
    }

    private String apiHtml(int idx, String title, List<ApiCall> calls) {
        List<String> lines = new ArrayList<>();
        for (ApiCall c : calls) {
            lines.add(c.toString());
            if (c.isServerError() || c.isClientError() || c.error != null) {
                apiObservations.add(String.format("Phase %02d (%s): %s", idx, title, c));
            }
        }
        network.put(String.format("%02d_%s", idx, slug), lines);
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<br><b>Requests the page sent to the server during this step (recorded for information only):</b><br><small>");
        lines.stream().limit(15).forEach(l -> sb.append(esc(l)).append("<br>"));
        if (lines.size() > 15) {
            sb.append("... and ").append(lines.size() - 15).append(" more (see api-evidence/network-summary.json)");
        }
        return sb.append("</small>").toString();
    }


    // ================================================================== scenario entries (the reporting standard of 2026-11-11)

    /**
     * Starts ONE scenario as its own entry of the report (left panel): the name is "<test case> | <scenario id> | <title>", the tags are the module, the parent test case and the
     * given reusable tags. The entry keeps its own steps, screenshots, files, API calls and verdict; {@link #finishScenario} closes it.
     */
    public static TestReporter startScenario(String parentTc, String id, List<String> tags, String title, String description, String testData, String expected,
            WebDriver driver, ClientData data, java.util.function.Supplier<String> headerLine) {
        title = PlainText.simplify(title);
        description = cleanDescription(PlainText.simplify(description));
        int no = ScenarioBoard.nextNumber(parentTc);
        String testName = plain(parentTc + " | " + no + " | " + title);
        ExtentTest t = ExtentManager.get().createTest(testName, plain(description));
        List<String> cats = new ArrayList<>();
        cats.add(ExecutionInfo.module());
        cats.add(parentTc);
        for (String g : tags) {
            cats.add(g.trim().replaceAll("\\s+", "-"));
        }
        t.assignCategory(cats.toArray(new String[0]));
        TestReporter r = new TestReporter(driver, t, data);
        r.headerLine = headerLine;
        r.testName = testName;
        r.description = plain(description);
        r.scenarioId = id;
        r.scenarioNo = no;
        r.parentTc = parentTc;
        r.scenarioTestData = testData == null ? "" : testData;
        r.scenarioExpected = expected == null ? "" : expected;
        r.record.id = parentTc + " | " + no + " (" + id + ")";
        r.record.group = String.join(", ", tags);
        r.record.title = title;
        r.record.input = testData;
        r.record.expected = expected;
        r.record.started = r.started;
        r.record.run = ExecutionInfo.id();
        // the readable head of the entry (title, severity, steps, expected, actual, artifacts) is written when the scenario ends: it is only then that the result is known
        r.previous = CURRENT.get();
        CURRENT.set(r);
        Log.info("Scenario started: " + testName);
        return r;
    }

    /** The steps to reproduce the scenario in plain words ("Sign in as the AOR user", "Open Employees > AOR ..."). */
    public TestReporter steps(List<String> steps) {
        this.scenarioSteps = steps;
        return this;
    }

    /** Replaces the "test data" line of the entry (for example the list of values a batch of rows tries). */
    public TestReporter testData(String text) {
        this.scenarioTestData = text == null ? "" : text;
        return this;
    }

    /** From now on the full record of every call the page sends (URL, headers, bodies, times) is kept for this scenario. */
    public TestReporter captureFullApi() {
        this.fullApi = true;
        return this;
    }

    /** A file that belongs to this scenario (for example the uploaded workbook): shown as a link in the scenario's evidence. {@code relativePath} is relative to the run folder. */
    public void addFile(String label, String relativePath) {
        scenarioFiles.put(label, relativePath);
    }

    /** What the scenario did to the records of the application (employees created or updated ...). */
    public void impact(String text) {
        this.scenarioImpact = text == null ? "" : text;
    }

    /**
     * Closes a scenario with its verdict. {@code kind}: an application defect (verdict FAIL) or an automation failure (the test could not carry the scenario out: NOT JUDGED) are
     * never the same thing. {@code defectRefs} link the scenario to the known defects (the same defect can be shown by many scenarios). The entry gets the colour of the verdict, a
     * tag for the verdict, one for the kind and one per defect reference, and - for NOT RUN / BLOCKED / NOT JUDGED - the verdict in its name.
     */
    public void finishScenario(ScenarioBoard.Verdict v, ScenarioBoard.Kind kind, List<String> defectRefs, String reason, Throwable failure) {
        if (finished) {
            return;
        }
        this.statusOverride = v.extent;
        this.resultLabel = v.label + (kind == ScenarioBoard.Kind.NONE ? "" : " - " + kind.label);
        this.skipWord = v.label;
        String actual = record.phases.isEmpty() ? "" : String.valueOf(record.phases.get(record.phases.size() - 1).actual);
        test.info(caseHead(v, defectRefs, reason, actual));
        attachApiEvidence();
        classified = true;
        test.assignCategory("Status-" + v.label.replace(' ', '-'));
        if (kind != ScenarioBoard.Kind.NONE) {
            test.assignCategory(kind == ScenarioBoard.Kind.APPLICATION_DEFECT ? "Application-Defect" : "Automation-Failure");
        }
        for (String ref : defectRefs) {
            test.assignCategory(ref);
        }
        Throwable cause = failure != null ? failure : v == ScenarioBoard.Verdict.PASS ? null : new org.testng.SkipException(reason == null ? v.label : reason);
        finish(v == ScenarioBoard.Verdict.PASS ? "PASS" : v == ScenarioBoard.Verdict.FAIL ? "FAIL" : "SKIP", cause);
        if (v != ScenarioBoard.Verdict.PASS && v != ScenarioBoard.Verdict.FAIL) {
            try {
                test.getModel().setName(plain(testName + " [" + v.label + "]"));
            } catch (RuntimeException ignored) {
                // the name stays as it was
            }
        }
        ScenarioBoard.Outcome o = new ScenarioBoard.Outcome();
        o.tc = parentTc;
        o.no = scenarioNo;
        o.id = scenarioId;
        o.title = record.title;
        o.verdict = v;
        o.kind = kind;
        o.defectRefs = new ArrayList<>(defectRefs);
        o.tags = new ArrayList<>(Arrays.asList(String.valueOf(record.group).split(", ")));
        o.reason = reason == null ? "" : reason;
        ScenarioBoard.register(o);
    }


    /**
     * Closes a scenario entry the standard way for suites that keep no defect list: a skip = NOT RUN (BLOCKED when a prerequisite or the network is missing), an exception of the
     * test itself = NOT JUDGED + automation failure, a recorded problem = FAIL + application defect (the same problem text always gets the same AUTO reference), else PASS.
     */
    public ScenarioBoard.Verdict finishAuto(Throwable error, org.testng.SkipException skip) {
        ScenarioBoard.Verdict v;
        ScenarioBoard.Kind kind = ScenarioBoard.Kind.NONE;
        List<String> refs = new ArrayList<>();
        String reason;
        int found = defects.size();
        if (skip != null) {
            String msg = String.valueOf(skip.getMessage());
            v = msg.contains("network connection") || msg.contains("limit") || msg.contains("prerequisite") || msg.startsWith("Blocked: ") ? ScenarioBoard.Verdict.BLOCKED : ScenarioBoard.Verdict.NOT_RUN;
            reason = msg;
        } else if (found > 0) {
            v = ScenarioBoard.Verdict.FAIL;
            kind = ScenarioBoard.Kind.APPLICATION_DEFECT;
            for (String h : defectHeadlines) {
                String ref = ScenarioBoard.autoRef(h);
                if (!refs.contains(ref)) {
                    refs.add(ref);
                }
            }
            if (refs.isEmpty()) {
                refs.add(ScenarioBoard.autoRef(defects.get(0)));
            }
            reason = found + (found == 1 ? " problem" : " problems") + " found in the application (see the steps below).";
        } else if (error != null) {
            v = ScenarioBoard.Verdict.NOT_JUDGED;
            kind = ScenarioBoard.Kind.AUTOMATION_FAILURE;
            reason = "The test could not carry this scenario out (an automation failure, not an application defect): " + PlainText.oneLine(PlainText.assertion(String.valueOf(error.getMessage())), 300);
        } else {
            v = ScenarioBoard.Verdict.PASS;
            reason = "The expected behaviour was observed.";
        }
        finishScenario(v, kind, refs, reason, error != null ? error : skip);
        return v;
    }

    private void attachApiEvidence() {
        if (!allCalls.isEmpty()) {
            test.createNode("API calls observed (" + allCalls.size() + ")").info(apiDetailsHtml());
            writeApiEvidenceFile();
        }
    }

    /**
     * Every case that is closed through {@link #finish} (all test classes of the project) gets the reporting standard automatically: one of the five verdicts, the kind (an assertion
     * that the application broke a rule is an APPLICATION DEFECT; an exception of the test itself - a click that is not possible, a wait that timed out - is an AUTOMATION FAILURE and the
     * case is NOT JUDGED), status / kind / defect tags, a defect reference (the same problem text = the same reference), and the full API record of the case.
     */
    private String classifyLegacy(String overall, Throwable failure) {
        classified = true;
        ScenarioBoard.Verdict v;
        ScenarioBoard.Kind kind = ScenarioBoard.Kind.NONE;
        List<String> refs = new ArrayList<>();
        String msg = failure == null || failure.getMessage() == null ? "" : failure.getMessage();
        if ("PASS".equals(overall)) {
            v = ScenarioBoard.Verdict.PASS;
        } else if ("SKIP".equals(overall)) {
            v = msg.contains("network connection") || msg.contains("limit") || msg.contains("prerequisite") || msg.startsWith("Not run: ") ? ScenarioBoard.Verdict.BLOCKED : ScenarioBoard.Verdict.NOT_RUN;
        } else {
            boolean automation = defects.isEmpty() && failure != null && (!(failure instanceof AssertionError) || msg.startsWith("Could not") || msg.startsWith("Expected condition")
                    || msg.contains("did not appear within") || msg.startsWith("The page did not show what the test needed"));
            if (automation) {
                v = ScenarioBoard.Verdict.NOT_JUDGED;
                kind = ScenarioBoard.Kind.AUTOMATION_FAILURE;
            } else {
                v = ScenarioBoard.Verdict.FAIL;
                kind = ScenarioBoard.Kind.APPLICATION_DEFECT;
                String headline = !defectHeadlines.isEmpty() ? defectHeadlines.get(0) : PlainText.firstSentences(PlainText.assertion(SecretMasker.mask(msg)), 1);
                refs.add(ScenarioBoard.autoRef(headline));
            }
        }
        this.statusOverride = v.extent;
        this.resultLabel = v.label + (kind == ScenarioBoard.Kind.NONE ? "" : " - " + kind.label);
        this.skipWord = v.label;
        attachApiEvidence();
        test.assignCategory("Status-" + v.label.replace(' ', '-'));
        if (kind != ScenarioBoard.Kind.NONE) {
            test.assignCategory(kind == ScenarioBoard.Kind.APPLICATION_DEFECT ? "Application-Defect" : "Automation-Failure");
        }
        refs.forEach(test::assignCategory);
        if (v != ScenarioBoard.Verdict.PASS && v != ScenarioBoard.Verdict.FAIL) {
            try {
                test.getModel().setName(plain(testName + " [" + v.label + "]"));
            } catch (RuntimeException ignored) {
                // the name stays as it was
            }
        }
        ScenarioBoard.Outcome o = new ScenarioBoard.Outcome();
        o.tc = record.group == null || record.group.isBlank() ? "General" : record.group;
        o.id = record.id;
        o.title = record.title;
        o.verdict = v;
        o.kind = kind;
        o.defectRefs = refs;
        o.tags = new ArrayList<>(List.of(o.tc));
        o.reason = msg;
        ScenarioBoard.register(o);
        return v == ScenarioBoard.Verdict.PASS ? "PASS" : v == ScenarioBoard.Verdict.FAIL ? "FAIL" : "SKIP";
    }

    private static String evRow(String k, String v) {
        return "<tr><td><b>" + esc(k) + "</b></td><td>" + esc(v) + "</td></tr>";
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final int BODY_LIMIT = 30000;

    /** Plain words for the verdict of an entry. */
    private static String verdictWords(ScenarioBoard.Verdict v) {
        return switch (v) {
            case PASS -> "Passed";
            case FAIL -> "Failed";
            case NOT_RUN -> "Not run";
            case BLOCKED -> "Blocked (something this scenario needs was missing)";
            case NOT_JUDGED -> "Could not be judged";
        };
    }

    /** The priority that goes with a severity (the reference report uses the same pairing: High = P2, Medium = P3, Low = P4). */
    private static String priorityOf(String severity) {
        return switch (severity) {
            case "High" -> "P2";
            case "Medium" -> "P3";
            case "Low" -> "P4";
            default -> "not set";
        };
    }

    /**
     * The head of a scenario entry, written the way a bug report reads: a headline, "Severity - Priority - Area", the steps to reproduce, what was expected, what the application
     * did, and the artifacts (pictures, files, the number of server calls). Everything a manager needs stands here; the steps below it hold the details.
     */
    private String caseHead(ScenarioBoard.Verdict v, List<String> defectRefs, String reason, String actual) {
        StringBuilder sb = new StringBuilder("<div class='komp-case'>");
        String severity = "";
        for (String ref : defectRefs) {
            String s = ScenarioBoard.defectSeverity(ref);
            if ("High".equals(s) || ("Medium".equals(s) && !"High".equals(severity)) || ("Low".equals(s) && severity.isEmpty())) {
                severity = s;
            }
        }
        String headline = record.title;
        if (v == ScenarioBoard.Verdict.FAIL && !defectRefs.isEmpty()) {
            headline = ScenarioBoard.defectTitle(defectRefs.get(0));
        }
        String area = String.valueOf(record.group).replace(", ", " / ");
        sb.append("<div class='kc-title'>").append(esc(PlainText.simplify(headline))).append("</div><div class='kc-meta'>");
        if (v == ScenarioBoard.Verdict.FAIL) {
            sb.append("Severity: ").append(severity.isEmpty() ? "not rated" : esc(severity)).append(" &middot; Priority: ").append(priorityOf(severity)).append(" &middot; Area: ").append(esc(area));
        } else {
            sb.append("Result: ").append(esc(verdictWords(v))).append(" &middot; Area: ").append(esc(area));
        }
        sb.append(" &middot; Scenario ").append(scenarioNo).append(" of ").append(esc(parentTc)).append(" (id ").append(esc(scenarioId)).append(")</div>");

        List<String> steps = new ArrayList<>();
        if (scenarioSteps != null) {
            steps.addAll(scenarioSteps);
        } else {
            for (String[] r : results) {
                steps.add(r[1].replaceFirst("^[A-Za-z0-9-]+ \\| ", ""));
            }
        }
        if (!steps.isEmpty()) {
            sb.append("<h6>Steps to reproduce</h6><ol>");
            steps.forEach(s -> sb.append("<li>").append(esc(PlainText.simplify(s))).append("</li>"));
            sb.append("</ol>");
        }
        if (!scenarioTestData.isBlank()) {
            sb.append("<h6>Test data</h6><p>").append(esc(PlainText.simplify(scenarioTestData))).append("</p>");
        }
        sb.append("<h6>Expected</h6><p>").append(esc(PlainText.simplify(scenarioExpected))).append("</p>");
        String said = actual == null || actual.isBlank() ? (reason == null ? "-" : reason) : actual;
        sb.append("<h6>Actual</h6><p>").append(esc(PlainText.oneLine(PlainText.simplify(SecretMasker.mask(said)), 900))).append("</p>");
        if (!defectRefs.isEmpty()) {
            StringBuilder refs = new StringBuilder();
            defectRefs.forEach(r -> refs.append(ScenarioBoard.describeDefect(r)).append("; "));
            sb.append("<p><b>Known problem:</b> ").append(esc(refs.toString())).append("</p>");
        }
        if (v != ScenarioBoard.Verdict.PASS && v != ScenarioBoard.Verdict.FAIL && reason != null && !reason.isBlank()) {
            sb.append("<p><b>Why:</b> ").append(esc(PlainText.oneLine(PlainText.simplify(reason), 600))).append("</p>");
        }
        boolean any = !shots.isEmpty() || !scenarioFiles.isEmpty() || !allCalls.isEmpty();
        if (any) {
            sb.append("<h6>Artifacts</h6><div class='kc-art'>");
            int shown = 0;
            for (String s : new java.util.LinkedHashSet<>(shots)) {
                if (s != null && shown++ < 6) {
                    sb.append("<a href='").append(esc(s)).append("' target='_blank'><img src='").append(esc(s)).append("' alt='screenshot'></a>");
                }
            }
            scenarioFiles.forEach((label, path) -> sb.append("<div>").append(esc(label)).append(": <a href='").append(esc(path)).append("'>").append(esc(path)).append("</a></div>"));
            if (!allCalls.isEmpty()) {
                sb.append("<div>Calls the page sent to the server: ").append(allCalls.size()).append(" (listed in order at the bottom of this entry, with payload and response)</div>");
            }
            sb.append("</div>");
        }
        sb.append("<div class='kc-small'>").append(esc(scenarioImpact.isBlank() ? "" : scenarioImpact)).append(scenarioImpact.isBlank() ? "" : "<br>")
                .append("Started: ").append(TS.format(started)).append(" &middot; ").append(esc(header())).append("</div></div>");
        return sb.toString();
    }

    /** The path (and query) of an address, the part a reader recognises. */
    private static String endpointOf(ApiCall c) {
        String u = c.fullUrl == null ? c.url : c.fullUrl;
        try {
            java.net.URI uri = java.net.URI.create(u);
            String p = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return uri.getRawQuery() == null ? p : p + "?" + uri.getRawQuery();
        } catch (RuntimeException e) {
            return u == null ? "" : u;
        }
    }

    /** A body as readable text: JSON is laid out, a very long text is cut (the whole text is in the api-evidence file). */
    private static String bodyText(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String t = body.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                t = PRETTY.writeValueAsString(PRETTY.readTree(t));
            } catch (IOException | RuntimeException ignored) {
                // not JSON after all: shown as it is
            }
        }
        return t.length() > BODY_LIMIT ? t.substring(0, BODY_LIMIT) + "\n... (shortened: the whole text is in the file of this scenario in the folder api-evidence)" : t;
    }

    /**
     * Every call of the scenario in the order it happened, as one table (number, step, method, endpoint, status, time). A click on a call opens two options:
     * Payload (what the page sent) and Response (what the server answered), each with its headers folded below. Nothing is masked (user's decision).
     */
    private String apiDetailsHtml() {
        StringBuilder sb = new StringBuilder("<p>The calls the page sent to the server while this scenario ran, in the order they happened. Click a call to open its <b>Payload</b> and its <b>Response</b>.</p>");
        sb.append("<div class='kapi'><div class='kapi-row kapi-head'><span>#</span><span>Step</span><span>Method</span><span>Endpoint</span><span>Status</span><span>Time</span></div>");
        int n = 0;
        for (ApiCall c : allCalls) {
            n++;
            String status = c.error != null ? "FAILED" : c.status < 0 ? "no answer" : String.valueOf(c.status);
            String cls = c.error != null || c.status >= 500 ? "bad" : c.status >= 400 || c.status < 0 ? "warn" : "ok";
            String full = c.fullUrl == null ? c.url : c.fullUrl;
            sb.append("<details class='kapi-call'><summary class='kapi-row' title='").append(esc(full)).append("'><span>").append(n).append("</span><span>").append(c.phase).append("</span><span><b>")
                    .append(esc(c.method)).append("</b></span><span class='ep'>").append(esc(endpointOf(c))).append("</span><span class='").append(cls).append("'>").append(esc(status))
                    .append("</span><span>").append(c.durationMs < 0 ? "-" : c.durationMs + " ms").append("</span></summary><div class='kapi-body'>");
            String payload = bodyText(c.requestBody);
            sb.append("<details class='kapi-sub'><summary>Payload</summary><pre>").append(esc(payload == null ? "(no payload: this request sends no body)" : payload)).append("</pre>")
                    .append("<details><summary>Request headers</summary><pre>").append(esc(headers(c.requestHeaders))).append("</pre></details></details>");
            String response = bodyText(c.responseBody);
            sb.append("<details class='kapi-sub'><summary>Response</summary><pre>").append(esc("Status: " + status + (c.error != null ? " (" + c.error + ")" : "") + "\n\n"
                    + (response == null ? "(no response text available)" : response))).append("</pre>")
                    .append("<details><summary>Response headers</summary><pre>").append(esc(headers(c.responseHeaders))).append("</pre></details></details>");
            sb.append("<div class='kc-small'>Sent at ").append(clock(c.startMs)).append(", answered at ").append(clock(c.endMs)).append("<br>").append(esc(full)).append("</div></div></details>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String clock(long ms) {
        return ms <= 0 ? "-" : CLOCK.format(java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault()));
    }

    private static String headers(Map<String, String> h) {
        StringBuilder sb = new StringBuilder();
        h.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
        return sb.length() == 0 ? "(none recorded)" : sb.toString();
    }

    private void writeApiEvidenceFile() {
        try {
            java.nio.file.Path dir = ExecutionInfo.root().resolve("api-evidence");
            java.nio.file.Files.createDirectories(dir);
            List<Map<String, Object>> out = new ArrayList<>();
            for (ApiCall c : allCalls) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("testCase", parentTc);
                m.put("scenario", scenarioId);
                m.put("step", c.phase);
                m.put("method", c.method);
                m.put("url", c.fullUrl == null ? c.url : c.fullUrl);
                m.put("requestHeaders", c.requestHeaders);
                m.put("requestBody", c.requestBody);
                m.put("status", c.status);
                m.put("error", c.error);
                m.put("responseHeaders", c.responseHeaders);
                m.put("responseBody", c.responseBody);
                m.put("requestTime", clock(c.startMs));
                m.put("responseTime", clock(c.endMs));
                m.put("durationMs", c.durationMs);
                out.add(m);
            }
            new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .writeValue(dir.resolve("scenario_" + (scenarioId == null ? String.valueOf(record.id) : parentTc + "_" + scenarioId).replaceAll("[^A-Za-z0-9]+", "_") + ".json").toFile(), out);
        } catch (java.io.IOException | RuntimeException e) {
            Log.warn("The API record of the scenario could not be written: " + e.getMessage());
        }
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 4000 ? s.substring(0, 4000) + "\n... (truncated)" : s;
    }

    private static String duration(Duration d) {
        return String.format("%dm %02ds", d.toMinutes(), d.toSecondsPart());
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
