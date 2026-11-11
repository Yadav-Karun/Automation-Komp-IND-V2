package pages;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import base.BasePage;
import utils.DateInput;
import utils.TableReader;

/**
 * Sidebar AOR Financials > Invoices (/financials/aor/invoices), "AOR Invoices". Verified on the sandbox (2026-11-10): the tabs All / Paid / Pending Invoice, a search
 * box "Search invoices...", a Client filter, two date boxes, and the table INVOICE ID, SESSION ID, CLIENT NAME, STATE, AMOUNT, INVOICE DATE, DUE DATE, STATUS.
 * One invoice row is one state of one payroll session, so there are fewer rows than employees.
 */
public class AorInvoicesPage extends BasePage {

    private final By financialsMenu = By.xpath("//button[contains(normalize-space(.),'AOR Financials')]");
    private final By invoicesLink = By.xpath("//a[@href='/financials/aor/invoices']");
    private final By heading = By.xpath("//h1[normalize-space()='AOR Invoices']");
    private final By pageDates = By.xpath("//input[@type='date'][not(ancestor::div[@role='dialog'])]");

    public AorInvoicesPage(WebDriver driver) {
        super(driver);
    }

    public void open() {
        if (!isDisplayed(invoicesLink, 1)) {
            click(financialsMenu);
        }
        click(invoicesLink);
        visible(heading);
        waitStable();
    }

    /** Loads the page again (F5): the list is rebuilt, so a deletion shows. */
    public void reload() {
        driver.navigate().refresh();
        visible(heading);
        waitStable();
    }

    /** Opens the window of the page's "Download Report" button (client, invoice month, projects, state). */
    public AorDialog openReportDialog() {
        click(By.xpath("//button[normalize-space()='Download Report']"));
        AorDialog d = new AorDialog(driver);
        d.waitOpen("Download Report");
        return d;
    }

    /**
     * Clicks "Refresh Report" and waits until the application confirms it ("... report refreshed successfully."). Returns the messages that appeared;
     * waits at most {@code seconds}.
     */
    public List<String> refreshReport(int seconds) {
        utils.ToastWatcher.clear(driver);
        click(By.xpath("//button[normalize-space()='Refresh Report']"));
        List<String> messages = new ArrayList<>();
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            pause(500);
            for (String t : utils.ToastWatcher.all(driver)) {
                if (!messages.contains(t)) {
                    messages.add(t);
                }
            }
            if (messages.stream().anyMatch(m -> m.toLowerCase().contains("refreshed") || m.toLowerCase().contains("fail") || m.toLowerCase().contains("error"))) {
                pause(1000);
                break;
            }
        }
        return messages;
    }

    /** "..." button of a row, then "Delete": opens the confirmation window (nothing is deleted yet); returns its text. */
    public String openDeleteOfRow(int rowIndex) {
        click(By.xpath("(//tbody/tr[count(td)>=4])[" + (rowIndex + 1) + "]//button[@title='Actions']"));
        click(By.xpath("//*[@role='menuitem' and normalize-space()='Delete']"));
        return DeleteWindow.waitText(driver);
    }

    /** "..." button of a row, then "View": the invoice opens in a window; returns its text. */
    public String viewRow(int rowIndex) {
        click(By.xpath("(//tbody/tr[count(td)>=4])[" + (rowIndex + 1) + "]//button[@title='Actions']"));
        click(By.xpath("//*[@role='menuitem' and normalize-space()='View']"));
        return DeleteWindow.waitText(driver);
    }

    /** Clicks the download control of the open invoice window (a button called Download, or the icon button at the top that is not the close button). */
    public void downloadOpenInvoice() {
        Object r = ((org.openqa.selenium.JavascriptExecutor) driver).executeScript(
                "var all=[...document.querySelectorAll('[role=dialog],[role=alertdialog]')].filter(function(x){var b=x.getBoundingClientRect();return b.width>0&&b.height>0;});"
                + "var d=all.length?all[all.length-1]:null; if(!d) return 'no window';"
                + "var bs=[...d.querySelectorAll('button,a,[role=button]')];"
                + "var named=bs.find(function(b){return /download/i.test((b.innerText||'')+(b.getAttribute('aria-label')||'')+(b.title||''));});"
                + "var close=function(b){return /close|cancel/i.test((b.innerText||'')+(b.getAttribute('aria-label')||''))||/absolute/.test(b.className||'');};"
                + "var icon=bs.find(function(b){return !close(b)&&b.querySelector('svg')&&b.getBoundingClientRect().top<d.getBoundingClientRect().top+140;});"
                + "var pick=named||icon; if(!pick) return 'none: '+bs.map(function(b){return (b.innerText||b.getAttribute('aria-label')||b.title||b.tagName).trim().slice(0,20);}).join(' | ');"
                + "pick.click(); return 'clicked';");
        if (!"clicked".equals(String.valueOf(r))) {
            throw new AssertionError("The invoice window has no download control (" + r + ").");
        }
    }

    /** Types into "Search invoices..." and waits until the list shows the result; returns the rows. */
    public List<Map<String, String>> search(String text) {
        By box = By.xpath("//input[starts-with(@placeholder,'Search inv')]");
        String before = TableReader.fingerprint(driver);
        type(box, text);
        waitChanged(before);
        return TableReader.rows(driver);
    }

    /** Shows only the invoices dated between the two dates. */
    public void filterDates(LocalDate from, LocalDate to) {
        List<WebElement> boxes = driver.findElements(pageDates);
        if (boxes.size() < 2) {
            throw new AssertionError("The invoice list has no two date boxes to filter the invoice date.");
        }
        String before = TableReader.fingerprint(driver);
        DateInput.type(driver, boxes.get(0), from);
        DateInput.type(driver, driver.findElements(pageDates).get(1), to);
        waitChanged(before);
    }

    /** Every invoice row of the list, all pages; each page is read after the table has settled and the count is compared with the "of N" of the "Showing" text (read again when it differs). */
    public List<Map<String, String>> allRows() {
        List<Map<String, String>> out = new ArrayList<>();
        for (int attempt = 0; attempt < 4; attempt++) {
            waitStable();
            for (int i = 0; i < 60; i++) {
                List<WebElement> prev = driver.findElements(By.xpath("//button[@aria-label='Previous page']"));
                if (prev.isEmpty() || !prev.get(0).isEnabled() || "true".equals(prev.get(0).getAttribute("aria-disabled"))) {
                    break;
                }
                String before = TableReader.fingerprint(driver);
                prev.get(0).click();
                waitChanged(before);
            }
            out = new ArrayList<>();
            for (int page = 0; page < 60; page++) {
                waitStable();
                out.addAll(TableReader.rows(driver));
                List<WebElement> next = driver.findElements(By.xpath("//button[@aria-label='Next page']"));
                if (next.isEmpty() || !next.get(0).isEnabled() || "true".equals(next.get(0).getAttribute("aria-disabled"))) {
                    break;
                }
                String before = TableReader.fingerprint(driver);
                next.get(0).click();
                waitChanged(before);
            }
            int total = TableReader.total(driver);
            if (total < 0 || out.size() == total) {
                return out;
            }
            pause(2000);
        }
        return out;
    }

    public String showing() {
        return TableReader.showing(driver);
    }

    private void waitStable() {
        waitChanged("\u0000never");
    }

    private void waitChanged(String before) {
        long end = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < end && TableReader.fingerprint(driver).equals(before)) {
            pause(150);
        }
        String last = null;
        long since = System.currentTimeMillis();
        end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            String now = TableReader.fingerprint(driver);
            if (now.equals(last)) {
                if (System.currentTimeMillis() - since >= 700) {
                    return;
                }
            } else {
                last = now;
                since = System.currentTimeMillis();
            }
            pause(150);
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
