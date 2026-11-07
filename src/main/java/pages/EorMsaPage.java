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

    public void searchClient(String client) {
        type(search, client);
        try {
            waitFor(timeout).until(ExpectedConditions.visibilityOfElementLocated(row(client)));
        } catch (TimeoutException e) {
            throw new AssertionError("Client '" + client + "' not found in MSA list after searching for it.");
        }
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
