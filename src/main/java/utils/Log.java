package utils;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Minimal execution logger: stdout + logs/execution.log, always secret-masked. */
public final class Log {

    private static PrintWriter writer;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {
    }

    /** Starts the log file of the current test class (the previous class's file, if any, is closed first). */
    public static synchronized void init(Path logFile) {
        close();
        try {
            Files.createDirectories(logFile.getParent());
            writer = new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create log file " + logFile, e);
        }
    }

    public static synchronized void close() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    public static synchronized void info(String msg) {
        write("INFO ", msg);
    }

    public static synchronized void warn(String msg) {
        write("WARN ", msg);
    }

    public static synchronized void error(String msg) {
        write("ERROR", msg);
    }

    private static void write(String level, String msg) {
        String line = LocalDateTime.now().format(TS) + " " + level + " " + SecretMasker.mask(msg);
        System.out.println(line);
        if (writer != null) {
            writer.println(line);
        }
    }
}
