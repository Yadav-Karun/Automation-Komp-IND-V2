package reporting;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Optional: combines the workbooks of several class reports (e.g. the happy-flow folder and the edge-cases folder) into
 * one consolidated workbook stored in reports/, next to the folders, so every screenshot / report / log link keeps working.
 * Each class run already writes its own workbook and jira-bugs/ folder; this tool is only for showing everything together.
 *
 * Usage: java -cp ... reporting.ExcelMergeTool reports/<folder1> reports/<folder2> ...
 * One row per test case id; a case that ran in several executions keeps the result of the LATEST execution given
 * (list the folders oldest first).
 */
public final class ExcelMergeTool {

    private static final DataFormatter FMT = new DataFormatter();

    /**
     * Cases whose expectation was tightened after earlier executions: the plain "failed in N of M" count would mislead,
     * so the wording says what was checked in which execution.
     */
    private static final Map<String, String> REPEAT_NOTES = Map.of(
            "O-06", "Failed in 1 of 1 execution with the field-name expectation (earlier executions only checked that SOME message appears and passed, because the generic toast counts as a message)",
            "O-07", "Failed in 1 of 1 execution with the field-name expectation (earlier executions only checked that SOME message appears and passed, because the generic toast counts as a message)");

    private ExcelMergeTool() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: ExcelMergeTool <reports/runDir> [<reports/runDir> ...]   (oldest first)");
            System.exit(2);
        }
        Map<String, ResultCollector.Case> byId = new LinkedHashMap<>();
        Map<String, List<String>> history = new LinkedHashMap<>();   // case id -> "run:STATUS" for every execution
        List<Path> used = new ArrayList<>();
        for (String a : args) {
            Path run = Paths.get(a);
            Path book = ExecutionInfo.excelOf(run);
            if (book == null) {
                System.err.println("Skipping " + a + ": no *_Results.xlsx workbook in that folder");
                continue;
            }
            used.add(run);
            for (ResultCollector.Case c : read(run, book)) {
                history.computeIfAbsent(c.id, k -> new ArrayList<>()).add(c.status);
                byId.remove(c.id); // later execution replaces the earlier result, keeping the newest at the end
                byId.put(c.id, c);
            }
        }
        for (ResultCollector.Case c : evidenceObservations()) {
            history.computeIfAbsent(c.id, k -> new ArrayList<>()).add(c.status);
            byId.put(c.id, c);
        }
        for (ResultCollector.Case c : byId.values()) {
            if (c.repeat != null) {
                continue; // observations from earlier executions carry their own wording
            }
            if (REPEAT_NOTES.containsKey(c.id) && "FAIL".equals(c.status)) {
                c.repeat = REPEAT_NOTES.get(c.id);
                continue;
            }
            List<String> h = history.get(c.id);
            long failed = h.stream().filter("FAIL"::equals).count();
            c.repeat = "FAIL".equals(c.status)
                    ? "Failed in " + failed + " of " + h.size() + " execution(s) that ran this case"
                    : "Passed in " + (h.size() - failed) + " of " + h.size() + " execution(s) that ran this case";
        }
        List<ResultCollector.Case> cases = new ArrayList<>(byId.values());
        cases.sort((x, y) -> order(x.id).compareTo(order(y.id)));
        Path reportsRoot = Paths.get(args[0]).toAbsolutePath().getParent();
        List<BugReport.Bug> bugs = BugReport.generate(cases, reportsRoot);
        Path out = reportsRoot.resolve("KOMP_IND_Client_Onboarding_Consolidated_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx");
        ExcelReportWriter.writeMerged(cases, out, used, bugs);
        System.out.println("Merged " + cases.size() + " test cases from " + used.size() + " executions -> " + out);
        System.out.println("Jira bugs: " + bugs.size() + " -> " + reportsRoot.resolve("jira-bugs"));
    }

    /**
     * Findings that were observed in EARLIER executions and cannot be re-run without creating a client (e.g. API errors
     * during MSA generation in the happy path). They come from testdata/evidence-observations.json and keep their own
     * evidence: the API summary and screenshot of the run they were seen in.
     */
    private static List<ResultCollector.Case> evidenceObservations() throws IOException {
        Path file = Paths.get("testdata/evidence-observations.json");
        List<ResultCollector.Case> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(file));
        for (com.fasterxml.jackson.databind.JsonNode n : root) {
            ResultCollector.Case c = new ResultCollector.Case();
            c.id = n.path("id").asText();
            c.group = n.path("group").asText();
            c.title = n.path("title").asText();
            c.input = n.path("input").asText();
            c.expected = n.path("expected").asText();
            c.actual = n.path("actual").asText();
            c.status = n.path("status").asText("FAIL");
            c.run = n.path("run").asText();
            c.repeat = n.path("repeat").asText(null);
            c.apiEvidence = n.path("apiEvidence").asText(null);
            c.defects.add(n.path("title").asText() + " | expected: " + c.expected + " | actual: " + c.actual);
            String shot = n.path("screenshot").asText(null);
            c.defectScreenshot = shot;
            ResultCollector.Phase p = new ResultCollector.Phase();
            p.index = 1;
            p.title = c.title;
            p.expected = c.expected;
            p.actual = c.actual;
            p.status = c.status;
            p.screenshot = shot;
            c.phases.add(p);
            out.add(c);
        }
        return out;
    }

    /** Sort key: group letter + number, e.g. R-02 before R-10; keeps happy path first. */
    private static String order(String id) {
        String[] p = id.split("-");
        String num = p.length > 1 ? p[1] : "0";
        try {
            num = String.format("%04d", Integer.parseInt(num));
        } catch (NumberFormatException ignored) {
            // keep text
        }
        return p[0] + "-" + num;
    }

    private static List<ResultCollector.Case> read(Path run, Path book) throws IOException {
        String prefix = run.getFileName() + "/";
        Map<String, ResultCollector.Case> cases = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(book); Workbook wb = new XSSFWorkbook(in)) {
            Sheet tc = wb.getSheet("Test Cases");
            for (int i = 1; tc != null && i <= tc.getLastRowNum(); i++) {
                Row r = tc.getRow(i);
                if (r == null || str(r, 0).isEmpty()) {
                    continue;
                }
                ResultCollector.Case c = new ResultCollector.Case();
                c.id = str(r, 0);
                c.run = run.getFileName().toString();
                c.group = str(r, 1);
                c.title = str(r, 2);
                c.input = str(r, 3);
                c.expected = str(r, 4);
                c.actual = str(r, 5);
                c.status = str(r, 6);
                for (String line : str(r, 7).split("\\R")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (line.contains("| expected:")) {
                        c.defects.add(line);
                    } else {
                        c.failure = c.failure == null ? line : c.failure + "\n" + line;
                    }
                }
                String shot = link(r, 8);
                c.defectScreenshot = shot == null ? null : prefix + shot;
                cases.put(c.id, c);
            }
            Sheet steps = wb.getSheet("Steps");
            for (int i = 1; steps != null && i <= steps.getLastRowNum(); i++) {
                Row r = steps.getRow(i);
                if (r == null) {
                    continue;
                }
                ResultCollector.Case c = cases.get(str(r, 0));
                if (c == null) {
                    continue;
                }
                ResultCollector.Phase p = new ResultCollector.Phase();
                try {
                    p.index = (int) Double.parseDouble(str(r, 1));
                } catch (NumberFormatException e) {
                    p.index = c.phases.size() + 1;
                }
                p.title = str(r, 2);
                p.expected = str(r, 3);
                p.actual = str(r, 4);
                p.status = str(r, 5);
                String shot = link(r, 6);
                p.screenshot = shot == null ? null : prefix + shot;
                c.phases.add(p);
            }
        }
        return new ArrayList<>(cases.values());
    }

    private static String str(Row r, int col) {
        Cell c = r.getCell(col);
        return c == null ? "" : FMT.formatCellValue(c).trim();
    }

    private static String link(Row r, int col) {
        Cell c = r.getCell(col);
        if (c == null || c.getHyperlink() == null) {
            return null;
        }
        return c.getHyperlink().getAddress();
    }
}
