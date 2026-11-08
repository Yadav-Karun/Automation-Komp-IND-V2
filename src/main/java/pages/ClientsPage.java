package pages;

import java.time.Duration;
import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import base.BasePage;
import utils.Xp;

/** Admin > Clients list. */
public class ClientsPage extends BasePage {

    public static class Row {
        public String company;
        public String email;
        public String poc;
        public String type;
        public String country;
        public String mobile;
        public String status;

        @Override
        public String toString() {
            return company + " | " + email + " | " + poc + " | " + type + " | " + country + " | " + mobile + " | " + status;
        }
    }

    private final By sidebarLink = By.xpath("//a[@href='/clients']");
    private final By heading = By.xpath("//h1[normalize-space()='Clients']");
    private final By registerButton = By.xpath("//button[normalize-space()='Register Client']");
    private final By search = By.xpath("//input[@placeholder='Search by company or POC name']");

    public ClientsPage(WebDriver driver) {
        super(driver);
    }

    public void open() {
        click(sidebarLink);
        visible(heading);
    }

    public void clickRegisterClient() {
        click(registerButton);
    }

    private By rowFor(String company) {
        return By.xpath("//tbody/tr[.//div[normalize-space()=" + Xp.literal(company) + "]]");
    }

    /** Searches by company name and returns the matching row once it appears. */
    public Row findClient(String company) {
        visible(heading);
        type(search, company);
        waitSearchApplied(company);
        try {
            waitFor(timeout).until(ExpectedConditions.visibilityOfElementLocated(rowFor(company)));
        } catch (TimeoutException e) {
            throw new AssertionError("Client '" + company + "' not found in the Clients list after searching for it.");
        }
        return read(driver.findElement(rowFor(company)));
    }

    private boolean searchApplied;

    /** True when the last {@link #findClient} saw the RESULT of its search on the screen (every shown row contains the searched name). */
    public boolean searchApplied() {
        return searchApplied;
    }

    /**
     * Waits until the list shows the RESULT of the search: at least one row and every data row contains the searched text, unchanged for 1 s (max 12 s).
     * A slow search leaves the old, unfiltered list on the screen for a while - the client we look for is in it too, so "the row is visible" proves nothing -
     * and a screenshot taken then shows the wrong list (seen in TC-E2E-03). {@link #searchApplied()} tells whether the result was reached.
     */
    private void waitSearchApplied(String term) {
        String wanted = term.trim().toLowerCase(java.util.Locale.ROOT);
        String last = null;
        long stableSince = System.currentTimeMillis();
        long end = System.currentTimeMillis() + 12_000;
        searchApplied = false;
        while (System.currentTimeMillis() < end) {
            String texts = null;
            boolean everyRowMatches = false;
            try {
                List<WebElement> rows = driver.findElements(By.xpath("//tbody/tr[count(td)>=6]"));
                StringBuilder sb = new StringBuilder();
                everyRowMatches = !rows.isEmpty();
                for (WebElement tr : rows) {
                    String row = tr.getText().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
                    sb.append(row).append('|');
                    if (!row.contains(wanted)) {
                        everyRowMatches = false;
                    }
                }
                texts = sb.toString();
            } catch (org.openqa.selenium.StaleElementReferenceException rerendered) {
                texts = null;
            }
            if (texts != null && everyRowMatches && texts.equals(last)) {
                if (System.currentTimeMillis() - stableSince >= 1000) {
                    searchApplied = true;
                    return;
                }
            } else {
                last = texts;
                stableSince = System.currentTimeMillis();
            }
            try {
                Thread.sleep(300); // documented wait: the list re-renders after typing
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---- Group C helpers: search, filters, pagination, counters (read-only) --------------------------

    // Verified live (2026-11-07): Country / Client Type are Radix popovers with a searchable multi-select list (cmdk);
    // page size is a native <select>; the four stat tiles are buttons that filter by status; headers sort; Columns is a checkbox menu.
    private final By popoverContent = By.xpath("//*[@data-slot='popover-content']");
    private final By pageSizeSelect = By.xpath("//select[option[contains(normalize-space(),'/ page')]]");
    private final By clearAll = By.xpath("//button[normalize-space()='Clear all']");
    private final By columnsButton = By.xpath("//button[@data-slot='dropdown-menu-trigger'][normalize-space()='Columns']");
    private final By previousPage = By.xpath("//button[@aria-label='Previous page']");
    private final By nextPage = By.xpath("//button[@aria-label='Next page']");

    /** Types the term into the search box (an empty term clears it). Does not wait for any result. */
    public void searchOnly(String term) {
        visible(heading);
        WebElement e = visible(search);
        e.sendKeys(org.openqa.selenium.Keys.chord(org.openqa.selenium.Keys.CONTROL, "a"), org.openqa.selenium.Keys.BACK_SPACE);
        if (term != null && !term.isEmpty()) {
            e.sendKeys(term);
        }
    }

    /**
     * Waits until the table stops changing (1.5 s without change, max 12 s), then returns the real data rows.
     * While a search term is in the search box the list only counts as settled when it is empty or shows that term: a SLOW search leaves the
     * old list unchanged for a while, which would otherwise look "stable" (seen: X-28 read the AOR list before the search was applied).
     */
    public List<Row> visibleRows() {
        String last = null;
        long stableSince = System.currentTimeMillis();
        long end = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < end) {
            String now = tableText();
            if (now.equals(last)) {
                if (System.currentTimeMillis() - stableSince > 1500 && matchesSearchBox(now)) {
                    break;
                }
            } else {
                last = now;
                stableSince = System.currentTimeMillis();
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        List<Row> rows = new java.util.ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                rows.clear();
                for (WebElement tr : driver.findElements(By.xpath("//tbody/tr"))) {
                    if (tr.findElements(By.tagName("td")).size() >= 6) {
                        rows.add(read(tr));
                    }
                }
                break;
            } catch (org.openqa.selenium.StaleElementReferenceException stale) {
                // table re-rendered while reading: read again
            }
        }
        return rows;
    }

    /** True when no search term is typed, when the table has no data rows, or when the table text contains the term (case-insensitive). */
    private boolean matchesSearchBox(String tableText) {
        try {
            String term = String.valueOf(driver.findElement(search).getAttribute("value")).trim().toLowerCase(java.util.Locale.ROOT);
            if (term.isEmpty() || "null".equals(term)) {
                return true;
            }
            boolean hasDataRows = driver.findElements(By.xpath("//tbody/tr[count(td)>=6]")).size() > 0;
            return !hasDataRows || tableText.toLowerCase(java.util.Locale.ROOT).contains(term);
        } catch (RuntimeException e) {
            return true; // cannot tell: do not hold the case up
        }
    }

    private String tableText() {
        List<WebElement> t = driver.findElements(By.xpath("//tbody"));
        return t.isEmpty() ? "(no table)" : t.get(0).getText();
    }

    /** Text shown inside the table when it has no data rows (empty-state message), or "" when rows exist. */
    public String emptyStateText() {
        List<WebElement> trs = driver.findElements(By.xpath("//tbody/tr"));
        if (trs.size() == 1 && trs.get(0).findElements(By.tagName("td")).size() < 6) {
            return trs.get(0).getText().trim();
        }
        return trs.isEmpty() ? "(table body is empty)" : "";
    }

    /** "Showing 1-10 of 21 clients" (or "" when not shown). */
    public String listSummary() {
        List<WebElement> s = driver.findElements(By.xpath("//*[starts-with(normalize-space(text()),'Showing')]"));
        return s.isEmpty() ? "" : s.get(0).getText().replace("\n", " ").trim();
    }

    /** Total number of clients from "Showing a-b of N clients" (-1 when the summary is not shown). */
    public int totalFromSummary() {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("of\\s+(\\d+)\\s+clients").matcher(listSummary());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    // ---- stat tiles (Total / Active / Inactive / In-Progress): buttons that filter by status ----

    private By tile(String label) {
        return By.xpath("//button[.//div[normalize-space()=" + Xp.literal(label) + "]]");
    }

    public void clickTile(String label) {
        click(tile(label));
    }

    public int tileValue(String label) {
        String t = visible(tile(label)).getText().replaceAll("[^0-9\\n]", " ").trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(t);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    // ---- Country / Client Type filters (popover, searchable, multi-select) ----------------------

    private By filterTrigger(String label) {
        return By.xpath("//button[@data-slot='popover-trigger'][.//span[normalize-space()=" + Xp.literal(label) + "]]");
    }

    /** Trigger text, e.g. "Country Any" or "Country India". */
    public String filterText(String label) {
        return visible(filterTrigger(label)).getText().replace("\n", " ").trim();
    }

    public void openFilter(String label) {
        click(filterTrigger(label));
        visible(popoverContent);
    }

    public void toggleFilterOption(String option) {
        click(By.xpath("//*[@data-slot='popover-content']//*[@cmdk-item and @data-value=" + Xp.literal(option) + "]"));
    }

    public java.util.List<String> filterOptionsShown() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (WebElement e : driver.findElements(By.xpath("//*[@data-slot='popover-content']//*[@cmdk-item]"))) {
            out.add(e.getText().trim());
        }
        return out;
    }

    public void searchInFilter(String text) {
        WebElement in = visible(By.xpath("//*[@data-slot='popover-content']//input[@data-slot='command-input']"));
        in.sendKeys(org.openqa.selenium.Keys.chord(org.openqa.selenium.Keys.CONTROL, "a"), org.openqa.selenium.Keys.BACK_SPACE);
        if (text != null && !text.isEmpty()) {
            in.sendKeys(text);
        }
    }

    public String filterPopoverText() {
        List<WebElement> p = driver.findElements(popoverContent);
        return p.isEmpty() ? "" : p.get(0).getText().replace("\n", " ").trim();
    }

    public void closePopover() {
        driver.findElement(By.tagName("body")).sendKeys(org.openqa.selenium.Keys.ESCAPE);
        waitGone(popoverContent);
    }

    /** Opens the filter, ticks the options (multi-select) and closes it. */
    public void selectFilter(String label, String... options) {
        openFilter(label);
        for (String o : options) {
            toggleFilterOption(o);
        }
        closePopover();
    }

    /** Clicks 'Clear all' when it is shown; returns whether it was there. */
    public boolean clearAllFilters() {
        if (!isDisplayed(clearAll, 2)) {
            return false;
        }
        click(clearAll);
        return true;
    }

    public boolean clearAllVisible() {
        return isDisplayedNow(clearAll);
    }

    // ---- pagination (native page-size <select>, Previous / numbered / Next buttons) ------------

    public boolean hasPageSizeControl() {
        return isDisplayed(pageSizeSelect, 3);
    }

    public void pageSize(int n) {
        new org.openqa.selenium.support.ui.Select(visible(pageSizeSelect)).selectByVisibleText(n + " / page");
    }

    public String pageSizeShown() {
        return new org.openqa.selenium.support.ui.Select(visible(pageSizeSelect)).getFirstSelectedOption().getText().trim();
    }

    public boolean gotoPage(int n) {
        By b = By.xpath("//button[@aria-label='Previous page']/following-sibling::button[normalize-space()='" + n + "']");
        if (!isDisplayed(b, 3)) {
            return false;
        }
        click(b);
        return true;
    }

    public boolean isPreviousEnabled() {
        return visible(previousPage).isEnabled();
    }

    public boolean isNextEnabled() {
        return visible(nextPage).isEnabled();
    }

    public void clickNext() {
        click(nextPage);
    }

    public void clickPrevious() {
        click(previousPage);
    }

    /** Number of numbered page buttons shown. */
    public int pageButtons() {
        return driver.findElements(By.xpath("//button[@aria-label='Previous page']/following-sibling::button[number(normalize-space()) = number(normalize-space())]")).size();
    }

    // ---- sorting and visible columns ---------------------------------------------------------------

    /** Click a sortable header (Company, POC Name, Client Type, Country, Status): ascending, descending, default. */
    public void sortBy(String header) {
        click(By.xpath("//th[normalize-space()=" + Xp.literal(header) + "]"));
    }

    public java.util.List<String> headerTexts() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (WebElement th : driver.findElements(By.xpath("//thead//th"))) {
            out.add(th.getText().trim());
        }
        return out;
    }

    /** Opens the Columns menu, toggles the named column and closes the menu. */
    public void toggleColumn(String name) {
        click(columnsButton);
        click(By.xpath("//*[@role='menuitemcheckbox'][normalize-space()=" + Xp.literal(name) + "]"));
        driver.findElement(By.tagName("body")).sendKeys(org.openqa.selenium.Keys.ESCAPE);
        waitGone(By.xpath("//*[@role='menu']"));
    }

    /** Number of cells of the first data row (drops when a column is hidden). */
    public int firstRowCellCount() {
        List<WebElement> trs = driver.findElements(By.xpath("//tbody/tr"));
        return trs.isEmpty() ? 0 : trs.get(0).findElements(By.tagName("td")).size();
    }

    /** Number of list rows whose company cell equals the given name (after searching for it). */
    public int countRows(String company) {
        visible(heading);
        type(search, company);
        try {
            waitFor(timeout).until(ExpectedConditions.visibilityOfElementLocated(rowFor(company)));
        } catch (TimeoutException e) {
            return 0;
        }
        return driver.findElements(rowFor(company)).size();
    }

    /** The first row of the unfiltered list whose email is on yopmail.com (used as a known existing client). */
    public Row firstYopmailClient() {
        visible(heading);
        By anyRow = By.xpath("//tbody/tr");
        try {
            return waitFor(timeout).ignoring(org.openqa.selenium.StaleElementReferenceException.class, IndexOutOfBoundsException.class).until(d -> {
                for (WebElement tr : d.findElements(anyRow)) {
                    if (tr.findElements(By.tagName("td")).size() < 6) {
                        continue; // the empty-state / loading row of a list that is not (yet) filled has ONE cell: not a client
                    }
                    Row r = read(tr);
                    if (r.email != null && r.email.toLowerCase().endsWith("@yopmail.com") && !r.company.isEmpty()) {
                        return r;
                    }
                }
                return null;
            });
        } catch (TimeoutException e) {
            throw new AssertionError("No existing yopmail client found on the first page of the Clients list.");
        }
    }

    public Row waitForStatus(String company, String status, int seconds) {
        try {
            waitFor(seconds).pollingEvery(Duration.ofSeconds(2)).until(d -> {
                Row r = findClient(company);
                return status.equalsIgnoreCase(r.status);
            });
        } catch (TimeoutException e) {
            // fall through to return the real state so the caller can report expected vs actual
        }
        return findClient(company);
    }

    private Row read(WebElement tr) {
        List<WebElement> td = tr.findElements(By.tagName("td"));
        Row r = new Row();
        List<WebElement> inner = td.get(0).findElements(By.xpath(".//div[contains(@class,'truncate')]"));
        r.company = inner.size() > 0 ? inner.get(0).getText().trim() : td.get(0).getText().trim();
        r.email = inner.size() > 1 ? inner.get(1).getText().trim() : "";
        r.poc = td.get(1).getText().trim();
        r.type = td.get(2).getText().trim();
        r.country = td.get(3).getText().trim();
        r.mobile = td.get(4).getText().trim();
        r.status = td.get(5).getText().trim();
        return r;
    }
}
