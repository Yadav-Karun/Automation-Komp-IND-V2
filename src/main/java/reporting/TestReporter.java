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
    private final List<String> defectActuals = new ArrayList<>();
    /** The DEFECT screenshot taken during the current phase (the phase result row reuses it). */
    private String phaseDefectShot;
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
        String testName = plain(id + " - " + title);
        ExtentTest t = ExtentManager.get().createTest(testName, plain(description));
        // the report strips spaces from a tag ("E2E profile check" -> "E2Eprofilecheck"): hyphens keep it readable
        t.assignCategory("Client-Onboarding", group == null ? "General" : group.trim().replaceAll("\\s+", "-"));
        TestReporter r = new TestReporter(driver, t, data);
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
                + "Environment: " + esc(ConfigManager.env()) + " (" + esc(ConfigManager.baseUrl()) + ")<br>"
                + "Browser: " + esc(DriverFactory.browserVersion(driver)) + "<br>"
                + "Started: " + TS.format(r.started) + "<br>"
                + esc(clientLine(data)));
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
        node = test.createNode(plain(String.format("Phase %02d - %s", idx, title)));
        Log.info(String.format("Phase %02d START - %s", idx, title));
        NetworkCapture.drain(driver); // discard anything before this phase
        carried.clear();
        int defectsBefore = defects.size();
        phaseDefectShot = null;
        try {
            String actual = action.run();
            List<ApiCall> calls = collectCalls();
            if (defects.size() > defectsBefore) {
                // the phase ran to the end but found a product defect (soft check): the phase result is FAIL, never PASS
                String shot = phaseDefectShot != null ? phaseDefectShot : ScreenshotManager.capture(driver, fileName(idx, "FAIL"), true);
                node.fail(detail(expected, "DEFECT FOUND - " + actual) + apiHtml(idx, title, calls), media(shot));
                results.add(new String[] { String.valueOf(idx), title, "FAIL", shot });
                recordPhase(idx, title, expected, "DEFECT FOUND - " + SecretMasker.mask(actual), "FAIL", shot);
                Log.error(String.format("Phase %02d FAIL (defect) - %s | %s", idx, title, actual));
                return;
            }
            String shot = ScreenshotManager.capture(driver, fileName(idx, "PASS"), false);
            node.pass(detail(expected, actual) + apiHtml(idx, title, calls), media(shot));
            results.add(new String[] { String.valueOf(idx), title, "PASS", shot });
            recordPhase(idx, title, expected, SecretMasker.mask(actual), "PASS", shot);
            Log.info(String.format("Phase %02d PASS - %s | %s", idx, title, actual));
        } catch (Throwable t) {
            if (!(t instanceof org.testng.SkipException) && utils.Connectivity.isNetworkFailure(driver, t)) {
                // a lost connection is not an application result: SKIP with the reason, never FAIL (the next case waits for the network)
                t = new org.testng.SkipException("The network connection to the application was lost during this step ("
                        + utils.Connectivity.describe(t) + "). This is not an application result: run the case again (-Dcases="
                        + record.id + ").");
            }
            if (t instanceof org.testng.SkipException) {
                // a case can skip itself mid-way ("only one page of clients"): that is a SKIP with its reason, not a failure
                List<ApiCall> skipCalls = collectCalls();
                String skipReason = SecretMasker.mask(t.getMessage() == null ? "skipped" : t.getMessage());
                String skipShot = ScreenshotManager.capture(driver, fileName(idx, "SKIP"), false);
                node.skip(detail(expected, "SKIPPED: " + skipReason) + apiHtml(idx, title, skipCalls), media(skipShot));
                results.add(new String[] { String.valueOf(idx), title, "SKIP", skipShot });
                recordPhase(idx, title, expected, "SKIPPED: " + skipReason, "SKIP", skipShot);
                Log.info(String.format("Phase %02d SKIP - %s | %s", idx, title, skipReason));
                throw (org.testng.SkipException) t;
            }
            List<ApiCall> calls = collectCalls();
            String shot = ScreenshotManager.capture(driver, fileName(idx, "FAIL"), true);
            String reason = SecretMasker.mask(t.getMessage() == null ? t.toString() : t.getMessage());
            String console = String.join("<br>", NetworkCapture.severeConsole(driver).stream().map(TestReporter::esc).toList());
            node.fail(detail(expected, "FAILED: " + reason)
                    + "<br><b>Exception:</b><pre>" + esc(SecretMasker.mask(stack(t))) + "</pre>"
                    + (console.isEmpty() ? "" : "<b>Browser console (SEVERE):</b><br>" + console)
                    + apiHtml(idx, title, calls), media(shot));
            results.add(new String[] { String.valueOf(idx), title, "FAIL", shot });
            recordPhase(idx, title, expected, "FAILED: " + reason, "FAIL", shot);
            Log.error(String.format("Phase %02d FAIL - %s | %s", idx, title, reason));
            throw t instanceof Error ? (Error) t : new AssertionError(
                    "Phase " + idx + " (" + title + ") failed: " + reason, t);
        }
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
        String shot = ScreenshotManager.capture(driver,
                fileName(seq, "PASS") .replace("_PASS.png", "_" + (++evidenceCount) + "_" + label + "_PASS.png"), false);
        node.info("Evidence: " + esc(label), media(shot));
    }

    /** Soft check: a genuine product defect that does not block the rest of the journey. */
    public void defect(String title, String expected, String actual) {
        String line = "Phase " + seq + " - " + title + " | expected: " + expected + " | actual: " + actual;
        defects.add(line);
        defectActuals.add(SecretMasker.mask(actual));
        record.defects.add(title + " | expected: " + expected + " | actual: " + SecretMasker.mask(actual));
        String shot = ScreenshotManager.capture(driver, fileName(seq, "DEFECT"), true);
        record.defectScreenshot = shot;
        phaseDefectShot = shot;
        node.fail("<b>DEFECT:</b> " + esc(title) + detail(expected, actual), media(shot));
        Log.error("DEFECT " + line);
    }

    public void note(String message) {
        if (node != null) {
            node.info(esc(message));
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
        sb.append("<b>Overall result: ").append(overall).append("</b><br>")
                .append("Started: ").append(TS.format(started)).append(" | Ended: ").append(TS.format(ended))
                .append(" | Duration: ").append(duration(Duration.between(started, ended))).append("<br>")
                .append(esc(clientLine(data))).append("<br>")
                .append("<table border='1' cellpadding='4'><tr><th>#</th><th>Phase</th><th>Result</th><th>Screenshot</th></tr>");
        for (String[] r : results) {
            sb.append("<tr><td>").append(r[0]).append("</td><td>").append(esc(r[1])).append("</td><td>").append(r[2])
                    .append("</td><td>").append(r[3] == null ? "-" : "<a href='" + r[3] + "'>" + r[3] + "</a>").append("</td></tr>");
        }
        sb.append("</table>");
        if (!defects.isEmpty()) {
            sb.append("<br><b>Defects found (").append(defects.size()).append("):</b><ul>");
            defects.forEach(d -> sb.append("<li>").append(esc(d)).append("</li>"));
            sb.append("</ul>");
        }
        sb.append("<br><b>API validation:</b> NOT CONFIGURED - no documented API contract was supplied, so API calls are ")
                .append("recorded as observations only (method, path, status). UI results above are independent of this.");
        if (!apiObservations.isEmpty()) {
            sb.append("<br><b>API observations (4xx/5xx):</b><ul>");
            apiObservations.forEach(o -> sb.append("<li>").append(esc(o)).append("</li>"));
            sb.append("</ul>");
        }
        Status s = "PASS".equals(overall) ? Status.PASS : "SKIP".equals(overall) ? Status.SKIP : Status.FAIL;
        ExtentTest summary = test.createNode("Execution summary");
        summary.log(s, sb.toString());
        if (failure != null && !"PASS".equals(overall)) {
            summary.log(Status.INFO, (("SKIP".equals(overall)) ? "Skipped: " : "Failure: ")
                    + esc(SecretMasker.mask(String.valueOf(failure.getMessage()))));
        }
        writeNetworkSummary(overall);
        if ("FAIL".equals(overall)) {
            nameTheFailure(failure);
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

    /** "Client: Black Clover | type EOR | login asta@yopmail.com | password ... | temporary password ..." (secrets shown unless masking is on). */
    private static String clientLine(ClientData d) {
        StringBuilder sb = new StringBuilder("Client: ").append(d.legalName).append(" | type ").append(d.clientType)
                .append(" | login e-mail ").append(SecretMasker.maskEmail(d.email));
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
     * So a failed case gets the reason in front of its description line (right under the title): "FAILED because: <what was wrong> | Checked: <what
     * the case checks>". The name itself is left alone (a longer name wraps and crowds the header).
     */
    private void nameTheFailure(Throwable failure) {
        try {
            String why = !defectActuals.isEmpty() ? defectActuals.get(0)
                    : failure != null && failure.getMessage() != null ? SecretMasker.mask(failure.getMessage()) : "see the steps";
            why = why.replaceAll("\\s+", " ").trim();
            if (why.length() > 300) {
                why = why.substring(0, 297) + "...";
            }
            test.getModel().setDescription(plain("FAILED because: " + why + "  |  Checked: " + description));
        } catch (RuntimeException e) {
            Log.warn("Failed case not renamed in the report: " + e.getClass().getSimpleName());
        }
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
        return "<br><b>Expected:</b> " + esc(expected) + "<br><b>Actual:</b> " + esc(SecretMasker.mask(actual));
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
        StringBuilder sb = new StringBuilder("<br><b>API calls observed (not contract-validated):</b><br><small>");
        lines.stream().limit(15).forEach(l -> sb.append(esc(l)).append("<br>"));
        if (lines.size() > 15) {
            sb.append("... +").append(lines.size() - 15).append(" more (see api-evidence/network-summary.json)");
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
