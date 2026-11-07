package utils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Central place that can remove secrets (passwords, temporary passwords, e-mail addresses) from anything written to logs and reports.
 * <p>
 * Masking is OFF by default: the sandbox / preprod runs use throw-away test accounts and the reports are handed to management with the
 * logins visible. Switch it on with {@code -Dmask.secrets=true}. A run against PRODUCTION always masks, whatever the switch says.
 * The secrets file {@code config/secrets.local.properties} is gitignored, as are {@code reports/} and {@code *.log}.
 */
public final class SecretMasker {

    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();
    private static final Pattern TEMP_PASSWORD = Pattern.compile("(?i)(temporary\\s+password\\s*:?\\s*)\\S+");

    private SecretMasker() {
    }

    /** True when secrets are hidden: asked for with -Dmask.secrets=true, or the run targets production. */
    public static boolean enabled() {
        if (ConfigManager.getBool("mask.secrets", false)) {
            return true;
        }
        try {
            return "production".equals(ConfigManager.env());
        } catch (RuntimeException e) {
            return true; // unknown / refused environment: stay safe
        }
    }

    public static void register(String secret) {
        if (secret != null && secret.length() >= 4) {
            SECRETS.add(secret);
        }
    }

    public static String mask(String text) {
        if (text == null || !enabled()) {
            return text;
        }
        String out = TEMP_PASSWORD.matcher(text).replaceAll("$1********");
        for (String secret : SECRETS) {
            out = out.replace(secret, "********");
        }
        return out;
    }

    /** komp.ind.2026...@yopmail.com -> kom***@yopmail.com (only while masking is on; otherwise the address as it is). */
    public static String maskEmail(String email) {
        if (!enabled() || email == null || !email.contains("@")) {
            return email;
        }
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        return local.substring(0, Math.min(3, local.length())) + "***" + email.substring(at);
    }

    /**
     * A secret for display in a report: the value itself, or "(masked)" while masking is on. A very long value (the 300-character password
     * of case P-23) is shortened to its start and its length.
     */
    public static String reveal(String secret) {
        if (secret == null) {
            return "(not known)";
        }
        if (enabled()) {
            return "(masked)";
        }
        return secret.length() > 48 ? secret.substring(0, 24) + "... (" + secret.length() + " characters)" : secret;
    }
}
