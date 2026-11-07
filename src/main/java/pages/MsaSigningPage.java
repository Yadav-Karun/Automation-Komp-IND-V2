package pages;

import java.time.Duration;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;

import base.BasePage;
import utils.ConfigManager;

/** Client onboarding wizard - step 4: Sign MSA. */
public class MsaSigningPage extends BasePage {

    private final By proceed = By.xpath("//button[normalize-space()='Proceed to Sign/View MSA']");
    private final By preparing = By.xpath("//button[starts-with(normalize-space(),'Preparing')]");
    private final By viewer = By.xpath("//iframe[@title='Sign MSA']");
    private final By successDialog = By.xpath("//div[@role='dialog'][.//*[contains(normalize-space(),'Document sent and submitted successfully')]]");
    private final By done = By.xpath("//div[@role='dialog']//button[normalize-space()='Done']");

    public MsaSigningPage(WebDriver driver) {
        super(driver);
    }

    /** Step 4 is open once the Proceed button is visible. */
    public void waitUntilOpen() {
        waitFor(ConfigManager.getInt("msa.generation.timeout.seconds", 90)).until(d -> isDisplayedNow(proceed));
    }

    public boolean isProceedEnabled() {
        return driver.findElement(proceed).isEnabled();
    }

    /** MSA generation is asynchronous: wait (bounded) until the viewer iframe holds a generated document URL. */
    public boolean waitForGeneratedMsa() {
        int t = ConfigManager.getInt("msa.generation.timeout.seconds", 90);
        try {
            waitFor(t).pollingEvery(Duration.ofSeconds(1)).until(d -> {
                var els = d.findElements(viewer);
                return !els.isEmpty() && els.get(0).isDisplayed()
                        && String.valueOf(els.get(0).getAttribute("src")).startsWith("blob:");
            });
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    /** Fetches the viewer's blob/URL inside the page and returns "<mime>:<bytes>" (proves the PDF is retrievable). */
    public String viewerContentSummary() {
        Object r = ((JavascriptExecutor) driver).executeAsyncScript(
                "const done=arguments[arguments.length-1];const f=document.querySelector('iframe[title=\"Sign MSA\"]');"
                        + "if(!f||!f.src){done('no-src');return;}"
                        + "fetch(f.src).then(r=>r.blob()).then(b=>done(b.type+':'+b.size)).catch(e=>done('error:'+e));");
        return String.valueOf(r);
    }

    public void proceedToSign() {
        click(proceed);
    }

    /** Signing in the sandbox completes by itself; wait for the application's own success confirmation. */
    public String waitForSuccess() {
        int t = ConfigManager.getInt("msa.generation.timeout.seconds", 90);
        try {
            waitFor(t).pollingEvery(Duration.ofMillis(500)).until(d -> isDisplayedNow(successDialog));
        } catch (TimeoutException e) {
            throw new AssertionError("MSA signing success confirmation not shown within " + t + "s"
                    + (isDisplayedNow(preparing) ? " (button still 'Preparing...')." : "."));
        }
        return text(successDialog).replace("\n", " ");
    }

    public void clickDone() {
        click(done);
    }
}
