package pages;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.Select;

import base.BasePage;
import utils.DateInput;
import utils.TableReader;
import utils.Xp;

/**
 * One of the three AOR list screens (AOR Agents, AOR Payroll, AOR Invoices) with the controls they share: search box, status tabs, the filter "Client" (searchable list), date boxes,
 * page size, numbered pages with Previous / Next, sortable headings and (Agents only) the Columns menu. Written like {@link ClientsPage} (same design system).
 */
public class AorListPage extends BasePage {

    /** What differs between the three screens. */
    public static final class Spec {
        public final String name;
        public final Runnable opener;
        public final By search;
        public final String[] tabs;
        public final String statusHeader;
        public final String clientHeader;
        public final String dateHeader;
        public final String[] sortHeaders;
        public final String[] searchHeaders;
        public final boolean columnsMenu;
        public final boolean dateBoxes;

        public Spec(String name, Runnable opener, By search, String[] tabs, String statusHeader, String clientHeader, String dateHeader, String[] sortHeaders, String[] searchHeaders,
                boolean columnsMenu, boolean dateBoxes) {
            this.name = name;
            this.opener = opener;
            this.search = search;
            this.tabs = tabs;
            this.statusHeader = statusHeader;
            this.clientHeader = clientHeader;
            this.dateHeader = dateHeader;
            this.sortHeaders = sortHeaders;
            this.searchHeaders = searchHeaders;
            this.columnsMenu = columnsMenu;
            this.dateBoxes = dateBoxes;
        }
    }

    private final Spec spec;
    private final By pageSizeSelect = By.xpath("//select[option[contains(normalize-space(),'/ page')]]");
    private final By previous = By.xpath("//button[@aria-label='Previous page']");
    private final By next = By.xpath("//button[@aria-label='Next page']");
    private final By popover = By.xpath("//*[@data-slot='popover-content']");
    private final By clearAll = By.xpath("//button[normalize-space()='Clear all']");

    public AorListPage(WebDriver driver, Spec spec) {
        super(driver);
        this.spec = spec;
    }

    public Spec spec() {
        return spec;
    }

    public void open() {
        spec.opener.run();
        waitFor(timeout).until(d -> !TableReader.rows(d).isEmpty() || !TableReader.showing(d).isEmpty());
        settle();
    }

    // ------------------------------------------------------------------ reading

    public List<Map<String, String>> rows() {
        return TableReader.rows(driver);
    }

    public int total() {
        return TableReader.total(driver);
    }

    public String showing() {
        return TableReader.showing(driver);
    }

    public String column(Map<String, String> row, String header) {
        return TableReader.column(row, header);
    }

    public List<String> headers() {
        List<String> out = new ArrayList<>();
        for (WebElement th : driver.findElements(By.xpath("//thead//th"))) {
            out.add(th.getText().trim());
        }
        return out;
    }

    /** Waits until the list stops changing (a slow search / filter leaves the old rows for a while). */
    public void settle() {
        String last = "";
        int stable = 0;
        Instant end = Instant.now().plus(Duration.ofSeconds(timeout));
        while (Instant.now().isBefore(end) && stable < 5) {
            pause(400);
            String now = TableReader.fingerprint(driver);
            stable = now.equals(last) ? stable + 1 : 0;
            last = now;
        }
    }

    /** The number a page starts with ("Showing 51-100 of 325" -> 51); -1 when the text has no such number. */
    private int startOfPage() {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Showing\\s+([0-9]+)").matcher(showing());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * Every row of the list as it is now (all pages, 50 per page). After every "Next" the page is read only when its "Showing a-b" text has moved on to the expected start, so a page
     * that is still the old one is never read twice.
     */
    public List<Map<String, String>> allRows() {
        if (!driver.findElements(pageSizeSelect).isEmpty()) {
            pageSize(50);
        }
        gotoPage(1);
        List<Map<String, String>> all = new ArrayList<>();
        for (int page = 0; page < 60; page++) {
            all.addAll(rows());
            if (!nextEnabled()) {
                break;
            }
            int expectedStart = all.size() + 1;
            clickNext();
            try {
                waitFor(timeout).until(d -> startOfPage() == expectedStart);
            } catch (org.openqa.selenium.TimeoutException notMoved) {
                break; // the page did not move on: nothing is read twice
            }
            settle();
        }
        return all;
    }

    // ------------------------------------------------------------------ search, tabs, filter

    /**
     * Types into the search box and waits for the RESULT: the list is read only after it has changed (or, when the result is the same as before, after 4 seconds) and then stood
     * still for two seconds - the search starts a moment after the last key and the old rows stay on the page until the answer arrives (seen: the old rows were read as the result).
     */
    public List<Map<String, String>> search(String text) {
        String before = TableReader.fingerprint(driver);
        WebElement box = visible(spec.search);
        box.sendKeys(org.openqa.selenium.Keys.chord(org.openqa.selenium.Keys.CONTROL, "a"), org.openqa.selenium.Keys.BACK_SPACE);
        if (text != null && !text.isEmpty()) {
            box.sendKeys(text);
        }
        Instant end = Instant.now().plus(Duration.ofSeconds(4));
        while (Instant.now().isBefore(end) && TableReader.fingerprint(driver).equals(before)) {
            pause(300);
        }
        settle();
        return rows();
    }

    public void clickTab(String tab) {
        By t = By.xpath("//button[normalize-space()=" + Xp.literal(tab) + "]");
        click(t);
        settle();
    }

    private By filterTrigger() {
        return By.xpath("//button[@data-slot='popover-trigger'][.//span[normalize-space()='Client']] | //button[@data-slot='popover-trigger'][starts-with(normalize-space(.),'Client')]");
    }

    public String clientFilterText() {
        return visible(filterTrigger()).getText().replace("\n", " ").trim();
    }

    /** The entries of the filter "Client" (opened and closed again). */
    public List<String> clientOptions() {
        click(filterTrigger());
        visible(popover);
        pause(600);
        List<String> out = new ArrayList<>();
        for (WebElement e : driver.findElements(By.xpath("//*[@data-slot='popover-content']//*[@cmdk-item]"))) {
            String t = e.getText().trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        closePopover();
        return out;
    }

    public void selectClient(String client) {
        click(filterTrigger());
        visible(popover);
        click(By.xpath("//*[@data-slot='popover-content']//*[@cmdk-item][normalize-space()=" + Xp.literal(client) + "]"));
        closePopover();
        settle();
    }

    private void closePopover() {
        driver.findElement(By.tagName("body")).sendKeys(org.openqa.selenium.Keys.ESCAPE);
        try {
            waitGone(popover);
        } catch (org.openqa.selenium.TimeoutException stays) {
            driver.findElement(By.tagName("body")).sendKeys(org.openqa.selenium.Keys.ESCAPE);
        }
    }

    public boolean clearAllFilters() {
        if (!isDisplayed(clearAll, 2)) {
            return false;
        }
        click(clearAll);
        settle();
        return true;
    }

    // ------------------------------------------------------------------ paging

    public void pageSize(int n) {
        new Select(visible(pageSizeSelect)).selectByVisibleText(n + " / page");
        int expected = n;
        try {
            // the list keeps the old rows until the new page size is loaded: wait until it shows min(n, total) rows
            waitFor(timeout).until(d -> {
                int total = TableReader.total(d);
                int rows = TableReader.rows(d).size();
                return total < 0 ? rows > 0 : rows == Math.min(expected, total);
            });
        } catch (org.openqa.selenium.TimeoutException keepsOld) {
            // the check of the scenario reports what the list shows
        }
        settle();
    }

    public boolean hasPageSize() {
        return !driver.findElements(pageSizeSelect).isEmpty();
    }

    public boolean gotoPage(int n) {
        List<WebElement> b = driver.findElements(By.xpath("//button[@aria-label='Previous page']/following-sibling::button[normalize-space()='" + n + "']"));
        if (b.isEmpty()) {
            return n == 1;
        }
        ((JavascriptExecutor) driver).executeScript("arguments[0].click()", b.get(0));
        settle();
        return true;
    }

    public boolean nextEnabled() {
        List<WebElement> b = driver.findElements(next);
        return !b.isEmpty() && b.get(0).isEnabled() && !"true".equals(b.get(0).getAttribute("aria-disabled"));
    }

    public boolean previousEnabled() {
        List<WebElement> b = driver.findElements(previous);
        return !b.isEmpty() && b.get(0).isEnabled() && !"true".equals(b.get(0).getAttribute("aria-disabled"));
    }

    public void clickNext() {
        ((JavascriptExecutor) driver).executeScript("arguments[0].click()", visible(next));
    }

    public void clickPrevious() {
        ((JavascriptExecutor) driver).executeScript("arguments[0].click()", visible(previous));
    }

    // ------------------------------------------------------------------ sort, columns, dates

    public void sortBy(String header) {
        By th = By.xpath("//th[normalize-space()=" + Xp.literal(header) + "] | //th[translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ')=" + Xp.literal(header.toUpperCase(Locale.ROOT)) + "]");
        click(th);
        settle();
    }

    public boolean hasColumnsMenu() {
        return !driver.findElements(By.xpath("//button[normalize-space()='Columns']")).isEmpty();
    }

    public List<String> columnMenuItems() {
        click(By.xpath("//button[normalize-space()='Columns']"));
        visible(By.xpath("//*[@role='menu']"));
        List<String> out = new ArrayList<>();
        for (WebElement e : driver.findElements(By.xpath("//*[@role='menuitemcheckbox']"))) {
            out.add(e.getText().trim());
        }
        driver.findElement(By.tagName("body")).sendKeys(org.openqa.selenium.Keys.ESCAPE);
        return out;
    }

    public void toggleColumn(String name) {
        click(By.xpath("//button[normalize-space()='Columns']"));
        click(By.xpath("//*[@role='menuitemcheckbox'][normalize-space()=" + Xp.literal(name) + "]"));
        driver.findElement(By.tagName("body")).sendKeys(org.openqa.selenium.Keys.ESCAPE);
        pause(600);
    }

    public int firstRowCells() {
        List<WebElement> trs = driver.findElements(By.xpath("//tbody/tr"));
        return trs.isEmpty() ? 0 : trs.get(0).findElements(By.tagName("td")).size();
    }

    public void setDates(LocalDate from, LocalDate to) {
        List<WebElement> boxes = driver.findElements(By.xpath("//main//input[@type='date']"));
        if (boxes.size() < 2) {
            throw new AssertionError("The list has no two date boxes.");
        }
        if (from != null) {
            DateInput.type(driver, boxes.get(0), from);
        }
        if (to != null) {
            DateInput.type(driver, driver.findElements(By.xpath("//main//input[@type='date']")).get(1), to);
        }
        settle();
    }

    public void clearDates() {
        for (WebElement e : driver.findElements(By.xpath("//main//input[@type='date']"))) {
            ((JavascriptExecutor) driver).executeScript(
                    "const i=arguments[0];Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(i,'');i.dispatchEvent(new Event('input',{bubbles:true}));i.dispatchEvent(new Event('change',{bubbles:true}));", e);
        }
        settle();
    }

    public String pageText() {
        return bodyText().replaceAll("\\s+", " ").trim();
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms); // documented: a list re-renders a moment after a search / filter / page change
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
