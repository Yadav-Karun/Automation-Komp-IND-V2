package utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import model.AorEmployee;

/**
 * The employees that the AOR edge and negative cases have created, over ALL runs (user's rule, 2026-11-11: only 5 employees may be onboarded for them, and employees cannot
 * be deleted by automation). One line per employee in {@code testdata/aor-edge-employees.txt}:
 * {@code date time | run id | kind | delivery partner id | company employee id | name}; kind is "base" (valid, used for the duplicate and Payee Sheet cases) or "accepted"
 * (an employee the application wrongly accepted). Comment lines start with #. Delete a line only when that employee really was removed on the sandbox.
 */
public final class AorEdgeRegistry {

    private AorEdgeRegistry() {
    }

    private static Path file() {
        return Paths.get(ConfigManager.get("aor.edge.registry", "testdata/aor-edge-employees.txt"));
    }

    public static synchronized List<String[]> entries() {
        List<String[]> out = new ArrayList<>();
        try {
            if (Files.exists(file())) {
                for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                    if (!line.isBlank() && !line.startsWith("#")) {
                        String[] p = line.split("\\|");
                        for (int i = 0; i < p.length; i++) {
                            p[i] = p[i].trim();
                        }
                        if (p.length >= 6) {
                            out.add(p);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("The register of edge-case employees " + file() + " cannot be read: " + e.getMessage(), e);
        }
        return out;
    }

    public static int count() {
        return entries().size();
    }

    /** The delivery partner ids of the valid base employees. */
    public static List<String> baseIds() {
        List<String> ids = new ArrayList<>();
        for (String[] e : entries()) {
            if ("base".equals(e[2])) {
                ids.add(e[3]);
            }
        }
        return ids;
    }

    public static synchronized void record(String runId, String kind, AorEmployee e) {
        try {
            Files.createDirectories(file().toAbsolutePath().getParent());
            if (!Files.exists(file())) {
                Files.writeString(file(), "# AOR edge-case employees (all runs). date time | run id | kind | delivery partner id | company employee id | name\n", StandardCharsets.UTF_8);
            }
            Files.writeString(file(), LocalDateTime.now().withNano(0) + " | " + runId + " | " + kind + " | " + e.deliveryPartnerId + " | " + e.companyEmployeeId + " | " + e.name + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new IllegalStateException("The register of edge-case employees " + file() + " cannot be written: " + ex.getMessage(), ex);
        }
    }
}
