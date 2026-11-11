package pages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import base.BasePage;
import utils.TableReader;

/**
 * Sidebar Employees > AOR (/aor-employees): the list "AOR Agents" and its "Bulk Upload" window. Verified on the sandbox (2026-11-10): the list has the tabs
 * All / In-Progress / Active / Inactive Agents, a search box "Search by name or ID", the filters Client and Project, and the columns AGENT (name and id), STATE, CLIENT, STATUS.
 * The window "Bulk Upload Agents" has "Download template", a file box (.xlsx, .xls, .csv), Cancel and Upload.
 */
public class AorAgentsPage extends BasePage {

    private final By employeesMenu = By.xpath("//button[normalize-space()='Employees']");
    private final By aorLink = By.xpath("//a[@href='/aor-employees']");
    private final By heading = By.xpath("//h1[normalize-space()='AOR Agents']");
    private final By bulkUpload = By.xpath("//button[normalize-space()='Bulk Upload']");
    private final By search = By.xpath("//input[@placeholder='Search by name or ID']");

    private final AorDialog dialog;

    public AorAgentsPage(WebDriver driver) {
        super(driver);
        this.dialog = new AorDialog(driver);
    }

    public AorDialog dialog() {
        return dialog;
    }

    /** True when the sidebar of the signed-in AOR user is shown (Employees is there). */
    public boolean sidebarVisible() {
        return isDisplayed(employeesMenu, timeout);
    }

    /** Sidebar Employees > AOR: the list "AOR Agents" is shown. */
    public void open() {
        if (!isDisplayed(aorLink, 1)) {
            click(employeesMenu);
        }
        click(aorLink);
        visible(heading);
    }

    public boolean isOpen() {
        return isDisplayedNow(heading);
    }

    public void openBulkUpload() {
        click(bulkUpload);
        dialog.waitOpen("Bulk Upload Agents");
    }

    public void attach(Path workbook) {
        dialog.attach(workbook);
    }

    /** Clicks Upload and waits for the answer of the application (see {@link AorDialog#clickAndWait}). */
    public AorDialog.Answer clickUploadAndWait(int seconds) {
        return dialog.clickAndWait("Upload", seconds);
    }

    // ------------------------------------------------------------------ the list

    /** Types into "Search by name or ID" and waits until the list shows the result (every row contains the text, or nothing is found). */
    public List<Map<String, String>> search(String text) {
        type(search, text);
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        String last = null;
        long stableSince = System.currentTimeMillis();
        long end = System.currentTimeMillis() + 20_000;
        List<Map<String, String>> rows = List.of();
        while (System.currentTimeMillis() < end) {
            rows = TableReader.rows(driver);
            boolean match = !rows.isEmpty() && rows.stream().allMatch(r -> String.join(" ", r.values()).toLowerCase(Locale.ROOT).contains(wanted));
            boolean none = rows.isEmpty() && bodyText().toLowerCase(Locale.ROOT).contains("no agents");
            String fp = TableReader.fingerprint(driver);
            if ((match || none) && fp.equals(last)) {
                if (System.currentTimeMillis() - stableSince >= 1000) {
                    return rows;
                }
            } else {
                last = fp;
                stableSince = System.currentTimeMillis();
            }
            pause(300);
        }
        return rows;
    }

    // ------------------------------------------------------------------ the profile of one agent (Actions > View)

    /** Searches the agent, opens the "..." menu of its row and clicks "View" (never "Edit"); waits for the profile page. */
    public void openProfileOf(String name) {
        search(name);
        click(By.xpath("(//tbody/tr[contains(normalize-space(.)," + utils.Xp.literal(name) + ")]//button[@title='Actions'])[1]"));
        click(By.xpath("//*[@role='menuitem' and normalize-space()='View']"));
        visible(By.xpath("//*[normalize-space()='Back to AOR Agents']"));
        pause(800);
    }

    /** Clicks one of the five tabs of the profile (Basic Details, Personal Info, Agent Bank Details, Identification Information, Area Manager/Supervisor). */
    public void selectProfileTab(String tab) {
        click(By.xpath("(//*[normalize-space()=" + utils.Xp.literal(tab) + "]/ancestor::*[self::button or @role='tab' or @role='button'][1])[1]"));
        pause(900);
    }

    /** The text of the whole profile page as lines (header and the open tab). */
    public List<String> profileLines() {
        String t = String.valueOf(((org.openqa.selenium.JavascriptExecutor) driver).executeScript("return document.body.innerText;"));
        return java.util.Arrays.stream(t.split("\\R")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    /** The value that stands under the label (the label is a line of its own, upper case), or null when the label is not on the page. */
    public static String valueOf(List<String> lines, String label) {
        for (int i = 0; i < lines.size() - 1; i++) {
            if (lines.get(i).equalsIgnoreCase(label)) {
                return lines.get(i + 1);
            }
        }
        return null;
    }

    /** Leaves the profile: "Back to AOR Agents". */
    public void backToList() {
        click(By.xpath("//*[normalize-space()='Back to AOR Agents']"));
        visible(heading);
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
