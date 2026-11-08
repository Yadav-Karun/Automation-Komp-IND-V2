package pages;

import java.time.Duration;

import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;

import base.BasePage;
import utils.Xp;

/** EOR portal > MSA & Contract > MSA (Contracts & MSA list). */
public class EorMsaPage extends BasePage {

    private final By menu = By.xpath("//button[normalize-space()='MSA & Contract']");
    private final By msaLink = By.xpath("//a[@href='/contracts']");
    private final By heading = By.xpath("//h1[normalize-space()='Contracts & MSA']");
    private final By search = By.xpath("//input[@placeholder='Search by client name']");
    private final By dialog = By.xpath("//div[@role='dialog'][.//h2[contains(normalize-space(),'Update client status')]]");
    private final By approve = By.xpath("//div[@role='dialog']//button[normalize-space()='Approve & Activate']");

    public EorMsaPage(WebDriver driver) {
        super(driver);
    }

    public void open() {
        if (!isDisplayed(msaLink, 2)) {
            click(menu);
        }
        click(msaLink);
        visible(heading);
    }

    private By row(String client) {
        return By.xpath("//tbody/tr[td[1][normalize-space()=" + Xp.literal(client) + "]]");
    }

    private boolean searchApplied;

    /** True when the last {@link #searchClient} saw the RESULT of its search on the screen (every shown row contains the searched name). */
    public boolean searchApplied() {
        return searchApplied;
    }

    /** "Showing 1-1 of 1 MSAs" (or "" when not shown). */
    public String listSummary() {
        java.util.List<org.openqa.selenium.WebElement> s = driver.findElements(By.xpath("//*[starts-with(normalize-space(text()),'Showing')]"));
        return s.isEmpty() ? "" : s.get(0).getText().replace("\n", " ").trim();
    }

    /**
     * Waits until the list shows the RESULT of the search (every data row contains the searched name, unchanged for 1 s, max 12 s): a slow search leaves
     * the old, unfiltered list on the screen - the client is in it too - and a screenshot taken then shows the wrong list.
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
                java.util.List<org.openqa.selenium.WebElement> rows = driver.findElements(By.xpath("//tbody/tr[count(td)>=5]"));
                StringBuilder sb = new StringBuilder();
                everyRowMatches = !rows.isEmpty();
                for (org.openqa.selenium.WebElement tr : rows) {
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

    public void searchClient(String client) {
        type(search, client);
        waitSearchApplied(client);
        try {
            waitFor(timeout).until(ExpectedConditions.visibilityOfElementLocated(row(client)));
        } catch (TimeoutException e) {
            throw new AssertionError("Client '" + client + "' not found in MSA list after searching for it.");
        }
    }

    /** Texts of the cells of the client's row: client, MSA id, start date, country, status (the action buttons are left out). */
    public java.util.List<String> rowTexts(String client) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.List<org.openqa.selenium.WebElement> cells = driver.findElement(row(client)).findElements(By.tagName("td"));
        for (int i = 0; i < 5; i++) {
            out.add(i < cells.size() ? cells.get(i).getText().trim() : "");
        }
        return out;
    }

    public String msaId(String client) {
        return driver.findElement(row(client)).findElement(By.xpath("./td[2]")).getText().trim();
    }

    public String startDate(String client) {
        return driver.findElement(row(client)).findElement(By.xpath("./td[3]")).getText().trim();
    }

    public String status(String client) {
        return driver.findElement(row(client)).findElement(By.xpath("./td[5]")).getText().trim();
    }

    public void clickRefresh(String client) {
        driver.findElement(row(client)).findElement(By.xpath(".//button[@title='Refresh sign status']")).click();
    }

    /** Clicks Refresh (re-clicking every ~10s) until the row shows the status, or the timeout expires. Returns last status. */
    public String refreshUntil(String client, String expected, int seconds) {
        clickRefresh(client);
        long lastClick = System.currentTimeMillis();
        try {
            waitFor(seconds).pollingEvery(Duration.ofSeconds(1)).until(d -> {
                if (expected.equalsIgnoreCase(status(client))) {
                    return true;
                }
                if (System.currentTimeMillis() - lastClick > 10_000) {
                    clickRefresh(client);
                }
                return false;
            });
        } catch (TimeoutException ignored) {
            // report the real state to the caller
        }
        return status(client);
    }

    public void openStatusDialog(String client) {
        driver.findElement(row(client)).findElement(By.xpath(".//button[normalize-space()='IN REVIEW']")).click();
        visible(dialog);
    }

    public String dialogText() {
        return text(dialog).replace("\n", " ");
    }

    public void approveAndActivate() {
        click(approve);
        waitGone(dialog);
    }
}
