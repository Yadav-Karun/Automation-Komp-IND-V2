package pages;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import base.BasePage;

/** Client portal home (/portal). */
public class ClientPortalHomePage extends BasePage {

    private static final Pattern PROGRESS = Pattern.compile("(\\d+)%\\s*completed");

    private final By welcome = By.xpath("//h1[starts-with(normalize-space(),'Welcome')]");
    private final By start = By.xpath("//button[normalize-space()='Start']");

    public ClientPortalHomePage(WebDriver driver) {
        super(driver);
    }

    public String welcomeText() {
        return text(welcome);
    }

    public boolean isStartVisible() {
        return isDisplayed(start, timeout);
    }

    public void clickStart() {
        click(start);
        waitUrlContains("/portal/onboarding");
    }

    /** Company-onboarding progress shown after the first setup pass, or -1 if not shown. */
    public int progressPercent() {
        Matcher m = PROGRESS.matcher(bodyText());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    public void waitForProgressWidget() {
        visible(By.xpath("//*[contains(normalize-space(),'% completed')]"));
    }

    // ---- the tabs of the portal menu (Home, Employees, Projects, MSA) and what a tab page shows ------------------

    /** Clicks a tab of the portal menu (by clicking, not by URL: a full load of /portal/msa lands on /portal) and waits for the page heading. */
    public void openTab(String tab) {
        click(By.xpath("//a[starts-with(@href,'/portal')][normalize-space()=" + utils.Xp.literal(tab) + "]"));
        visible("Home".equals(tab) ? welcome : By.xpath("//main//h1[normalize-space()=" + utils.Xp.literal(tab) + "]"));
        waitStable();
    }

    /**
     * Waits until the content of the page stopped changing: a table appears after its heading and renders again when its data arrives, so reading it at
     * once meets elements that are replaced under our hands ("stale element"). Max 8 s.
     */
    private void waitStable() {
        java.util.List<String> last = null;
        long stableSince = System.currentTimeMillis();
        long end = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < end) {
            java.util.List<String> now;
            try {
                now = mainLines();
            } catch (org.openqa.selenium.StaleElementReferenceException rerendered) {
                now = null;
            }
            if (now != null && now.equals(last)) {
                if (System.currentTimeMillis() - stableSince > 900) {
                    return;
                }
            } else {
                last = now;
                stableSince = System.currentTimeMillis();
            }
            try {
                Thread.sleep(300); // documented wait: the page re-renders after its data arrives
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Reads again when the page re-rendered while it was being read (at most 4 tries). */
    private static <T> T reread(java.util.function.Supplier<T> read) {
        for (int attempt = 1;; attempt++) {
            try {
                return read.get();
            } catch (org.openqa.selenium.StaleElementReferenceException rerendered) {
                if (attempt >= 4) {
                    throw rerendered;
                }
            }
        }
    }

    /** The first heading of the page content. */
    public String heading() {
        return reread(() -> text(By.xpath("(//main//h1)[1]")));
    }

    /** Every non-empty line of the page content (main area), in reading order. */
    public java.util.List<String> mainLines() {
        return reread(() -> {
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (String l : text(By.tagName("main")).split("\\R")) {
                if (!l.isBlank()) {
                    lines.add(l.trim());
                }
            }
            return lines;
        });
    }

    /** The line right after the line that equals the label (e.g. the value under "MSA ID"), or null when the label is not on the page. */
    public String valueAfter(String label) {
        java.util.List<String> lines = mainLines();
        for (int i = 0; i < lines.size() - 1; i++) {
            if (lines.get(i).equalsIgnoreCase(label)) {
                return lines.get(i + 1);
            }
        }
        return null;
    }

    /** Column headers of the first table of the page (a column without a title, such as an action column, is left out). */
    public java.util.List<String> tableHeaders() {
        return reread(() -> {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (org.openqa.selenium.WebElement th : driver.findElements(By.xpath("//main//table[1]//thead//th"))) {
                String header = th.getText().trim();
                if (!header.isEmpty()) {
                    out.add(header);
                }
            }
            return out;
        });
    }

    /** Rows of the first table of the page: one list of cell texts per row (an empty table shows ONE row with the empty-state message). */
    public java.util.List<java.util.List<String>> tableRows() {
        return reread(() -> {
            java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
            for (org.openqa.selenium.WebElement tr : driver.findElements(By.xpath("//main//table[1]//tbody/tr"))) {
                java.util.List<String> cells = new java.util.ArrayList<>();
                for (org.openqa.selenium.WebElement td : tr.findElements(By.tagName("td"))) {
                    cells.add(td.getText().trim().replaceAll("\\s+", " "));
                }
                rows.add(cells);
            }
            return rows;
        });
    }

    // ---- profile menu (top right) and the "My Profile" dialog -------------------------------------------

    private final By profileButton = By.xpath("(//button[contains(normalize-space(.),'@')])[last()]");
    private final By myProfileItem = By.xpath("//*[@role='menuitem'][normalize-space()='My Profile']");
    private final By myProfileDialog = By.xpath("//*[@role='dialog'][.//h2[normalize-space()='My Profile']]");

    /** Text of the top-right profile button: contact name + e-mail (e.g. "RP Online Regression POC ... a@b.com"). */
    public String profileButtonText() {
        return text(profileButton).replace("\n", " ");
    }

    /** Opens the profile menu with a REAL click (the menu opens on pointer events) and then 'My Profile'. */
    public void openMyProfile() {
        click(profileButton);
        click(myProfileItem);
        visible(myProfileDialog);
    }

    /** Everything the dialog shows: company name, 'Client Console', e-mail, phone (one item per line). */
    public java.util.List<String> myProfileLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String l : text(myProfileDialog).split("\\R")) {
            if (!l.isBlank()) {
                lines.add(l.trim());
            }
        }
        return lines;
    }

    public void closeMyProfile() {
        driver.findElement(By.tagName("body")).sendKeys(org.openqa.selenium.Keys.ESCAPE);
        waitGone(myProfileDialog);
    }
}
