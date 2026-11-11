package utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The ids of every AOR run, kept in {@code testdata/aor-used-ids.txt} (spec section 13: "persist generated Company Employee IDs and Delivery Partner IDs ... to
 * prevent reuse across runs"). Lines are {@code kind | value | run id | date time}; kinds: DP (Delivery Partner Id), EMP (Company Employee Id), NAME (employee
 * name: the personal e-mail address is made from it). A value that is in the file is never generated again. Comment lines start with #.
 * The file also tells how many employees were ever created, so the Aadhaar / PAN pool of the QA owner is used round-robin (a pair is not used twice in a row).
 * Override the file with -Daor.history.file=<path>.
 */
public final class AorRunHistory {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss a");

    private AorRunHistory() {
    }

    private static Path file() {
        return Paths.get(ConfigManager.get("aor.history.file", "testdata/aor-used-ids.txt"));
    }

    private static List<String> lines() {
        try {
            return Files.isRegularFile(file()) ? Files.readAllLines(file(), StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            throw new IllegalStateException("The AOR run history " + file() + " cannot be read: " + e.getMessage(), e);
        }
    }

    /** Every value of this kind that an earlier run used (compared ignoring case). */
    public static Set<String> used(String kind) {
        Set<String> out = new HashSet<>();
        for (String line : lines()) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            String[] p = line.split("\\|", -1);
            if (p.length >= 2 && p[0].trim().equals(kind)) {
                out.add(p[1].trim().toLowerCase());
            }
        }
        return out;
    }

    /** How many employees earlier runs created (lines of kind EMP). */
    public static int employeesEver() {
        return used("EMP").size();
    }

    /** Appends the values of one new employee, right after the run decided to use them (so that a run that stops half-way still blocks them). */
    public static synchronized void record(String runId, String kind, String value) {
        try {
            Path f = file();
            if (f.getParent() != null) {
                Files.createDirectories(f.getParent());
            }
            if (!Files.exists(f)) {
                Files.writeString(f, "# kind | value | run | date time - ids of the AOR runs (DP = Delivery Partner Id, EMP = Company Employee Id, NAME = employee name).\n"
                        + "# A value in this file is never generated again. Delete a line only to free a value.\n", StandardCharsets.UTF_8);
            }
            Files.writeString(f, kind + " | " + value + " | " + runId + " | " + LocalDateTime.now().format(STAMP) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("The AOR run history " + file() + " cannot be written: " + e.getMessage(), e);
        }
    }
}
