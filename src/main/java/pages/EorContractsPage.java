package pages;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import base.BasePage;
import utils.TableReader;

/**
 * Admin side: sidebar MSA &amp; Contract &gt; Contract ({@code /contracts/eor}): the list of EOR contracts (client, employee code, employee name, contract id, personal e-mail,
 * status), the page of one contract (header, Contract Details, Employee, Salary Breakup with "Unmask all") and "Edit Contract" (a wizard: Contract Details, Salary Breakup,
 * Letter of Intent).
 */
public class EorContractsPage extends BasePage {

    private final By menu = By.xpath("//button[normalize-space()='MSA & Contract']");
    private final By link = By.xpath("//a[contains(@href,'contracts/eor')]");
    private final By search = By.xpath("//input[contains(@placeholder,'Search by client')]");
    private final By editContract = By.xpath("//button[normalize-space()='Edit Contract']");
    private final By saveContinue = By.xpath("//button[normalize-space()='Save & Continue']");
    private final By salaryToggle = By.xpath("//main//button[contains(normalize-space(.),'Salary Breakup')]");
    private final By unmask = By.xpath("//main//button[contains(normalize-space(.),'Unmask all')]");

    public EorContractsPage(WebDriver driver) {
        super(driver);
    }

    public void open() {
        if (!isDisplayed(link, 1)) {
            click(menu);
        }
        click(link);
        visible(search);
        waitFor(timeout).until(d -> !TableReader.rows(d).isEmpty() || !TableReader.showing(d).isEmpty() || bodyText().toLowerCase(Locale.ROOT).contains("no contracts"));
    }

    /** Types the text into the search box and waits until every row contains it (or nothing is found). */
    public List<Map<String, String>> search(String text) {
        type(search, text);
        String wanted = text.toLowerCase(Locale.ROOT);
        Instant end = Instant.now().plus(Duration.ofSeconds(timeout + 10));
        List<Map<String, String>> rows = List.of();
        int stable = 0;
        String last = "";
        while (Instant.now().isBefore(end)) {
            pause(500);
            rows = TableReader.rows(driver);
            boolean match = !rows.isEmpty() && rows.stream().allMatch(r -> String.join(" ", r.values()).toLowerCase(Locale.ROOT).contains(wanted));
            String now = TableReader.fingerprint(driver);
            stable = now.equals(last) ? stable + 1 : 0;
            last = now;
            if ((match && stable >= 2) || (rows.isEmpty() && stable >= 6)) {
                break;
            }
        }
        return rows;
    }

    /** Opens the contract of the first row (click on the row). */
    public void openFirstRow() {
        WebElement row = waitFor(timeout).until(d -> {
            List<WebElement> r = d.findElements(By.xpath("//tbody/tr"));
            return r.isEmpty() ? null : r.get(0);
        });
        ((JavascriptExecutor) driver).executeScript("arguments[0].click()", row);
        visible(editContract);
        pause(1500);
        utils.SmoothScroll.tour(driver);
    }

    /** The non-empty lines of the page (label, value, label, value ...). */
    public List<String> lines() {
        String all = (String) ((JavascriptExecutor) driver).executeScript("return (document.querySelector('main')||document.body).innerText;");
        List<String> out = new ArrayList<>();
        for (String l : all.split("\\R")) {
            if (!l.trim().isEmpty()) {
                out.add(l.trim());
            }
        }
        return out;
    }

    /** The line after the first line equal to the label (ignoring case) that stands after the marker line; null when there is none. */
    public static String after(List<String> lines, String marker, String label) {
        int start = marker.isEmpty() ? 0 : -1;
        for (int i = 0; i < lines.size() && start < 0; i++) {
            if (lines.get(i).equalsIgnoreCase(marker)) {
                start = i;
            }
        }
        if (start < 0) {
            return null;
        }
        for (int i = start; i < lines.size() - 1; i++) {
            if (lines.get(i).equalsIgnoreCase(label)) {
                return lines.get(i + 1);
            }
        }
        return null;
    }

    /** True when the contract page has the part "Salary Breakup" at all (the page of some accounts has none). */
    public boolean hasSalaryBreakup() {
        return isDisplayed(salaryToggle, 5);
    }

    /** Opens the part "Salary Breakup" without showing the hidden values. */
    public void expandSalaryBreakup() {
        WebElement t = visible(salaryToggle);
        utils.SmoothScroll.click(driver, t);
        visible(unmask);
        pause(1000);
    }

    public void unmaskSalary() {
        WebElement u = visible(unmask);
        utils.SmoothScroll.click(driver, u);
        visible(By.xpath("//main//button[contains(normalize-space(.),'Mask all')]"));
        pause(1500);
    }

    public void maskSalary() {
        WebElement m = visible(By.xpath("//main//button[contains(normalize-space(.),'Mask all')]"));
        utils.SmoothScroll.click(driver, m);
        visible(unmask);
        pause(1500);
    }

    /** The values below "Salary Breakup" that are still hidden behind dots (not counting the summary line "Annual CTC" above the button). */
    public int hiddenSalaryValues() {
        List<String> lines = lines();
        int from = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equalsIgnoreCase("Unmask all") || lines.get(i).equalsIgnoreCase("Mask all")) {
                from = i;
            }
        }
        int n = 0;
        for (int i = from + 1; from >= 0 && i < lines.size(); i++) {
            if (lines.get(i).contains("\u2022")) {
                n++;
            }
        }
        return n;
    }

    /** Opens the part "Salary Breakup" of the contract page and shows every masked value. */
    public void openSalaryBreakupUnmasked() {
        WebElement t = visible(salaryToggle);
        utils.SmoothScroll.click(driver, t);
        WebElement u = visible(unmask);
        utils.SmoothScroll.click(driver, u);
        pause(1500);
    }

    /** True while a value below the button "Mask all" is still hidden (the summary line "Annual CTC" above it always stays hidden). */
    public boolean stillMasked() {
        List<String> lines = lines();
        int from = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equalsIgnoreCase("Mask all")) {
                from = i;
            }
        }
        if (from < 0) {
            return true; // "Unmask all" was not used (or did not work): the button is still "Unmask all"
        }
        for (int i = from + 1; i < lines.size(); i++) {
            if (lines.get(i).contains("•")) {
                return true;
            }
        }
        return false;
    }

    /** Waits until the box of the label is on the page (the wizard fills its step a moment after it opens). */
    public void waitForField(String label) {
        waitFor(timeout).until(d -> editValues().containsKey(label));
    }

    public void clickEditContract() {
        WebElement b = visible(editContract);
        utils.SmoothScroll.click(driver, b);
        visible(By.xpath("//*[normalize-space()='Edit Contract']"));
        waitFor(timeout).until(d -> urlContains("/update/"));
        waitForField("Client Name");
        utils.SmoothScroll.tour(driver);
    }

    /** The boxes of the page of the wizard as label -> value in page order (the label of a box is the label of its group). */
    @SuppressWarnings("unchecked")
    public List<String[]> editFields() {
        Object o = ((JavascriptExecutor) driver).executeScript(
                "return Array.from(document.querySelectorAll('main input,main textarea,main select')).filter(x=>x.offsetParent!==null||x.tagName==='SELECT').map(x=>{"
                        + "const c=x.closest('div.flex-col')||x.parentElement;const lb=c?c.querySelector('label'):null;"
                        + "return [lb?lb.innerText.replace(/\\s+/g,' ').replace('*','').trim():'',x.name||x.id||x.tagName,x.value||''];});");
        List<String[]> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object row : (List<Object>) o) {
                List<Object> r = (List<Object>) row;
                out.add(new String[] {String.valueOf(r.get(0)), String.valueOf(r.get(1)), String.valueOf(r.get(2))});
            }
        }
        return out;
    }

    /** Label -> value of the boxes with a label of their own (the first box of every label). */
    public Map<String, String> editValues() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] f : editFields()) {
            if (!f[0].isBlank()) {
                m.putIfAbsent(f[0], f[2]);
            }
        }
        return m;
    }

    public void saveAndContinue() {
        WebElement b = visible(saveContinue);
        utils.SmoothScroll.to(driver, b);
        try {
            b.click();
        } catch (org.openqa.selenium.WebDriverException covered) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click()", b);
        }
        pause(1500);
        utils.SmoothScroll.tour(driver);
    }

    public String pageText() {
        return bodyText().replaceAll("\\s+", " ").trim();
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms); // documented: a list / a step of the wizard needs a moment to render
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
