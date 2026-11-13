package base;

import java.time.Duration;
import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.ElementNotInteractableException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import utils.ConfigManager;
import utils.Xp;

/** Shared, wait-based helpers. No Thread.sleep: everything polls an explicit condition. */
public abstract class BasePage {

    protected final WebDriver driver;
    protected final int timeout;

    protected BasePage(WebDriver driver) {
        this.driver = driver;
        this.timeout = ConfigManager.getInt("explicit.wait.seconds", 20);
    }

    protected WebDriverWait waitFor(int seconds) {
        return new WebDriverWait(driver, Duration.ofSeconds(seconds));
    }

    protected WebElement visible(By by) {
        return waitFor(timeout).until(ExpectedConditions.visibilityOfElementLocated(by));
    }

    protected boolean isDisplayed(By by, int seconds) {
        try {
            waitFor(seconds).until(ExpectedConditions.visibilityOfElementLocated(by));
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    protected boolean isDisplayedNow(By by) {
        List<WebElement> els = driver.findElements(by);
        return !els.isEmpty() && els.get(0).isDisplayed();
    }

    protected void waitGone(By by) {
        waitFor(timeout).until(ExpectedConditions.invisibilityOfElementLocated(by));
    }

    protected void click(By by) {
        try {
            waitFor(timeout).ignoring(StaleElementReferenceException.class).until(d -> {
                try {
                    List<WebElement> els = d.findElements(by);
                    if (els.isEmpty() || !els.get(0).isDisplayed() || !els.get(0).isEnabled()) {
                        return false;
                    }
                    utils.SmoothScroll.to(driver, els.get(0)); // the page glides to the button, so that the person watching sees what is clicked
                    els.get(0).click();
                    return true;
                } catch (ElementNotInteractableException e) {
                    return false;
                }
            });
        } catch (TimeoutException e) {
            throw new TimeoutException("Could not click element " + by + " within " + timeout + "s", e);
        }
    }

    protected void jsClick(By by) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].click()", driver.findElement(by));
    }

    protected void type(By by, String text) {
        WebElement e = visible(by);
        utils.SmoothScroll.to(driver, e);
        if (!valueOf(e).isEmpty()) {
            e.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        }
        e.sendKeys(text);
    }

    protected String valueOf(WebElement e) {
        String v = e.getAttribute("value");
        return v == null ? "" : v;
    }

    protected String value(By by) {
        return valueOf(visible(by));
    }

    protected String text(By by) {
        return visible(by).getText().trim();
    }

    protected String bodyText() {
        return driver.findElement(By.tagName("body")).getText();
    }

    protected void scrollIntoView(By by) {
        utils.SmoothScroll.to(driver, visible(by));
    }

    /** Radix select: open trigger, pick option by visible text, wait for the list to close. */
    protected void selectRadix(By trigger, String optionText) {
        click(trigger);
        By option = By.xpath("//div[@role='option' and normalize-space()=" + Xp.literal(optionText) + "]");
        click(option);
        waitGone(option);
    }

    protected void upload(By fileInput, String path) {
        driver.findElement(fileInput).sendKeys(path);
    }

    public boolean urlContains(String fragment) {
        return driver.getCurrentUrl().contains(fragment);
    }

    protected void waitUrlContains(String fragment) {
        waitFor(timeout).until(ExpectedConditions.urlContains(fragment));
    }

    protected void waitUrlNotContains(String fragment) {
        waitFor(timeout).until(ExpectedConditions.not(ExpectedConditions.urlContains(fragment)));
    }

    /** Label-anchored locator for form controls, e.g. the combobox that follows the label "Client Type". */
    protected By comboAfterLabel(String label) {
        return By.xpath("(//label[starts-with(normalize-space(.)," + Xp.literal(label) + ")]/following::button[@role='combobox'])[1]");
    }
}
