package utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wording helpers. The reports are read by people who are not technical (managers, product owners): every sentence that reaches a report says
 * in everyday words what the application did and what it should have done. Technical evidence (stack traces, server requests) is kept, but in a
 * folded "Technical details" block ({@code TestReporter}), never in the sentence itself.
 */
public final class PlainText {

    private static final Pattern BOOLEAN_NOISE = Pattern.compile("\\s*expected \\[(true|false)\\] but found \\[(true|false)\\]\\s*$");
    private static final Pattern EXPECTED_FOUND = Pattern.compile("\\s*expected \\[(.*?)\\] but found \\[(.*)\\]\\s*$", Pattern.DOTALL);

    private PlainText() {
    }

    // ------------------------------------------------------------------ safety net: technical words in a report sentence

    /**
     * Technical term -> everyday words. THE RULE (user, 2026-11-08): every report is read by people who are not technical, so a sentence in a report never
     * uses these words. Authors write plain sentences at the source; this list is the safety net for a sentence that slipped through: the report shows the
     * translated sentence and the run log names the term, so the source can be fixed ({@link #simplify}, {@link #wordingNotes}).
     * Order matters (longer phrases first); matching ignores upper / lower case.
     */
    private static final String[][] TERMS = {
        { "HTTP\\s*5\\d\\d", "a server error" },
        { "HTTP\\s*4\\d\\d", "a refused request" },
        { "5xx", "server errors" },
        { "4xx", "refused requests" },
        { "API calls", "requests to the server" },
        { "API call", "request to the server" },
        { "API responses", "server answers" },
        { "API response", "server answer" },
        { "API", "server" },
        { "inline messages?", "messages shown under the fields" },
        { "toasts", "pop-up notices" },
        { "toast", "pop-up notice" },
        { "validation messages?", "error message" },
        { "validation errors?", "error message" },
        { "validation", "check" },
        { "empty-state", "'nothing found'" },
        { "empty state", "'nothing found'" },
        { "stack traces?", "error trace" },
        { "exceptions?", "error" },
        { "assertions?", "check" },
        { "selectors?", "page element" },
        { "locators?", "page element" },
        { "stale element", "page element that changed" },
        { "payload", "data sent" },
        { "endpoints?", "server address" },
        { "JSON", "data" },
        { "DOM", "page" },
        { "popovers?", "drop-down box" },
        { "modals?", "window" },
        { "dialogs?", "window" },
        { "wizard", "onboarding form" },
        { "status badge", "status label" },
        { "badge", "status label" },
        { "deep link", "direct address" },
        { "unauthenticated", "not signed in" },
        { "XSS", "script run by typed text" },
        { "backend", "server" },
        { "frontend", "page" },
    };

    private static final Pattern[] TERM_PATTERNS = new Pattern[TERMS.length];

    static {
        for (int i = 0; i < TERMS.length; i++) {
            TERM_PATTERNS[i] = Pattern.compile("(?<![A-Za-z0-9])" + TERMS[i][0] + "(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
        }
    }

    private static final java.util.Set<String> NOTED = new java.util.LinkedHashSet<>();

    /**
     * The sentence in everyday words: technical terms outside double quotes are replaced (text inside double quotes is evidence - a message of the application,
     * text of a page - and stays exactly as it was). Sentences that already follow the rule come back unchanged.
     */
    public static String simplify(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        StringBuilder segment = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                out.append(quoted ? segment : translate(segment.toString()));
                out.append(c);
                segment.setLength(0);
                quoted = !quoted;
            } else {
                segment.append(c);
            }
        }
        out.append(quoted ? segment : translate(segment.toString()));
        String result = out.toString();
        if (!result.equals(text)) {
            note(text);
        }
        return result;
    }

    private static String translate(String s) {
        String r = s;
        for (int i = 0; i < TERMS.length; i++) {
            r = TERM_PATTERNS[i].matcher(r).replaceAll(Matcher.quoteReplacement(TERMS[i][1]));
        }
        return r;
    }

    private static synchronized void note(String original) {
        if (NOTED.size() < 200) {
            NOTED.add(oneLine(original, 160));
        }
    }

    /** The sentences of this run that needed the automatic translation (they should be reworded at the source). */
    public static synchronized List<String> wordingNotes() {
        return new ArrayList<>(NOTED);
    }

    /** Forgets the notes: a new run (a new test class) starts clean. */
    public static synchronized void resetWordingNotes() {
        NOTED.clear();
    }

    /**
     * The text TestNG adds to an assertion message ("... expected [false] but found [true]") says nothing to a reader: for a yes / no check it is removed,
     * for a comparison it becomes "(expected: A; found: B)".
     */
    public static String assertion(String message) {
        if (message == null) {
            return "";
        }
        String m = message.replaceFirst("^(java\\.lang\\.)?AssertionError:\\s*", "");
        m = BOOLEAN_NOISE.matcher(m).replaceFirst("");
        Matcher x = EXPECTED_FOUND.matcher(m);
        if (x.find()) {
            m = m.substring(0, x.start()) + " (expected: " + x.group(1) + "; found: " + x.group(2) + ")";
        }
        return m.trim();
    }

    /** Drops every text that is contained in a longer one (a pop-up notice is read twice: its title and its full text), keeps the order. */
    public static List<String> withoutRepeats(List<String> texts) {
        List<String> out = new ArrayList<>();
        for (String t : texts) {
            if (t == null || t.isBlank()) {
                continue;
            }
            String s = t.trim();
            boolean inside = false;
            for (String other : texts) {
                if (other != null && other != t && other.trim().length() > s.length() && other.contains(s)) {
                    inside = true;
                    break;
                }
            }
            if (!inside && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /** "a"; "b" - every text in double quotes. */
    public static String quote(List<String> texts) {
        List<String> q = new ArrayList<>();
        for (String t : texts) {
            q.add("\"" + t.replaceAll("\\s+", " ").trim() + "\"");
        }
        return String.join("; ", q);
    }

    /** At most {@code max} items, then "and N more": "Black Clover, Bocchi the Rock, Chainsaw Man and 7 more". */
    public static String some(List<String> items, int max) {
        if (items.size() <= max) {
            return String.join(", ", items);
        }
        return String.join(", ", items.subList(0, max)) + " and " + (items.size() - max) + " more";
    }

    /** The first {@code n} sentences of a text (a sentence ends with ". " or at the end): the headline of a failed case stays short. */
    public static String firstSentences(String text, int n) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("\\s+", " ").trim();
        int end = 0;
        for (int i = 0; i < n; i++) {
            int dot = t.indexOf(". ", end);
            if (dot < 0) {
                return t;
            }
            end = dot + 2;
        }
        return t.substring(0, end).trim();
    }

    /** "1 client" / "2 clients". */
    public static String count(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    /** One line, no more than {@code max} characters. */
    public static String oneLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, Math.max(0, max - 3)) + "..." : t;
    }
}
