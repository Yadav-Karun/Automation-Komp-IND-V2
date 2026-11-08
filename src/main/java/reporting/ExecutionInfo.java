package reporting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

/**
 * One report per TEST CLASS, one dated folder per run, grouped under the class name:
 *
 *   reports/&lt;ClassName&gt;/&lt;YYYY-MM-DD_hh.mm.ss&gt;/        (hh = 12-hour clock, e.g. 2026-11-07_07.33.21)
 *       &lt;ClassName&gt;.html            the Extent report of that run
 *       screenshots/  logs/  api-evidence/  (test-data.json for the happy flow)
 *
 * An XML suite with two classes therefore produces one run folder under each class, with its own HTML and its own screenshots.
 * A folder is never overwritten (a counter is appended when two runs start in the same second).
 * Classes run one after the other (parallel="none"), so "the current class" is always unambiguous.
 * (The Excel workbook and the Jira bug folders are switched off: report.excel / report.jira, see ExcelReportWriter.)
 */
public final class ExecutionInfo {

    public static final String PROJECT = "KOMP IND Testing";

    /** File names used by executions that were recorded before reports were split per class (the merge tool still reads them). */
    private static final String OLD_EXCEL = "KOMP_IND_Testing_Results.xlsx";

    private static String executionId;
    private static String className;
    private static String testName;
    private static Path root;
    private static LocalDateTime started;

    private ExecutionInfo() {
    }

    /** The module of the client-onboarding classes (the default). */
    public static final String MODULE_CLIENT = "Client-Onboarding";

    private static String module = MODULE_CLIENT;

    /** The module the current class belongs to ("Client-Onboarding", "Project-Management"): the first tag of every case and a line of the report header. */
    public static String module() {
        return module;
    }

    /** True for the client-onboarding module (its reports show the test logins by decision of the user; other modules never print a password). */
    public static boolean isClientModule() {
        return MODULE_CLIENT.equals(module);
    }

    /** Starts the report of one test class. {@code xmlTestName} is the name of the &lt;test&gt; element of the suite. */
    public static synchronized void begin(String cls, String xmlTestName) {
        begin(cls, xmlTestName, MODULE_CLIENT);
    }

    /** Same, for a class of another module (see {@link #module()}). */
    public static synchronized void begin(String cls, String xmlTestName, String moduleName) {
        module = moduleName == null || moduleName.isBlank() ? MODULE_CLIENT : moduleName;
        className = cls;
        testName = xmlTestName == null || xmlTestName.isBlank() ? cls : xmlTestName;
        started = LocalDateTime.now();
        String stamp = started.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_hh.mm.ss")); // hh = 12-hour clock
        Path classFolder = Paths.get("reports", cls);
        String run = stamp;
        Path dir = classFolder.resolve(run);
        for (int n = 2; Files.exists(dir); n++) { // same second, or the same clock time 12 hours apart
            run = stamp + "_" + n;
            dir = classFolder.resolve(run);
        }
        executionId = cls + "/" + run;
        root = dir;
        try {
            Files.createDirectories(screenshots());
            Files.createDirectories(logs());
            Files.createDirectories(apiEvidence());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the report folders under " + root, e);
        }
    }

    /** Folder of the current run relative to reports/, e.g. ClientOnboardingEdgeCases/2026-11-07_06.30.15. */
    public static String id() {
        return executionId;
    }

    public static String className() {
        return className;
    }

    public static String testName() {
        return testName;
    }

    public static LocalDateTime started() {
        return started;
    }

    public static Path root() {
        return root;
    }

    public static Path screenshots() {
        return root.resolve("screenshots");
    }

    public static Path logs() {
        return root.resolve("logs");
    }

    public static Path apiEvidence() {
        return root.resolve("api-evidence");
    }

    /** HTML report file name = the test class name. */
    public static String reportFileName() {
        return className + ".html";
    }

    public static Path reportFile() {
        return root.resolve(reportFileName());
    }

    public static String excelFileName() {
        return className + "_Results.xlsx";
    }

    // ------------------------------------------------------------------ reading existing folders (merge tool)

    /** The Excel workbook inside any report folder: &lt;Class&gt;_Results.xlsx, or the pre-split file name. Null when absent. */
    public static Path excelOf(Path runDir) {
        Path old = runDir.resolve(OLD_EXCEL);
        if (Files.isRegularFile(old)) {
            return old;
        }
        try (Stream<Path> s = Files.list(runDir)) {
            return s.filter(p -> p.getFileName().toString().endsWith("_Results.xlsx")).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Name of the Extent HTML file inside any report folder (the single *.html next to the screenshots folder). */
    public static String htmlOf(Path runDir) {
        try (Stream<Path> s = Files.list(runDir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".html")).findFirst().orElse("(no html report)");
        } catch (IOException e) {
            return "(no html report)";
        }
    }
}
