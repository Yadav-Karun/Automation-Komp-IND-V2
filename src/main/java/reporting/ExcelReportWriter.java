package reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import utils.ConfigManager;
import utils.Log;

/**
 * Builds reports/&lt;executionId&gt;/KOMP_IND_Testing_Results.xlsx: summary, test cases, steps, defects and an index of
 * every artifact (HTML report, log, network summary, screenshots) with relative hyperlinks, so the workbook can be
 * shown together with the report folder. No secrets: all text passed through SecretMasker before it is collected.
 */
public final class ExcelReportWriter {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Workbook wb = new XSSFWorkbook();
    private final CellStyle header;
    private final CellStyle wrap;
    private final CellStyle pass;
    private final CellStyle fail;
    private final CellStyle skip;
    private final CellStyle link;
    private final CellStyle title;

    private ExcelReportWriter() {
        header = style(true, IndexedColors.DARK_BLUE, IndexedColors.WHITE);
        wrap = style(false, null, null);
        pass = style(true, IndexedColors.LIGHT_GREEN, IndexedColors.BLACK);
        fail = style(true, IndexedColors.CORAL, IndexedColors.BLACK);
        skip = style(true, IndexedColors.LIGHT_YELLOW, IndexedColors.BLACK);
        link = style(false, null, IndexedColors.BLUE);
        title = style(true, null, null);
        Font big = wb.createFont();
        big.setBold(true);
        big.setFontHeightInPoints((short) 14);
        title.setFont(big);
    }

    /** Merged workbook: several executions in one file. Links are relative to the workbook (stored next to the run folders). */
    private List<Path> mergedRuns;

    /** Writes a workbook for the given cases (taken from several executions) at {@code out}; run folders sit beside it. */
    private List<BugReport.Bug> bugs;

    public static void writeMerged(List<ResultCollector.Case> cases, Path out, List<Path> runDirs, List<BugReport.Bug> bugs)
            throws IOException {
        try (OutputStream os = Files.newOutputStream(out)) {
            ExcelReportWriter w = new ExcelReportWriter();
            w.mergedRuns = runDirs;
            w.bugs = bugs;
            w.summary(cases);
            w.testCases(cases);
            w.jiraBugs();
            w.catalog(cases);
            w.steps(cases);
            w.defects(cases);
            w.artifacts();
            w.wb.write(os);
            w.wb.close();
        }
    }

    /** The Excel workbook is switched OFF by default (-Dreport.excel=true brings it back). */
    public static boolean excelEnabled() {
        return ConfigManager.getBool("report.excel", false);
    }

    /** The Jira-ready bug folders are switched OFF by default (-Dreport.jira=true brings them back). */
    public static boolean jiraEnabled() {
        return ConfigManager.getBool("report.jira", false);
    }

    /**
     * Writes the optional outputs of the CURRENT test class into its run folder: the Excel workbook (report.excel) and the Jira-ready
     * bug folders (report.jira). Both are OFF by default: only the HTML report, screenshots, logs and API evidence are produced.
     * Never throws: a reporting problem must not turn a test result into an error.
     */
    public static void write() {
        List<ResultCollector.Case> cases = ResultCollector.all();
        if (cases.isEmpty() || ExecutionInfo.root() == null || (!excelEnabled() && !jiraEnabled())) {
            return;
        }
        List<BugReport.Bug> bugs = null;
        if (jiraEnabled()) {
            try {
                bugs = BugReport.generate(cases, ExecutionInfo.root());
            } catch (IOException | RuntimeException e) {
                Log.warn("Jira bug folders not written: " + e);
            }
        }
        if (!excelEnabled()) {
            return;
        }
        Path out = ExecutionInfo.root().resolve(ExecutionInfo.excelFileName());
        try (OutputStream os = Files.newOutputStream(out)) {
            ExcelReportWriter w = new ExcelReportWriter();
            w.bugs = bugs;
            w.summary(cases);
            w.testCases(cases);
            w.jiraBugs();
            w.catalog(cases);
            w.steps(cases);
            w.defects(cases);
            w.artifacts();
            w.wb.write(os);
            w.wb.close();
            Log.info("Excel report: " + out.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            Log.warn("Excel report not written: " + e);
        }
    }

    // ------------------------------------------------------------------ sheets

    private void summary(List<ResultCollector.Case> cases) {
        Sheet s = wb.createSheet("Summary");
        int r = 0;
        text(s.createRow(r++), 0, "KOMP IND Testing - client onboarding test results", title);
        r++;
        if (mergedRuns != null) {
            r = kv(s, r, "Executions merged", mergedRuns.stream().map(p -> p.getFileName().toString())
                    .collect(java.util.stream.Collectors.joining(", ")));
            r = kv(s, r, "Merge rule", "One row per test case id; when a case ran in several executions the latest execution wins.");
        } else {
            r = kv(s, r, "Test class", ExecutionInfo.className());
            r = kv(s, r, "Suite (XML test)", ExecutionInfo.testName());
            r = kv(s, r, "Report folder", ExecutionInfo.id());
            r = kv(s, r, "Started", ExecutionInfo.started().format(TS));
        }
        r = kv(s, r, "Environment", ConfigManager.env() + " (" + ConfigManager.baseUrl() + ")");
        r = kv(s, r, "Generated", java.time.LocalDateTime.now().format(TS));
        r = kv(s, r, "Total test cases", String.valueOf(cases.size()));
        r = kv(s, r, "Passed", String.valueOf(count(cases, "PASS")));
        r = kv(s, r, "Failed (defects found)", String.valueOf(count(cases, "FAIL")));
        r = kv(s, r, "Skipped", String.valueOf(count(cases, "SKIP")));
        r = kv(s, r, "Note", "FAIL on a negative case = the application accepted input it should reject, or gave no validation message. "
                + "API validation: NOT CONFIGURED (no API contract supplied).");
        r++;

        Row h = s.createRow(r++);
        String[] cols = { "Group", "Total", "Passed", "Failed", "Skipped" };
        for (int i = 0; i < cols.length; i++) {
            text(h, i, cols[i], header);
        }
        Map<String, int[]> byGroup = new LinkedHashMap<>();
        for (ResultCollector.Case c : cases) {
            int[] a = byGroup.computeIfAbsent(c.group, k -> new int[4]);
            a[0]++;
            a["PASS".equals(c.status) ? 1 : "SKIP".equals(c.status) ? 3 : 2]++;
        }
        for (Map.Entry<String, int[]> e : byGroup.entrySet()) {
            Row row = s.createRow(r++);
            text(row, 0, e.getKey(), wrap);
            for (int i = 0; i < 4; i++) {
                Cell c = row.createCell(i + 1);
                c.setCellValue(e.getValue()[i]);
                c.setCellStyle(wrap);
            }
        }
        r++;
        if (mergedRuns != null) {
            for (Path run : mergedRuns) {
                Row l = s.createRow(r++);
                text(l, 0, "Extent HTML report - " + run.getFileName(), wrap);
                String rel = run.getFileName() + "/" + ExecutionInfo.htmlOf(run);
                link(l.createCell(1), rel, rel);
            }
        } else {
            Row l = s.createRow(r++);
            text(l, 0, "Open the Extent HTML report", wrap);
            link(l.createCell(1), ExecutionInfo.reportFileName(), ExecutionInfo.reportFileName());
            if (bugs != null && !bugs.isEmpty()) {
                Row b = s.createRow(r);
                text(b, 0, "Jira-ready bug folders (" + bugs.size() + ")", wrap);
                link(b.createCell(1), "jira-bugs/jira-import.csv", "jira-bugs/ (bug.md, screenshots, log excerpt, API evidence) + jira-import.csv");
            }
        }
        s.setColumnWidth(0, 9000);
        s.setColumnWidth(1, 12000);
        for (int i = 2; i < 5; i++) {
            s.setColumnWidth(i, 3500);
        }
    }

    private void testCases(List<ResultCollector.Case> cases) {
        Sheet s = wb.createSheet("Test Cases");
        String[] cols = { "TC ID", "Group", "Test case", "Input / data used", "Expected result", "Actual result", "Status",
                "Defect(s) found", "Evidence (screenshot)", "Duration", "Test procedure (manual steps)" };
        headerRow(s, cols);
        int r = 1;
        for (ResultCollector.Case c : cases) {
            Row row = s.createRow(r++);
            text(row, 0, c.id, wrap);
            text(row, 1, c.group, wrap);
            text(row, 2, c.title, wrap);
            text(row, 3, c.input, wrap);
            text(row, 4, c.expected, wrap);
            text(row, 5, c.actual == null ? "" : c.actual, wrap);
            text(row, 6, c.status, statusStyle(c.status));
            text(row, 7, String.join("\n", c.defects) + (c.failure != null && c.defects.isEmpty() && !"PASS".equals(c.status)
                    ? c.failure : ""), wrap);
            String shot = evidence(c);
            Cell ev = row.createCell(8);
            if (shot != null) {
                link(ev, shot, shot);
            }
            text(row, 9, c.started == null || c.ended == null ? ""
                    : Duration.between(c.started, c.ended).toSeconds() + " s", wrap);
            text(row, 10, ReproTemplates.steps(c), wrap);
        }
        widths(s, 11, 18, 40, 42, 48, 60, 10, 60, 34, 10, 90);
        s.createFreezePane(0, 1);
        s.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(1, r - 1), 0, cols.length - 1));
    }

    /** One row per Jira bug (failing cases with the same root cause are grouped), with the full description and evidence links. */
    private void jiraBugs() {
        if (bugs == null) {
            return;
        }
        Sheet s = wb.createSheet("Jira Bugs");
        headerRow(s, new String[] { "Bug key", "Summary", "Severity (suggested)", "Priority (suggested)", "Component", "Related TCs",
                "Reproducibility", "Steps to reproduce", "Expected result", "Actual result", "Test data", "Evidence folder", "Evidence files" });
        int r = 1;
        for (BugReport.Bug b : bugs) {
            ResultCollector.Case first = b.cases.get(0);
            Row row = s.createRow(r++);
            text(row, 0, b.entry.key, wrap);
            text(row, 1, b.entry.summary, wrap);
            text(row, 2, b.entry.severity, wrap);
            text(row, 3, b.entry.priority, wrap);
            text(row, 4, b.entry.component, wrap);
            StringBuilder tcs = new StringBuilder();
            StringBuilder actual = new StringBuilder();
            StringBuilder data = new StringBuilder();
            for (ResultCollector.Case c : b.cases) {
                tcs.append(tcs.length() > 0 ? ", " : "").append(c.id);
                actual.append(c.id).append(": ").append(c.failure != null ? c.failure
                        : (c.defects.isEmpty() ? c.actual : String.join("; ", c.defects))).append("\n");
                data.append(c.id).append(": ").append(c.input).append("\n");
            }
            text(row, 5, tcs.toString(), wrap);
            text(row, 6, first.repeat == null ? "" : first.repeat, wrap);
            text(row, 7, ReproTemplates.steps(first), wrap);
            text(row, 8, first.expected, wrap);
            text(row, 9, actual.toString(), wrap);
            text(row, 10, data.toString(), wrap);
            link(row.createCell(11), b.folder + "/bug.md", b.folder);
            StringBuilder files = new StringBuilder();
            for (String f : b.files) {
                files.append(f).append("\n");
            }
            text(row, 12, files.toString(), wrap);
        }
        widths(s, 22, 60, 12, 12, 28, 18, 24, 90, 45, 70, 50, 28, 60);
        s.createFreezePane(0, 1);
    }

    /** Every test case with the suite / class that holds it and the command to re-run it alone. */
    private void catalog(List<ResultCollector.Case> cases) {
        Sheet s = wb.createSheet("TC Catalog");
        headerRow(s, new String[] { "TC ID", "Group", "Test case", "Input / data", "Expected result", "Suite file", "Test class",
                "Command to re-run this case", "Creates a client?", "Last result" });
        int r = 1;
        for (ResultCollector.Case c : cases) {
            Row row = s.createRow(r++);
            text(row, 0, c.id, wrap);
            text(row, 1, c.group, wrap);
            text(row, 2, c.title, wrap);
            text(row, 3, c.input, wrap);
            text(row, 4, c.expected, wrap);
            text(row, 5, TcCatalog.suite(c), wrap);
            text(row, 6, TcCatalog.testClass(c), wrap);
            text(row, 7, TcCatalog.runCommand(c), wrap);
            text(row, 8, TcCatalog.createsClient(c), wrap);
            text(row, 9, c.status, statusStyle(c.status));
        }
        widths(s, 11, 22, 42, 46, 50, 30, 34, 80, 28, 10);
        s.createFreezePane(0, 1);
        s.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(1, r - 1), 0, 9));
    }

    private void steps(List<ResultCollector.Case> cases) {
        Sheet s = wb.createSheet("Steps");
        headerRow(s, new String[] { "TC ID", "Step #", "Step", "Expected", "Actual", "Status", "Screenshot" });
        int r = 1;
        for (ResultCollector.Case c : cases) {
            for (ResultCollector.Phase p : c.phases) {
                Row row = s.createRow(r++);
                text(row, 0, c.id, wrap);
                Cell n = row.createCell(1);
                n.setCellValue(p.index);
                n.setCellStyle(wrap);
                text(row, 2, p.title, wrap);
                text(row, 3, p.expected, wrap);
                text(row, 4, p.actual, wrap);
                text(row, 5, p.status, statusStyle(p.status));
                if (p.screenshot != null) {
                    link(row.createCell(6), p.screenshot, p.screenshot);
                }
            }
        }
        widths(s, 11, 7, 38, 50, 70, 10, 36);
        s.createFreezePane(0, 1);
    }

    private void defects(List<ResultCollector.Case> cases) {
        Sheet s = wb.createSheet("Defects");
        headerRow(s, new String[] { "#", "TC ID", "Group", "Test case", "Defect / failure", "Evidence (screenshot)" });
        int r = 1;
        int n = 1;
        for (ResultCollector.Case c : cases) {
            if (!"FAIL".equals(c.status)) {
                continue;
            }
            List<String> items = new java.util.ArrayList<>(c.defects);
            if (items.isEmpty() && c.failure != null) {
                items.add(c.failure);
            }
            for (String d : items) {
                Row row = s.createRow(r++);
                Cell num = row.createCell(0);
                num.setCellValue(n++);
                num.setCellStyle(wrap);
                text(row, 1, c.id, wrap);
                text(row, 2, c.group, wrap);
                text(row, 3, c.title, wrap);
                text(row, 4, d, wrap);
                String shot = evidence(c);
                if (shot != null) {
                    link(row.createCell(5), shot, shot);
                }
            }
        }
        widths(s, 5, 11, 18, 40, 90, 34);
        s.createFreezePane(0, 1);
    }

    private void artifacts() {
        Sheet s = wb.createSheet("Artifacts");
        headerRow(s, new String[] { "Artifact", "Type", "Link (relative to this workbook)" });
        int r = 1;
        List<Path> runs = mergedRuns != null ? mergedRuns : List.of(ExecutionInfo.root());
        for (Path run : runs) {
            String prefix = mergedRuns != null ? run.getFileName() + "/" : "";
            String tag = mergedRuns != null ? " [" + run.getFileName() + "]" : "";
            r = artifactRow(s, r, "Extent HTML report" + tag, "report", prefix + ExecutionInfo.htmlOf(run));
            r = artifactRow(s, r, "Execution log" + tag, "log", prefix + "logs/execution.log");
            r = artifactRow(s, r, "Network summary (method, path, status only)" + tag, "api evidence",
                    prefix + "api-evidence/network-summary.json");
            try (Stream<Path> files = Files.list(run.resolve("screenshots"))) {
                for (Path p : (Iterable<Path>) files.sorted()::iterator) {
                    r = artifactRow(s, r, p.getFileName().toString() + tag, "screenshot", prefix + "screenshots/" + p.getFileName());
                }
            } catch (IOException e) {
                Log.warn("Could not list screenshots for the Artifacts sheet: " + e.getMessage());
            }
        }
        widths(s, 60, 14, 70);
        s.createFreezePane(0, 1);
    }

    // ------------------------------------------------------------------ helpers

    private int artifactRow(Sheet s, int r, String name, String type, String rel) {
        Row row = s.createRow(r);
        text(row, 0, name, wrap);
        text(row, 1, type, wrap);
        link(row.createCell(2), rel, rel);
        return r + 1;
    }

    private static String evidence(ResultCollector.Case c) {
        if (c.defectScreenshot != null) {
            return c.defectScreenshot;
        }
        for (ResultCollector.Phase p : c.phases) {
            if ("FAIL".equals(p.status) && p.screenshot != null) {
                return p.screenshot;
            }
        }
        return c.phases.isEmpty() ? null : c.phases.get(c.phases.size() - 1).screenshot;
    }

    private static int count(List<ResultCollector.Case> cases, String status) {
        return (int) cases.stream().filter(c -> status.equals(c.status)).count();
    }

    private int kv(Sheet s, int r, String k, String v) {
        Row row = s.createRow(r);
        text(row, 0, k, header);
        text(row, 1, v, wrap);
        return r + 1;
    }

    private CellStyle statusStyle(String status) {
        return "PASS".equals(status) ? pass : "SKIP".equals(status) ? skip : fail;
    }

    private void headerRow(Sheet s, String[] cols) {
        Row h = s.createRow(0);
        for (int i = 0; i < cols.length; i++) {
            text(h, i, cols[i], header);
        }
    }

    private void widths(Sheet s, int... chars) {
        for (int i = 0; i < chars.length; i++) {
            s.setColumnWidth(i, Math.min(chars[i], 100) * 256);
        }
    }

    private void text(Row row, int col, String v, CellStyle style) {
        Cell c = row.createCell(col);
        String t = v == null ? "" : v;
        c.setCellValue(t.length() > 32000 ? t.substring(0, 32000) : t);
        c.setCellStyle(style);
    }

    private void link(Cell cell, String address, String label) {
        cell.setCellValue(label);
        org.apache.poi.ss.usermodel.Hyperlink h = wb.getCreationHelper().createHyperlink(HyperlinkType.FILE);
        h.setAddress(address.replace(" ", "%20"));
        cell.setHyperlink(h);
        cell.setCellStyle(link);
    }

    private CellStyle style(boolean bold, IndexedColors fill, IndexedColors fontColor) {
        CellStyle st = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(bold);
        if (fontColor != null) {
            f.setColor(fontColor.getIndex());
        }
        st.setFont(f);
        st.setWrapText(true);
        st.setVerticalAlignment(VerticalAlignment.TOP);
        st.setAlignment(HorizontalAlignment.LEFT);
        st.setBorderBottom(BorderStyle.THIN);
        st.setBorderTop(BorderStyle.THIN);
        st.setBorderLeft(BorderStyle.THIN);
        st.setBorderRight(BorderStyle.THIN);
        if (fill != null) {
            st.setFillForegroundColor(fill.getIndex());
            st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return st;
    }
}
