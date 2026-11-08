package reporting;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    private final WebDriver driver;
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
                node.fail(detail(expected, "PROBLEM FOUND - " + actual) + phaseHtml + technical(apiHtml(idx, title, calls)), media(shot));
                results.add(new String[] { String.valueOf(idx), title, "FAIL", shot });
                recordPhase(idx, title, expected, "PROBLEM FOUND - " + SecretMasker.mask(actual), "FAIL", shot);
                Log.error(String.format("Phase %02d FAIL (defect) - %s | %s", idx, title, actual));
                return;
            }
            String shot = ScreenshotManager.capture(driver, fileName(idx, "PASS"), false);
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
        all.addAll(NetworkCapture.drain(driver));
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
        LocalDateTime ended = LocalDateTime.now();
        StringBuilder sb = new StringBuilder();
        sb.append("<b>Result of this test: ").append(resultWord(overall)).append("</b><br>")
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
        Status s = "PASS".equals(overall) ? Status.PASS : "SKIP".equals(overall) ? Status.SKIP : Status.FAIL;
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
            test.getModel().setDescription(plain("NOT RUN because: " + why + "  |  What was checked: " + description));
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
            om.writeValue(Files.newOutputStream(ExecutionInfo.apiEvidence().resolve("network-" + record.id + ".json")), doc);
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
