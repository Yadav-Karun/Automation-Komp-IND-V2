package utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;

/**
 * Files the application lets the browser download (AOR Bank Sheet, DA Invoices, payroll report). The browser is told to save them into one folder
 * (default: the Downloads folder of the user, subfolder AOR; -Daor.download.dir=<path>), a file is waited for until the browser has finished it, and it is
 * renamed with the run id so that an earlier run's file is never overwritten ("run-specific filename").
 */
public final class Downloads {

    private Downloads() {
    }

    /** {@code <user home>/Downloads/AOR}, or -Daor.download.dir. Created when it does not exist. */
    public static Path folder() {
        Path dir = Paths.get(ConfigManager.get("aor.download.dir", Paths.get(System.getProperty("user.home"), "Downloads", "AOR").toString()));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("The download folder " + dir + " cannot be created: " + e.getMessage(), e);
        }
        return dir;
    }

    /** Tells the browser to save every download into {@code dir} without asking. */
    public static void allowInto(WebDriver driver, Path dir) {
        ChromiumDriver chrome = (ChromiumDriver) driver;
        try {
            chrome.executeCdpCommand("Browser.setDownloadBehavior", Map.of("behavior", "allow", "downloadPath", dir.toAbsolutePath().toString()));
        } catch (RuntimeException e) {
            chrome.executeCdpCommand("Page.setDownloadBehavior", Map.of("behavior", "allow", "downloadPath", dir.toAbsolutePath().toString()));
        }
    }

    /** The files in the folder now with their last change (taken before an action that downloads something). */
    public static Map<String, Long> snapshot(Path dir) {
        Map<String, Long> names = new HashMap<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> names.put(p.getFileName().toString(), changed(p)));
        } catch (IOException e) {
            throw new IllegalStateException("The download folder " + dir + " cannot be read: " + e.getMessage(), e);
        }
        return names;
    }

    /** Waits (max {@code seconds}) for a NEW finished file in the folder (not a ".crdownload" part): its size must not change for a second. Null when none came. */
    public static Path waitForNew(Path dir, Map<String, Long> before, int seconds) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        Path candidate = null;
        long lastSize = -1;
        long stableSince = 0;
        while (System.currentTimeMillis() < end) {
            Path now = null;
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String n = p.getFileName().toString();
                    boolean isNew = !before.containsKey(n) || changed(p) > before.get(n); // a download of the same name overwrites the old file
                    if (isNew && !n.endsWith(".crdownload") && !n.endsWith(".tmp")) {
                        now = p;
                    }
                }
            } catch (IOException e) {
                return null;
            }
            if (now != null) {
                long size = sizeOf(now);
                if (now.equals(candidate) && size == lastSize && size > 0) {
                    if (System.currentTimeMillis() - stableSince >= 1000) {
                        return now;
                    }
                } else {
                    candidate = now;
                    lastSize = size;
                    stableSince = System.currentTimeMillis();
                }
            }
            pause(250);
        }
        return null;
    }

    /** {@code <runId>_<purpose>_<original name>}; an existing file of that name is not overwritten (a number is added). */
    public static Path renameForRun(Path file, String runId, String purpose) {
        String base = runId + "_" + purpose + "_" + file.getFileName();
        Path target = file.resolveSibling(base);
        for (int n = 2; Files.exists(target); n++) {
            target = file.resolveSibling(runId + "_" + purpose + "_" + n + "_" + file.getFileName());
        }
        for (int attempt = 0; attempt < 20; attempt++) { // a big file can still be held by the browser for a moment
            try {
                return Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                try {
                    return Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e2) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return file; // keep the browser's name rather than lose the file
    }

    /**
     * What a downloaded file is, in plain words, and whether it can be opened: an Excel workbook (rows counted), a CSV (lines counted), a PDF (starts with %PDF) or a zip
     * (entries counted). Returns the description; {@code null} readable-ness is false when the file is empty or cannot be opened - see {@link #readable}.
     */
    public static String describe(Path file) {
        long size = sizeOf(file);
        String name = file.getFileName().toString();
        String lower = name.toLowerCase();
        try {
            if (size == 0) {
                return "'" + name + "' is empty (0 bytes)";
            }
            if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) {
                int rows = AorWorkbooks.readRows(file).size();
                return "'" + name + "' (" + size + " bytes) is an Excel file with " + PlainText.count(rows, "data row", "data rows");
            }
            if (lower.endsWith(".csv")) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                return "'" + name + "' (" + size + " bytes) is a CSV file with " + PlainText.count(lines.size(), "line", "lines");
            }
            if (lower.endsWith(".pdf")) {
                try (InputStream in = Files.newInputStream(file)) {
                    byte[] head = in.readNBytes(5);
                    boolean pdf = new String(head, StandardCharsets.ISO_8859_1).startsWith("%PDF");
                    return "'" + name + "' (" + size + " bytes) " + (pdf ? "is a PDF document" : "is called PDF but is not a PDF document");
                }
            }
            if (lower.endsWith(".zip")) {
                try (ZipFile z = new ZipFile(file.toFile())) {
                    return "'" + name + "' (" + size + " bytes) is a zip file with " + PlainText.count(z.size(), "file", "files");
                }
            }
            return "'" + name + "' (" + size + " bytes)";
        } catch (IOException | RuntimeException e) {
            return "'" + name + "' (" + size + " bytes) cannot be opened";
        }
    }

    /** The text of a PDF (white space collapsed), or null when it cannot be read (not a PDF, or only pictures). */
    public static String pdfText(Path file) {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
            String t = new org.apache.pdfbox.text.PDFTextStripper().getText(doc).replaceAll("\\s+", " ").trim();
            return t.isEmpty() ? null : t;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** True when the file exists, is not empty, and (for Excel, CSV, PDF, zip) can really be opened. */
    public static boolean readable(Path file) {
        if (file == null || !Files.isRegularFile(file) || sizeOf(file) == 0) {
            return false;
        }
        String d = describe(file);
        return !d.contains("cannot be opened") && !d.contains("is empty") && !d.contains("is not a PDF");
    }

    private static long changed(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** When the "download" is really an error page of the application's file storage (a small XML text with an Error element), its message; otherwise null. */
    public static String storageError(Path file) {
        try {
            if (Files.size(file) > 4000) {
                return null;
            }
            String t = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8).replace("﻿", "").trim();
            if (!t.startsWith("<?xml") || !t.contains("<Error>")) {
                return null;
            }
            java.util.regex.Matcher code = java.util.regex.Pattern.compile("<Code>(.*?)</Code>").matcher(t);
            java.util.regex.Matcher msg = java.util.regex.Pattern.compile("<Message>(.*?)(?:\r?\n|</Message>)", java.util.regex.Pattern.DOTALL).matcher(t);
            return (code.find() ? code.group(1) : "Error") + (msg.find() ? ": " + msg.group(1).trim() : "");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
