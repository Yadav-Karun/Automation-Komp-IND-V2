package reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import utils.ConfigManager;
import utils.SecretMasker;

/**
 * Turns failing test cases into Jira-ready bugs. For every bug it writes a folder
 * reports/jira-bugs/&lt;KEY&gt;/ with bug.md (the full description), the screenshots, the execution-log excerpt of the
 * failing case and its API evidence when recorded; plus one jira-import.csv for all bugs.
 */
public final class BugReport {

    public static final class Bug {
        public BugCatalog.Entry entry;
        public final List<ResultCollector.Case> cases = new ArrayList<>();
        public String folder;                    // relative to the reports root, e.g. jira-bugs/BUG-DOC-FILETYPE
        public final List<String> files = new ArrayList<>();
        public String description;               // full Jira description (markdown)
        public String reproducibility;
    }

    private BugReport() {
    }

    public static List<Bug> generate(List<ResultCollector.Case> allCases, Path reportsRoot) throws IOException {
        Map<String, Bug> bugs = new LinkedHashMap<>();
        for (ResultCollector.Case c : allCases) {
            if (!"FAIL".equals(c.status)) {
                continue;
            }
            BugCatalog.Entry e = BugCatalog.forCase(c);
            Bug b = bugs.computeIfAbsent(e.key, k -> {
                Bug nb = new Bug();
                nb.entry = e;
                return nb;
            });
            b.cases.add(c);
        }
        Path root = reportsRoot.resolve("jira-bugs");
        Files.createDirectories(root);
        StringBuilder csv = new StringBuilder("Summary,Issue Type,Severity,Priority,Component/s,Labels,Environment,Description\n");
        for (Bug b : bugs.values()) {
            b.folder = "jira-bugs/" + b.entry.key;
            Path dir = root.resolve(b.entry.key);
            Files.createDirectories(dir);
            collectEvidence(b, reportsRoot, dir);
            b.description = description(b);
            Files.writeString(dir.resolve("bug.md"), b.description, StandardCharsets.UTF_8);
            b.files.add(0, b.folder + "/bug.md");
            csv.append(csv(b.entry.summary)).append(",Bug,").append(b.entry.severity).append(',').append(b.entry.priority).append(',')
                    .append(csv(b.entry.component)).append(",\"client-onboarding negative-test automated\",")
                    .append(csv(environment(b.cases.get(0), reportsRoot))).append(',').append(csv(b.description)).append('\n');
        }
        Files.writeString(root.resolve("jira-import.csv"), csv.toString(), StandardCharsets.UTF_8);
        return new ArrayList<>(bugs.values());
    }

    // ------------------------------------------------------------------ evidence

    private static void collectEvidence(Bug b, Path reportsRoot, Path dir) throws IOException {
        for (ResultCollector.Case c : b.cases) {
            // screenshots: the failing/defect one first, then every step screenshot of the case
            List<String> shots = new ArrayList<>();
            if (c.defectScreenshot != null) {
                shots.add(c.defectScreenshot);
            }
            for (ResultCollector.Phase p : c.phases) {
                if (p.screenshot != null && !shots.contains(p.screenshot)) {
                    shots.add(p.screenshot);
                }
            }
            for (String rel : shots) {
                Path src = reportsRoot.resolve(rel);
                if (Files.isRegularFile(src)) {
                    String name = c.id + "_" + src.getFileName();
                    Files.copy(src, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    b.files.add(b.folder + "/" + name);
                }
            }
            Path run = runDir(reportsRoot, c.run);
            if (run != null) {
                String excerpt = logExcerpt(run.resolve("logs/execution.log"), c);
                if (!excerpt.isEmpty()) {
                    Files.writeString(dir.resolve(c.id + "_execution-log-excerpt.txt"), excerpt, StandardCharsets.UTF_8);
                    b.files.add(b.folder + "/" + c.id + "_execution-log-excerpt.txt");
                }
                Path net = c.apiEvidence != null ? reportsRoot.resolve(c.apiEvidence) : run.resolve("api-evidence/network-" + c.id + ".json");
                if (Files.isRegularFile(net)) {
                    Files.copy(net, dir.resolve(c.id + "_api-evidence.json"), StandardCopyOption.REPLACE_EXISTING);
                    b.files.add(b.folder + "/" + c.id + "_api-evidence.json");
                }
            }
        }
    }

    /** The log lines of one test case: from its 'Test started' line to its 'Test finished' line. */
    private static String logExcerpt(Path log, ResultCollector.Case c) {
        if (!Files.isRegularFile(log)) {
            return "";
        }
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            boolean in = false;
            for (String line : lines) {
                if (line.contains("Test started: " + c.id + " - ") || (c.id.equals("TC-E2E-01") && line.contains("Test started: "))) {
                    in = true;
                }
                if (in) {
                    sb.append(SecretMasker.mask(line)).append('\n');
                    if (line.contains("Test finished:")) {
                        break;
                    }
                }
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Folder of the execution a case came from. Merged mode: reportsRoot is reports/ and the run is a sub-folder.
     * Per-class mode: reportsRoot IS the run folder (reports/&lt;Class&gt;_&lt;ts&gt;/), named like the case's run.
     */
    private static Path runDir(Path reportsRoot, String run) {
        if (run == null || run.isEmpty()) {
            return null;
        }
        Path sub = reportsRoot.resolve(run);
        if (Files.isDirectory(sub)) {
            return sub;
        }
        if (run.equals(ExecutionInfo.id())) {
            return reportsRoot; // per-run mode: reportsRoot IS the run folder reports/<Class>/<date>
        }
        Path name = reportsRoot.toAbsolutePath().normalize().getFileName();
        return name != null && name.toString().equals(run) ? reportsRoot : null;
    }

    private static String browser(Path reportsRoot, ResultCollector.Case c) {
        Path runFolder = runDir(reportsRoot, c.run);
        if (runFolder != null) {
            Path log = runFolder.resolve("logs/execution.log");
            try {
                if (Files.isRegularFile(log)) {
                    for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                        int i = line.indexOf("Browser: ");
                        if (i >= 0) {
                            return line.substring(i + "Browser: ".length());
                        }
                    }
                }
            } catch (IOException ignored) {
                // fall through
            }
        }
        return "Google Chrome 156 (Windows 10) - exact version not recorded in this execution";
    }

    private static String environment(ResultCollector.Case c, Path reportsRoot) {
        return "Sandbox " + safeBase() + " | " + browser(reportsRoot, c) + " | execution " + c.run;
    }

    private static String safeBase() {
        try {
            return ConfigManager.baseUrl();
        } catch (RuntimeException e) {
            return "https://sandbox.kompglobal.com";
        }
    }

    // ------------------------------------------------------------------ description

    private static String description(Bug b) {
        ResultCollector.Case first = b.cases.get(0);
        BugCatalog.Entry e = b.entry;
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(e.key).append(": ").append(e.summary).append("\n\n");
        sb.append("**Severity (suggested):** ").append(e.severity).append("  |  **Priority (suggested):** ").append(e.priority)
                .append("  |  **Component:** ").append(e.component).append("\n\n");
        sb.append("**Environment:** Sandbox ").append(safeBase()).append("; client onboarding (admin console + client portal)\n");
        sb.append("**Browser / OS:** ").append(first.run == null ? "n/a" : "see execution log of " + first.run).append(" (Selenium-driven Google Chrome on Windows 10)\n");
        sb.append("**Found by:** automated test(s) ");
        for (int i = 0; i < b.cases.size(); i++) {
            sb.append(i > 0 ? ", " : "").append(b.cases.get(i).id);
        }
        sb.append("\n**Reproducibility:** ").append(b.cases.get(0).repeat == null ? "see Test Cases sheet" : b.cases.get(0).repeat).append("\n\n");

        sb.append("## Preconditions\n");
        sb.append("- Access to the sandbox ").append(safeBase()).append(" (admin / EOR console account for the admin-side bugs; a client account for the portal bugs).\n");
        sb.append("- Every e-mail address used is an @yopmail.com address (inbox readable at https://yopmail.com).\n");
        sb.append("- Test client names look like 'KOMP IND Regression Client <timestamp>' (happy flow) or 'KOMP IND Edge Case Client <timestamp>' (edge cases).\n\n");

        sb.append("## Steps to reproduce\n");
        sb.append("Test case ").append(first.id).append(" - ").append(first.title).append(":\n\n");
        sb.append(ReproTemplates.steps(first)).append("\n\n");
        if (b.cases.size() > 1) {
            sb.append("Same steps, different input (variants):\n\n| TC | Input | Observed |\n|---|---|---|\n");
            for (ResultCollector.Case c : b.cases) {
                sb.append("| ").append(c.id).append(" | ").append(cell(c.input)).append(" | ").append(cell(shorten(c.failure != null ? c.failure
                        : (c.defects.isEmpty() ? c.actual : String.join("; ", c.defects)), 300))).append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("## Expected result\n").append(first.expected).append("\n\n");
        sb.append("## Actual result\n");
        for (ResultCollector.Case c : b.cases) {
            sb.append("- **").append(c.id).append("** (").append(c.title).append("): ")
                    .append(shorten(c.failure != null ? c.failure : (c.defects.isEmpty() ? c.actual : String.join("; ", c.defects)), 600))
                    .append("\n");
        }
        sb.append("\n## Test data used\n");
        for (ResultCollector.Case c : b.cases) {
            sb.append("- ").append(c.id).append(": ").append(c.input).append("\n");
        }
        sb.append("\n## Evidence (attach these files to the Jira issue)\n");
        sb.append("- Folder: reports/").append("jira-bugs/").append(e.key).append("/ (screenshots, execution-log excerpt, API evidence where recorded)\n");
        for (ResultCollector.Case c : b.cases) {
            sb.append("- ").append(c.id).append(": run folder reports/").append(c.run).append("/ -> Extent HTML report, logs/execution.log, screenshots/\n");
        }
        sb.append("\n## Notes\n").append(e.note.isEmpty() ? "-" : e.note).append("\n\n");
        sb.append("## How to re-run the automated case(s)\n");
        for (ResultCollector.Case c : b.cases) {
            sb.append("- ").append(c.id).append(": `").append(TcCatalog.runCommand(c)).append("`\n");
        }
        return sb.toString();
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "/").replace("\n", " ");
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\n", " ").trim();
        return t.length() > max ? t.substring(0, max) + "..." : t;
    }

    private static String csv(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }
}
