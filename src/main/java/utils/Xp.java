package utils;

/** XPath helpers. */
public final class Xp {

    private Xp() {
    }

    /** Safe XPath string literal (handles embedded quotes). */
    public static String literal(String s) {
        if (!s.contains("'")) {
            return "'" + s + "'";
        }
        if (!s.contains("\"")) {
            return "\"" + s + "\"";
        }
        StringBuilder sb = new StringBuilder("concat(");
        String[] parts = s.split("'", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(", \"'\", ");
            }
            sb.append("'").append(parts[i]).append("'");
        }
        return sb.append(")").toString();
    }
}
