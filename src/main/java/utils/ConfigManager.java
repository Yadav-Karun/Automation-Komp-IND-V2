package utils;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Resolution order (first hit wins):
 * system property (-Dkey=value) -> environment variable (KOMP_KEY_NAME) ->
 * config/secrets.local.properties -> config/config.properties -> config/documents.properties.
 */
public final class ConfigManager {

    private static final Properties FILES = new Properties();

    static {
        load("config/documents.properties");
        load("config/config.properties");
        load("config/secrets.local.properties");
    }

    private ConfigManager() {
    }

    private static void load(String file) {
        Path p = Paths.get(file);
        if (!Files.exists(p)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(r);
            FILES.putAll(props);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }

    public static String get(String key) {
        String v = System.getProperty(key);
        if (isBlank(v)) {
            v = System.getenv("KOMP_" + key.toUpperCase().replace('.', '_'));
        }
        if (isBlank(v)) {
            v = FILES.getProperty(key);
        }
        return isBlank(v) ? null : v.trim();
    }

    public static String get(String key, String def) {
        String v = get(key);
        return v == null ? def : v;
    }

    public static int getInt(String key, int def) {
        String v = get(key);
        return v == null ? def : Integer.parseInt(v);
    }

    public static boolean getBool(String key, boolean def) {
        String v = get(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    public static String required(String key) {
        String v = get(key);
        if (v == null) {
            throw new IllegalStateException("Missing required config '" + key
                    + "'. Set it in config/secrets.local.properties, as -D" + key + ", or as env var KOMP_"
                    + key.toUpperCase().replace('.', '_') + ".");
        }
        return v;
    }

    // ---- environment ------------------------------------------------------

    public static String env() {
        String e = get("env", get("environment", "sandbox")).toLowerCase();
        switch (e) {
            case "sandbox":
            case "preprod":
                return e;
            case "prod":
            case "production":
            case "app":
                if (!getBool("allow.production", false)) {
                    throw new IllegalStateException("Refusing to run against PRODUCTION. "
                            + "Set -Dallow.production=true only if you explicitly intend that.");
                }
                return "production";
            default:
                throw new IllegalStateException("Unknown environment '" + e + "' (use sandbox | preprod | production)");
        }
    }

    public static String baseUrl() {
        return stripSlash(required(env() + ".base.url"));
    }

    public static String eorBaseUrl() {
        String v = get(env() + ".eor.base.url");
        return v == null ? baseUrl() : stripSlash(v);
    }

    public static String adminEmail() {
        return required(env() + ".admin.email");
    }

    public static String adminPassword() {
        return secret(env() + ".admin.password");
    }

    public static String eorEmail() {
        String v = get(env() + ".eor.email");
        return v != null ? v : adminEmail();
    }

    public static String eorPassword() {
        String v = get(env() + ".eor.password");
        return v != null ? SecretMasker_register(v) : adminPassword();
    }

    /** The AOR test user (Employee > AOR bulk upload, AOR Finance): <env>.aor.email / <env>.aor.password in config/secrets.local.properties. */
    public static String aorEmail() {
        return required(env() + ".aor.email");
    }

    public static String aorPassword() {
        return secret(env() + ".aor.password");
    }

    public static String clientPassword() {
        return secret("client.test.password");
    }

    /** Local file path from config; fails clearly if the file is missing (never fabricates documents). */
    public static String document(String key) {
        String path = required(key);
        if (!Files.isRegularFile(Paths.get(path))) {
            throw new IllegalStateException("Test document for '" + key + "' not found at: " + path);
        }
        return path;
    }

    private static String secret(String key) {
        return SecretMasker_register(required(key));
    }

    private static String SecretMasker_register(String v) {
        SecretMasker.register(v);
        return v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
