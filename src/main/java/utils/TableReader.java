package utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

/** Reads the table of a listing page in ONE script call (no stale elements): header (upper case) -> cell text, white space collapsed. */
public final class TableReader {

    private static final String ROWS_JS = "const h=Array.from(document.querySelectorAll('thead th')).map(t=>(t.innerText||'').trim().toUpperCase());"
            + "return Array.from(document.querySelectorAll('tbody tr')).filter(r=>r.cells.length>=4).map(r=>{const o={};"
            + "Array.from(r.cells).forEach((c,i)=>{o[h[i]||('COLUMN '+(i+1))]=(c.innerText||'').replace(/\\s+/g,' ').trim();});return o;});";
    private static final Pattern SHOWING = Pattern.compile("Showing\\s+(\\d+)\\D+(\\d+)\\s+of\\s+(\\d+)");

    private TableReader() {
    }

    /** The data rows of the table on the screen now (empty-state / loading rows have fewer than 4 cells and are left out). */
    @SuppressWarnings("unchecked")
    public static List<Map<String, String>> rows(WebDriver driver) {
        Object o = ((JavascriptExecutor) driver).executeScript(ROWS_JS);
        List<Map<String, String>> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object r : (List<Object>) o) {
                out.add(new LinkedHashMap<>((Map<String, String>) r));
            }
        }
        return out;
    }

    /** The value of a column, matched by part of its header (e.g. "SESSION", "FINAL PAYOUT"); empty when there is no such column. */
    public static String column(Map<String, String> row, String headerPart) {
        String wanted = headerPart.toUpperCase();
        for (Map.Entry<String, String> e : row.entrySet()) {
            if (e.getKey().contains(wanted)) {
                return e.getValue();
            }
        }
        return "";
    }

    /** "Showing 1-10 of 11 invoices" as shown at the bottom of the table, or "" when the page has no such text. */
    public static String showing(WebDriver driver) {
        Object o = ((JavascriptExecutor) driver).executeScript("return (document.body.innerText.match(/Showing[^\\n]*/)||[''])[0];");
        return String.valueOf(o).trim();
    }

    /** N of "Showing a-b of N ..."; -1 when there is no such text. */
    public static int total(WebDriver driver) {
        Matcher m = SHOWING.matcher(showing(driver));
        return m.find() ? Integer.parseInt(m.group(3)) : -1;
    }

    /** What the table shows right now (the "Showing" text and the first cell of every row): it changes when the page, a filter or the search changes. */
    public static String fingerprint(WebDriver driver) {
        try {
            return String.valueOf(((JavascriptExecutor) driver).executeScript(
                    "return (document.body.innerText.match(/Showing[^\\n]*/)||[''])[0]+'|'+Array.from(document.querySelectorAll('tbody tr')).map(r=>r.cells[0]?r.cells[0].innerText:'').join('~');"));
        } catch (RuntimeException e) {
            return "";
        }
    }
}
