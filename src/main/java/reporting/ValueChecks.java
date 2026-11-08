package reporting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import utils.SecretMasker;

/**
 * The values checked on ONE page, as a table: where, which value, what was expected (what was entered), what the page shows, OK / WRONG.
 * <p>
 * Why: a page such as the admin client profile holds dozens of values. Reporting it as three separate cases that show the same screenshot hides what is
 * wrong. With one table the report says "FAILED - 2 of 27 values are wrong (in red), the other 25 are correct" and shows every value once.
 * Used through {@link TestReporter#phaseChecks}; the rows are collected by the test, the verdict is derived from them.
 */
public final class ValueChecks {

    /** One checked value. */
    public static final class Row {
        public final String where;
        public final String value;
        public final String expected;
        public final String shown;
        public final boolean ok;

        Row(String where, String value, String expected, String shown, boolean ok) {
            this.where = where;
            this.value = value;
            this.expected = expected;
            this.shown = shown;
            this.ok = ok;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    /** The page must show exactly the expected text (spaces are compared loosely; e-mail values ignore upper / lower case). */
    public ValueChecks eq(String where, String value, String expected, String shown) {
        boolean ok = norm(expected).equals(norm(shown)) || (value.toLowerCase(Locale.ROOT).contains("mail") && norm(expected).equalsIgnoreCase(norm(shown)));
        rows.add(new Row(where, value, show(expected, "(empty)"), show(shown, "(not found)"), ok));
        return this;
    }

    /** The shown text must contain the expected text (e.g. the top-right profile button shows the e-mail somewhere in its text). */
    public ValueChecks contains(String where, String value, String expectedPart, String shownText) {
        boolean ok = shownText != null && expectedPart != null && norm(shownText).toLowerCase(Locale.ROOT).contains(norm(expectedPart).toLowerCase(Locale.ROOT));
        rows.add(new Row(where, value, "contains '" + show(expectedPart, "(empty)") + "'", show(shownText, "(not found)"), ok));
        return this;
    }

    /** Any other check: the test decides, the wording of "expected" and "shown" is given. */
    public ValueChecks check(String where, String value, boolean ok, String expected, String shown) {
        rows.add(new Row(where, value, show(expected, "(empty)"), show(shown, "(not found)"), ok));
        return this;
    }

    /** A step of the check that can throw (open a tab, read a table). */
    @FunctionalInterface
    public interface Step {
        void run() throws Exception;
    }

    /**
     * Runs a step; when it fails (the tab does not open, the content is not readable) that is ONE wrong row and the remaining steps still run - a broken
     * tab must not hide the tabs after it. A lost network connection is not an application result: it is passed on (the case is then reported as SKIP).
     */
    public ValueChecks guard(String where, String value, Step step) throws Exception {
        try {
            step.run();
        } catch (org.testng.SkipException e) {
            throw e;
        } catch (Exception | AssertionError e) {
            if (utils.Connectivity.isNetworkError(e)) {
                throw e;
            }
            String technical = String.valueOf(e.getMessage()).split("\\R")[0];
            utils.Log.warn("Check '" + where + " > " + value + "' could not be done: " + technical);
            // a failed check of the test already holds a sentence; anything else (a button not found, a timeout) is a problem of the run: plain wording only
            String why = e instanceof AssertionError ? utils.PlainText.oneLine(utils.PlainText.assertion(technical), 160)
                    : "could not be done: the page did not show what the test needed";
            rows.add(new Row(where, value, "it works", why, false));
        }
        return this;
    }

    public int total() {
        return rows.size();
    }

    public int failed() {
        return (int) rows.stream().filter(r -> !r.ok).count();
    }

    public int passed() {
        return total() - failed();
    }

    public List<Row> wrong() {
        return rows.stream().filter(r -> !r.ok).toList();
    }

    /** "27 values checked: 25 correct, 2 wrong". */
    public String summary() {
        return total() + " values checked: " + passed() + " correct, " + failed() + " wrong";
    }

    /** One sentence for the report header: which values are wrong, and that the rest is fine. */
    public String failureText() {
        StringBuilder sb = new StringBuilder();
        sb.append(failed()).append(" of ").append(total()).append(" values on this page are wrong (the other ").append(passed())
                .append(" are correct). Wrong: ");
        boolean first = true;
        for (Row r : wrong()) {
            sb.append(first ? "" : "; ").append(r.where).append(" > ").append(r.value).append(": should be \"").append(r.expected)
                    .append("\" but the page shows \"").append(r.shown).append("\"");
            first = false;
        }
        return sb.toString();
    }

    /** The table for the report (HTML), wrong values listed first, then every checked value. */
    public String html() {
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<br><br><b>");
        if (failed() == 0) {
            sb.append("<span style='color:#1b7f3b'>All ").append(total()).append(" checked values are correct.</span></b>");
        } else {
            sb.append("<span style='color:#c62828'>RESULT: FAILED - ").append(failed()).append(" of ").append(total())
                    .append(" values are wrong.</span> <span style='color:#1b7f3b'>The other ").append(passed()).append(" values are correct.</span></b>");
            sb.append("<ul style='margin:4px 0 0 18px'>");
            for (Row r : wrong()) {
                sb.append("<li><b>").append(TestReporter.esc(r.where)).append(" &gt; ").append(TestReporter.esc(r.value)).append("</b>: should be &ldquo;")
                        .append(TestReporter.esc(SecretMasker.mask(r.expected))).append("&rdquo; but the page shows <span style='color:#c62828'><b>&ldquo;")
                        .append(TestReporter.esc(SecretMasker.mask(r.shown))).append("&rdquo;</b></span></li>");
            }
            sb.append("</ul>");
        }
        sb.append("<table border='1' cellpadding='4' style='border-collapse:collapse;font-size:12px;margin-top:8px'>")
                .append("<tr style='background:#eceff1'><th style='white-space:nowrap'>#</th><th>Where</th><th>Value</th><th>Should be</th><th>Shown on the page</th>"
                        + "<th style='white-space:nowrap'>Result</th></tr>");
        int n = 0;
        for (Row r : rows) {
            n++;
            sb.append("<tr").append(r.ok ? "" : " style='background:#fdecea'").append("><td>").append(n).append("</td><td>").append(TestReporter.esc(r.where))
                    .append("</td><td>").append(TestReporter.esc(r.value)).append("</td><td>").append(TestReporter.esc(SecretMasker.mask(r.expected)))
                    .append("</td><td>").append(TestReporter.esc(SecretMasker.mask(r.shown))).append("</td><td style='white-space:nowrap'>")
                    .append(r.ok ? "<span style='color:#1b7f3b'><b>OK</b></span>" : "<span style='color:#c62828'><b>WRONG</b></span>").append("</td></tr>");
        }
        return sb.append("</table>").toString();
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    private static String show(String s, String whenEmpty) {
        if (s == null) {
            return whenEmpty.equals("(not found)") ? "(not found)" : "(empty)";
        }
        String t = s.trim();
        return t.isEmpty() ? "(empty)" : t;
    }
}
