package pages;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import base.BasePage;
import utils.TableReader;
import utils.Xp;

/** Admin side: sidebar Employees > EOR ("Employees" list, Bulk Upload window, search, an employee's profile with Verify). */
public class EorEmployeesPage extends BasePage {

    private final By employeesMenu = By.xpath("//button[normalize-space()='Employees']");
    private final By eorLink = By.xpath("//a[@href='/employees']");
    private final By heading = By.xpath("//h1[normalize-space()='Employees']");
    private final By bulkUpload = By.xpath("//button[normalize-space()='Bulk Upload']");
    private final By search = By.xpath("//input[@placeholder='Search by name, code, or email']");
    private final By verify = By.xpath("//button[normalize-space()='Verify'] | //*[@role='button' and normalize-space()='Verify']");
    private final AorDialog dialog;

    public EorEmployeesPage(WebDriver driver) {
        super(driver);
        this.dialog = new AorDialog(driver);
    }

    public AorDialog dialog() {
        return dialog;
    }

    public boolean sidebarVisible() {
        return isDisplayed(employeesMenu, timeout);
    }

    /** Sidebar Employees > EOR: the list "Employees" is shown with its rows. */
    public void open() {
        if (!isDisplayed(eorLink, 1)) {
            click(employeesMenu);
        }
        click(eorLink);
        visible(heading);
        waitFor(timeout).until(d -> !TableReader.rows(d).isEmpty() || !TableReader.showing(d).isEmpty());
    }

    public void openBulkUpload() {
        click(bulkUpload);
        dialog.waitOpen("Bulk Upload Employees");
    }

    public void attach(Path workbook) {
        dialog.attach(workbook);
    }

    public AorDialog.Answer clickUploadAndWait(int seconds) {
        return dialog.clickAndWait("Upload", seconds);
    }

    /** Types the text into the search box and waits until the list shows the result (every row contains the text, or nothing is found). */
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
            boolean allMatch = !rows.isEmpty() && rows.stream().allMatch(r -> String.join(" ", r.values()).toLowerCase(Locale.ROOT).contains(wanted));
            String now = TableReader.fingerprint(driver);
            stable = now.equals(last) ? stable + 1 : 0;
            last = now;
            if ((allMatch && stable >= 2) || (rows.isEmpty() && stable >= 6)) {
                break;
            }
        }
        return rows;
    }

    /** The value of a column of the row of this e-mail address ("" when there is no such row). */
    public String cell(String email, String headerPart) {
        for (Map<String, String> r : TableReader.rows(driver)) {
            if (String.join(" ", r.values()).toLowerCase(Locale.ROOT).contains(email.toLowerCase(Locale.ROOT))) {
                return TableReader.column(r, headerPart);
            }
        }
        return "";
    }

    /**
     * Opens the profile of the employee. An active employee has the eye button (title "View") in its row; before that (a candidate who still has to be verified)
     * the row has the menu "Actions" with the entry "View".
     */
    public void openProfile(String email) {
        String row = "//tr[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')," + Xp.literal(email.toLowerCase(Locale.ROOT)) + ")]";
        visible(By.xpath(row));
        By eye = By.xpath("(" + row + "//button[@title='View'])[1]");
        By actions = By.xpath("(" + row + "//button[@title='Actions'])[1]");
        waitFor(timeout).until(d -> !d.findElements(eye).isEmpty() || !d.findElements(actions).isEmpty());
        if (!driver.findElements(eye).isEmpty()) {
            click(eye);
            return;
        }
        click(actions);
        click(By.xpath("//*[(@role='menuitem' or self::button or self::div) and normalize-space()='View']"));
    }

    public boolean verifyOffered() {
        return isDisplayed(verify, timeout);
    }

    /** Clicks Verify on the profile and waits until the application is back on the list. */
    public void clickVerify() {
        click(verify);
        // some versions ask for a confirmation
        By yes = By.xpath("//div[@role='dialog']//button[normalize-space()='Yes' or normalize-space()='Confirm' or normalize-space()='Verify']");
        if (isDisplayed(yes, 3)) {
            click(yes);
        }
        waitFor(timeout).until(d -> d.findElements(verify).isEmpty() || d.getCurrentUrl().endsWith("/employees"));
    }

    public String bodyTextNow() {
        return bodyText();
    }

    public WebElement firstRow() {
        return driver.findElements(By.xpath("//table//tbody/tr")).stream().findFirst().orElse(null);
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms); // documented: waiting for a list to re-render after a search
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
