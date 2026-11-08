package pages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.Select;

import base.BasePage;
import utils.Xp;

/**
 * Sidebar "Projects" > AOR / EOR: the project listings (/projects/aor, /projects/eor). Verified on the sandbox (2026-11-08):
 * the sidebar group "Projects" expands into the two links; a listing has the tabs All / Active / Expired (AOR) or Inactive (EOR), a search box
 * "Search by project name or code", a Client filter (searchable popover, "Clear all"), a table (AOR: project name, code, client, start date, status;
 * EOR: + work location), Previous / Next, and a native page-size select. Navigation goes through the sidebar (a full page reload of a deep link can drop
 * the session).
 * <p>
 * Every action that re-renders the table waits until the table really CHANGED and then agrees with its "Showing a-b of N" text: the table keeps the old rows
 * while the next page / tab / filter is fetched, and a page-size change keeps the page index (51-60 instead of 1-10). {@link #allRows()} therefore starts on
 * page 1 itself.
 */
public class ProjectsPage extends BasePage {

    private static final Pattern SHOWING = Pattern.compile("Showing\\s+(\\d+)\\D+(\\d+)\\s+of\\s+(\\d+)");

    /** The data rows of the table: header (upper case) -> cell text, read in ONE script call (no stale elements, fast). */
    private static final String ROWS_JS = "const h=Array.from(document.querySelectorAll('thead th')).map(t=>(t.innerText||'').trim().toUpperCase());"
            + "return Array.from(document.querySelectorAll('tbody tr')).filter(r=>r.cells.length>=5).map(r=>{const o={};"
            + "Array.from(r.cells).forEach((c,i)=>{o[h[i]||('COLUMN '+(i+1))]=(c.innerText||'').replace(/\\s+/g,' ').trim();});return o;});";
    /** What the table shows right now: the "Showing" text and the first cell of every row (a change of page / tab / filter / sort changes it). */
    private static final String STATE_JS = "const s=(document.body.innerText.match(/Showing[^\\n]*/)||[''])[0];"
            + "return s+'|'+Array.from(document.querySelectorAll('tbody tr')).map(r=>r.cells[0]?r.cells[0].innerText:'').join('~')"
            + "+(document.body.innerText.includes('No projects found')?'|empty':'');";

    private final By projectsMenu = By.xpath("//button[normalize-space()='Projects']");
    private final By addProject = By.xpath("//button[normalize-space()='Add Project']");
    private final By search = By.xpath("//input[@placeholder='Search by project name or code']");
    private final By nextPage = By.xpath("//button[@aria-label='Next page']");
    private final By prevPage = By.xpath("//button[@aria-label='Previous page']");
    private final By pageSize = By.xpath("//select[option[contains(normalize-space(),'/ page')]]");
    private final By clearAll = By.xpath("//button[normalize-space()='Clear all']");

    private boolean searchApplied;

    public ProjectsPage(WebDriver driver) {
        super(driver);
    }

    // ------------------------------------------------------------------ navigation

    /** True when the sidebar offers the "Projects" tab (the signed-in console is loaded). */
    public boolean menuVisible() {
        return isDisplayed(projectsMenu, timeout);
    }

    /** Same, without waiting: false on the login page or while the console is still loading. */
    public boolean menuVisibleNow() {
        try {
            return isDisplayedNow(projectsMenu);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private By typeLink(String type) {
        return By.xpath("//a[@href='/projects/" + type.toLowerCase(Locale.ROOT) + "']");
    }

    private By heading(String type) {
        return By.xpath("//h1[normalize-space()='" + type + " Projects']");
    }

    /** Clicks the sidebar tab "Projects" (expands its AOR / EOR entries; a group that is already open is left open). */
    public void openProjectTab() {
        if (!isDisplayed(typeLink("AOR"), 1)) {
            click(projectsMenu);
        }
        visible(typeLink("AOR"));
        visible(typeLink("EOR"));
    }

    /** Opens the AOR or EOR listing through the sidebar and waits for its heading. */
    public void openListing(String type) {
        openProjectTab();
        click(typeLink(type));
        visible(heading(type));
        visible(addProject);
        settle();
    }

    public boolean listingOpen(String type) {
        return isDisplayedNow(heading(type));
    }

    public void clickAddProject() {
        click(addProject);
    }

    public String headingText(String type) {
        return text(heading(type));
    }

    /** "Showing 1-3 of 3 projects" (or "" when the page shows no such text). */
    public String listSummary() {
        List<WebElement> s = driver.findElements(By.xpath("//*[starts-with(normalize-space(text()),'Showing')]"));
        return s.isEmpty() ? "" : s.get(0).getText().replace("\n", " ").trim();
    }

    /** N of "Showing 1-5 of N projects"; -1 when the page shows no such text (the empty state). */
    public int totalCount() {
        Matcher m = SHOWING.matcher(listSummary());
        return m.find() ? Integer.parseInt(m.group(3)) : -1;
    }

    /** True when the listing shows its empty state ("No projects found - Try adjusting your filters ..."). */
    public boolean emptyStateShown() {
        return bodyText().contains("No projects found");
    }

    // ------------------------------------------------------------------ reading the table

    /** The rows of the current page as header -> cell text (headers in upper case; empty-state / loading rows are left out). */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> rows() {
        Object o = ((JavascriptExecutor) driver).executeScript(ROWS_JS);
        List<Map<String, String>> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object r : (List<Object>) o) {
                out.add(new LinkedHashMap<>((Map<String, String>) r));
            }
        }
        return out;
    }

    /** Every row of the listing, ALL pages (page size 50, from page 1, "Next" until it is disabled). */
    public List<Map<String, String>> allRows() {
        if (isDisplayed(pageSize, 2)) {
            selectPageSize(50);
        }
        firstPage();
        List<Map<String, String>> out = new ArrayList<>(rows());
        for (int page = 1; page < 40 && nextEnabled(); page++) {
            clickNext();
            out.addAll(rows());
        }
        firstPage(); // leave the listing on page 1 (the screenshot of the step shows the start of the list, not its last page)
        return out;
    }

    /** All project names -> codes of the listing (every page): used to pick a name / code nobody used yet. */
    public Map<String, String> allNamesAndCodes() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, String> row : allRows()) {
            out.put(column(row, "PROJECT NAME"), column(row, "PROJECT CODE"));
        }
        return out;
    }

    /** The value of a column, matched by part of its header ("NAME", "CODE", "CLIENT", "START DATE", "STATUS", "WORK LOCATION"). */
    public static String column(Map<String, String> row, String headerPart) {
        String wanted = headerPart.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> e : row.entrySet()) {
            if (e.getKey().contains(wanted)) {
                return e.getValue();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ controls

    /** Clicks a tab of the listing (All / Active / Expired / Inactive). */
    public void clickTab(String tab) {
        String before = state();
        click(By.xpath("//button[normalize-space()=" + Xp.literal(tab) + "]"));
        afterChange(before);
    }

    public boolean hasTab(String tab) {
        return isDisplayedNow(By.xpath("//button[normalize-space()=" + Xp.literal(tab) + "]"));
    }

    public void setPageSize(int size) {
        visible(pageSize);
        selectPageSize(size);
    }

    private void selectPageSize(int size) {
        String before = state();
        new Select(driver.findElement(pageSize)).selectByVisibleText(size + " / page");
        afterChange(before);
    }

    /** Clicks a column header (sorts: first click ascending, second descending, third the original order). */
    public void clickHeader(String headerPart) {
        String before = state();
        click(By.xpath("//th[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),"
                + Xp.literal(headerPart.toUpperCase(Locale.ROOT)) + ")]"));
        afterChange(before);
    }

    /** Opens the Client filter, ticks one client, closes the popover. */
    public void filterByClient(String client) {
        String before = state();
        click(By.xpath("//button[contains(normalize-space(.),'Client') and (contains(normalize-space(.),'Any') or contains(normalize-space(.),'selected')"
                + " or contains(normalize-space(.),'Client '))][not(@role='combobox')]"));
        click(By.xpath("//*[@role='option' and @cmdk-item and normalize-space()=" + Xp.literal(client) + "]"));
        driver.switchTo().activeElement().sendKeys(Keys.ESCAPE);
        afterChange(before);
    }

    /** "Clear all" next to the filters (only offered while a filter or a search is active). */
    public void clearAll() {
        String before = state();
        click(clearAll);
        afterChange(before);
    }

    public boolean nextEnabled() {
        return enabled(nextPage);
    }

    public boolean prevEnabled() {
        return enabled(prevPage);
    }

    private boolean enabled(By by) {
        List<WebElement> b = driver.findElements(by);
        return !b.isEmpty() && b.get(0).isEnabled() && !"true".equals(b.get(0).getAttribute("aria-disabled"));
    }

    public void clickNext() {
        String before = state();
        click(nextPage);
        afterChange(before);
    }

    public void clickPrev() {
        String before = state();
        click(prevPage);
        afterChange(before);
    }

    /** Back to page 1 (Previous until it is disabled). */
    public void firstPage() {
        for (int i = 0; i < 40 && prevEnabled(); i++) {
            clickPrev();
        }
    }

    public void clearSearch() {
        String before = state();
        type(search, "");
        afterChange(before);
    }

    // ------------------------------------------------------------------ search

    /** True when the last {@link #searchProject} saw the RESULT of its search on the screen (every row contains the text, or the empty state). */
    public boolean searchApplied() {
        return searchApplied;
    }

    /**
     * Types the text into the search box and waits until the list shows the RESULT: every row contains the text (ignoring case and the spaces around it) or the
     * empty state, unchanged for 1 s (max 20 s). A slow search leaves the old, unfiltered list on the screen - the project we look for is in it too.
     */
    public void searchProject(String text) {
        type(search, text);
        String wanted = text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String last = null;
        long stableSince = System.currentTimeMillis();
        long end = System.currentTimeMillis() + 20_000;
        searchApplied = false;
        while (System.currentTimeMillis() < end) {
            List<Map<String, String>> rows = rows();
            boolean empty = rows.isEmpty() && emptyStateShown();
            boolean everyRowMatches = !rows.isEmpty();
            for (Map<String, String> r : rows) {
                String line = String.join(" ", r.values()).toLowerCase(Locale.ROOT);
                if (!line.contains(wanted)) {
                    everyRowMatches = false;
                }
            }
            String now = state();
            if ((everyRowMatches || empty) && now.equals(last)) {
                if (System.currentTimeMillis() - stableSince >= 1000) {
                    searchApplied = true;
                    settle();
                    return;
                }
            } else {
                last = now;
                stableSince = System.currentTimeMillis();
            }
            pause(300); // documented wait: the list re-renders after typing
        }
    }

    /**
     * Searches by the exact project name until the project is listed (a new project can need a moment before the list shows it: up to {@code attempts}
     * searches, 3 s apart). Returns the row whose project name equals the name exactly, or null when it never showed up.
     */
    public Map<String, String> findProject(String name, int attempts) {
        for (int i = 1; i <= attempts; i++) {
            searchProject(name);
            for (Map<String, String> row : rows()) {
                if (column(row, "PROJECT NAME").equals(name.trim())) {
                    return row;
                }
            }
            pause(3000);
        }
        return null;
    }

    // ------------------------------------------------------------------ waiting for the table

    private String state() {
        try {
            return String.valueOf(((JavascriptExecutor) driver).executeScript(STATE_JS));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Waits until the table changed (max 5 s - some actions legitimately change nothing), then until it is consistent and stable. */
    private void afterChange(String before) {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end && state().equals(before)) {
            pause(150);
        }
        settle();
    }

    /**
     * Waits until the table agrees with the "Showing a-b of N projects" text (rows = b - a + 1, or the empty state) and has not changed for 500 ms (max 10 s).
     */
    private void settle() {
        long end = System.currentTimeMillis() + 10_000;
        String last = null;
        long since = System.currentTimeMillis();
        while (System.currentTimeMillis() < end) {
            String now = state();
            int rows = rows().size();
            Matcher m = SHOWING.matcher(now);
            boolean consistent = m.find() ? rows == Integer.parseInt(m.group(2)) - Integer.parseInt(m.group(1)) + 1 : rows == 0 && now.endsWith("|empty");
            if (consistent && now.equals(last)) {
                if (System.currentTimeMillis() - since >= 500) {
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
