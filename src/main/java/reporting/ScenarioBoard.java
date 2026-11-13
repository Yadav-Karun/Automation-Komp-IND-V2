package reporting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aventstack.extentreports.Status;

/**
 * The reporting standard for classes of independent scenarios (user, 2026-11-11): every scenario is ONE report entry with its own verdict, evidence, tags and defect
 * references; this board keeps the outcomes of the class and builds the summaries (per test case and for the whole execution).
 * <ul>
 *   <li>{@link Verdict}: PASS (green), FAIL (red: a confirmed application defect), NOT RUN (grey), BLOCKED (orange: a prerequisite is missing), NOT JUDGED (grey: the
 *       application did not reach the point that decides the scenario, or the test itself could not carry it out);</li>
 *   <li>{@link Kind}: an application defect and an automation failure are never the same thing;</li>
 *   <li>defect references: several scenarios can show the SAME defect; it is counted once as a unique defect, the failed scenarios are counted as well.</li>
 * </ul>
 */
public final class ScenarioBoard {

    public enum Verdict {
        PASS("PASS", "#2e7d32", Status.PASS),
        FAIL("FAIL", "#c62828", Status.FAIL),
        NOT_RUN("NOT RUN", "#757575", Status.SKIP),
        BLOCKED("BLOCKED", "#ef6c00", Status.WARNING),
        NOT_JUDGED("NOT JUDGED", "#757575", Status.SKIP);

        public final String label;
        public final String color;
        public final Status extent;

        Verdict(String label, String color, Status extent) {
            this.label = label;
            this.color = color;
            this.extent = extent;
        }

        public String badge() {
            return "<span style='background:" + color + ";color:#fff;padding:1px 8px;border-radius:3px;font-weight:600'>" + label + "</span>";
        }
    }

    public enum Kind {
        NONE("-"),
        APPLICATION_DEFECT("Application defect"),
        AUTOMATION_FAILURE("Automation failure");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public static final class Outcome {
        public String tc;
        /** The number of the scenario inside its test case (1, 2, 3 ... in execution order): what the report shows. */
        public int no;
        public String id;
        public String title;
        public Verdict verdict;
        public Kind kind = Kind.NONE;
        public List<String> defectRefs = new ArrayList<>();
        public List<String> tags = new ArrayList<>();
        public String reason = "";
    }

    public static final class Defect {
        public final String ref;
        public final String severity;
        public final String title;
        public final String description;

        Defect(String ref, String severity, String title, String description) {
            this.ref = ref;
            this.severity = severity;
            this.title = title;
            this.description = description;
        }
    }

    private static final List<Outcome> OUTCOMES = new ArrayList<>();
    private static final Map<String, Defect> CATALOG = new LinkedHashMap<>();

    private ScenarioBoard() {
    }

    private static com.aventstack.extentreports.ExtentTest overallEntry;
    private static final Map<String, String> AUTO_REFS = new LinkedHashMap<>();
    private static final Map<String, Integer> NUMBERS = new LinkedHashMap<>();

    /** The next scenario number of a test case: 1, 2, 3 ... in the order the scenarios start (also the ones that are not run). */
    public static synchronized int nextNumber(String tc) {
        return NUMBERS.merge(tc == null ? "" : tc, 1, Integer::sum);
    }

    /** A new class (= a new report) starts. */
    public static synchronized void reset() {
        OUTCOMES.clear();
        CATALOG.clear();
        AUTO_REFS.clear();
        NUMBERS.clear();
        overallEntry = null;
    }

    /**
     * The first entry of every report: the summary of the whole execution (created at the start so that it stands first in the left panel, filled when the class ends).
     * Every test class gets it from the base test class; nothing to do in a test class.
     */
    public static synchronized void openOverallSummary() {
        if (overallEntry == null) {
            overallEntry = ExtentManager.get().createTest("SUMMARY | All test cases of this run").assignCategory("Summary");
        }
    }

    public static synchronized void closeOverallSummary() {
        if (overallEntry != null && !OUTCOMES.isEmpty()) {
            boolean red = OUTCOMES.stream().anyMatch(o -> o.verdict == Verdict.FAIL);
            overallEntry.log(red ? Status.FAIL : Status.PASS, summaryHtml(null, "All scenarios of this run"));
        }
        overallEntry = null;
    }

    /**
     * A defect reference for a problem that has no reference of its own: the SAME problem sentence always gets the SAME reference (AUTO-001 ...), so scenarios that show the same
     * problem are counted as one unique defect even in a suite that keeps no defect list.
     */
    public static synchronized String autoRef(String headline) {
        String key = headline == null ? "" : headline.toLowerCase().replaceAll("[0-9]+", "#").replaceAll("\\s+", " ").trim();
        String ref = AUTO_REFS.get(key);
        if (ref == null) {
            ref = String.format("AUTO-%03d", AUTO_REFS.size() + 1);
            AUTO_REFS.put(key, ref);
            CATALOG.put(ref, new Defect(ref, "-", headline == null || headline.isBlank() ? "Problem found (no description)" : headline, "Linked automatically: scenarios with the same problem text."));
        }
        return ref;
    }

    public static synchronized void register(Outcome o) {
        OUTCOMES.add(o);
    }

    /** One known application defect (the same defect can be linked from many scenarios). */
    public static synchronized void defect(String ref, String severity, String title, String description) {
        CATALOG.put(ref, new Defect(ref, severity, title, description));
    }

    public static synchronized String describeDefect(String ref) {
        Defect d = CATALOG.get(ref);
        return d == null ? ref : ref + " - " + d.title + " (" + d.severity + ")";
    }

    /** The plain headline of a known defect (the title it was registered with); the reference itself when it is unknown. */
    public static synchronized String defectTitle(String ref) {
        Defect d = CATALOG.get(ref);
        return d == null ? ref : d.title;
    }

    /** High / Medium / Low as registered; "" when the defect has no rating. */
    public static synchronized String defectSeverity(String ref) {
        Defect d = CATALOG.get(ref);
        return d == null || d.severity == null || d.severity.equals("-") ? "" : d.severity;
    }

    public static synchronized List<Outcome> outcomes(String tc) {
        List<Outcome> out = new ArrayList<>();
        for (Outcome o : OUTCOMES) {
            if (tc == null || tc.equals(o.tc)) {
                out.add(o);
            }
        }
        return out;
    }

    /** Counts per verdict, the unique defects and the automation failures of one test case (or of everything when {@code tc} is null), as an HTML table. */
    public static synchronized String summaryHtml(String tc, String heading) {
        List<Outcome> list = outcomes(tc);
        Map<Verdict, Integer> n = new LinkedHashMap<>();
        for (Verdict v : Verdict.values()) {
            n.put(v, 0);
        }
        Map<String, List<String>> refScenarios = new LinkedHashMap<>();
        int unreferenced = 0;
        int automation = 0;
        for (Outcome o : list) {
            n.merge(o.verdict, 1, Integer::sum);
            if (o.kind == Kind.AUTOMATION_FAILURE) {
                automation++;
            }
            if (o.verdict == Verdict.FAIL) {
                if (o.defectRefs.isEmpty()) {
                    unreferenced++;
                }
                for (String r : o.defectRefs) {
                    refScenarios.computeIfAbsent(r, k -> new ArrayList<>()).add(o.no > 0 ? String.valueOf(o.no) : o.id);
                }
            }
        }
        boolean red = n.get(Verdict.FAIL) > 0;
        // the Status and Timestamp columns of the log table are hidden for a summary (CSS, marker class "komp-summary"): the time stands here, at the top right
        StringBuilder sb = new StringBuilder("<div class='komp-summary'><div style='text-align:right;color:#555'>"
                + (red ? Verdict.FAIL : Verdict.PASS).badge() + " &nbsp; " + java.time.LocalTime.now().withNano(0) + "</div><h5>").append(TestReporter.esc(heading)).append("</h5>");
        sb.append("<table border='1' cellpadding='5' style='border-collapse:collapse'><tr><th align='left'>Metric</th><th align='right' style='white-space:nowrap'>Count</th></tr>");
        sb.append(row("Total scenarios", list.size()));
        for (Verdict v : Verdict.values()) {
            sb.append("<tr><td>").append(v.badge()).append(" ").append(label(v)).append("</td><td align='right' style='white-space:nowrap'>").append(n.get(v)).append("</td></tr>");
        }
        sb.append(row("Application defects identified (unique, linked defects counted once)", refScenarios.size() + unreferenced));
        sb.append(row("Automation failures (the test could not carry the scenario out; not an application defect)", automation));
        sb.append("</table>");
        if (tc == null) {
            Map<String, int[]> byParent = new LinkedHashMap<>();
            for (Outcome o : list) {
                int[] c = byParent.computeIfAbsent(o.tc == null ? "-" : o.tc, k -> new int[Verdict.values().length]);
                c[o.verdict.ordinal()]++;
            }
            if (byParent.size() > 1) {
                sb.append("<br><b>Per test case</b><table border='1' cellpadding='5' style='border-collapse:collapse'><tr><th align='left'>Test case</th>");
                for (Verdict v : Verdict.values()) {
                    sb.append("<th style='white-space:nowrap'>").append(v.label).append("</th>");
                }
                sb.append("<th>Total</th></tr>");
                for (Map.Entry<String, int[]> e : byParent.entrySet()) {
                    int total = 0;
                    sb.append("<tr><td>").append(TestReporter.esc(e.getKey())).append("</td>");
                    for (int v : e.getValue()) {
                        sb.append("<td align='right'>").append(v).append("</td>");
                        total += v;
                    }
                    sb.append("<td align='right'>").append(total).append("</td></tr>");
                }
                sb.append("</table>");
            }
        }
        if (!refScenarios.isEmpty()) {
            sb.append("<br><b>Unique application defects and the scenarios that show them</b><table border='1' cellpadding='5' style='border-collapse:collapse'>")
                    .append("<tr><th>Reference</th><th>Severity</th><th>Defect</th><th>Scenario numbers</th></tr>");
            for (Map.Entry<String, List<String>> e : refScenarios.entrySet()) {
                Defect d = CATALOG.get(e.getKey());
                sb.append("<tr><td>").append(TestReporter.esc(e.getKey())).append("</td><td>").append(d == null ? "-" : TestReporter.esc(d.severity)).append("</td><td>")
                        .append(d == null ? "-" : TestReporter.esc(d.title + (d.description.isEmpty() ? "" : " - " + d.description))).append("</td><td>")
                        .append(TestReporter.esc(String.join(", ", e.getValue()))).append(" (").append(e.getValue().size()).append(")</td></tr>");
            }
            sb.append("</table>");
        }
        List<Outcome> notPassed = new ArrayList<>();
        for (Outcome o : list) {
            if (o.verdict != Verdict.PASS) {
                notPassed.add(o);
            }
        }
        if (!notPassed.isEmpty()) {
            sb.append("<br><b>Scenarios that did not pass (").append(notPassed.size()).append(")</b><div class='komp-scroll'><table class='komp-np' border='1' cellpadding='5' style='border-collapse:collapse'>")
                    .append("<tr><th>Test case</th><th>Scenario no. (id)</th><th>Status</th><th>Kind</th><th>Title</th><th>Defect</th></tr>");
            for (Outcome o : notPassed) {
                sb.append("<tr><td>").append(TestReporter.esc(o.tc)).append("</td><td>").append(TestReporter.esc(o.no > 0 ? o.no + " (" + o.id + ")" : o.id)).append("</td><td>").append(o.verdict.badge()).append("</td><td>")
                        .append(TestReporter.esc(o.kind.label)).append("</td><td>").append(TestReporter.esc(o.title)).append("</td><td>")
                        .append(TestReporter.esc(String.join(", ", o.defectRefs))).append("</td></tr>");
            }
            sb.append("</table></div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String row(String what, int count) {
        return "<tr><td>" + TestReporter.esc(what) + "</td><td align='right' style='white-space:nowrap'>" + count + "</td></tr>";
    }

    private static String label(Verdict v) {
        return switch (v) {
            case PASS -> "Scenario executed and the expected behaviour was observed";
            case FAIL -> "Scenario exposed a confirmed application defect";
            case NOT_RUN -> "Scenario was not executed";
            case BLOCKED -> "Execution could not continue because of a missing prerequisite";
            case NOT_JUDGED -> "Not enough evidence to decide (the application did not reach the checked stage, or the test could not carry it out)";
        };
    }
}
