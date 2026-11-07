package reporting;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** In-memory record of every executed test case, used to build the Excel workbook at the end of the execution. */
public final class ResultCollector {

    public static final class Phase {
        public int index;
        public String title;
        public String expected;
        public String actual;
        public String status;
        public String screenshot;
    }

    public static final class Case {
        public String id;
        public String group;
        public String title;
        public String input;
        public String expected;
        public String status;
        public String actual;
        public String failure;
        public String defectScreenshot;
        /** Execution folder name this result came from (used to locate its log / API evidence). */
        public String run;
        /** e.g. "Failed in 3 of 3 executions" - filled by the merge tool. */
        public String repeat;
        /** API evidence file (relative to the reports root) for cases that come from earlier executions, not from a per-case file. */
        public String apiEvidence;
        public LocalDateTime started;
        public LocalDateTime ended;
        public final List<Phase> phases = new ArrayList<>();
        public final List<String> defects = new ArrayList<>();
    }

    private static final List<Case> CASES = Collections.synchronizedList(new ArrayList<>());

    private ResultCollector() {
    }

    public static void add(Case c) {
        CASES.add(c);
    }

    public static List<Case> all() {
        synchronized (CASES) {
            return new ArrayList<>(CASES);
        }
    }

    /** Called when a new test class (= a new report) starts: its Excel workbook only holds its own cases. */
    public static void reset() {
        CASES.clear();
    }
}
