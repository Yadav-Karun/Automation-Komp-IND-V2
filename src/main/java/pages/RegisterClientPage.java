package pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import base.BasePage;
import model.ClientData;

/** Admin > Clients > Register Client form. */
public class RegisterClientPage extends BasePage {

    private final By legalName = By.name("organization_name");
    private final By clientType = comboAfterLabel("Client Type");
    private final By country = comboAfterLabel("Country");
    private final By pocName = By.name("poc_name");
    private final By email = By.name("email");
    private final By mobile = By.name("mobile_number");
    private final By submit = By.xpath("//button[@type='submit' and normalize-space()='Register Client']");

    public RegisterClientPage(WebDriver driver) {
        super(driver);
    }

    public void fill(ClientData d) {
        type(legalName, d.legalName);
        selectRadix(clientType, d.clientType);
        selectRadix(country, d.country);
        type(pocName, d.pocName);
        type(email, d.email);
        type(mobile, d.mobile);
    }

    /** Negative-test fill: a null field is left untouched (empty). Typing is real keyboard input, nothing is sanitised. */
    public void fillPartial(ClientData d) {
        if (d.legalName != null) {
            type(legalName, d.legalName);
        }
        if (d.clientType != null) {
            selectRadix(clientType, d.clientType);
        }
        if (d.country != null) {
            selectRadix(country, d.country);
        }
        if (d.pocName != null) {
            type(pocName, d.pocName);
        }
        if (d.email != null) {
            type(email, d.email);
        }
        if (d.mobile != null) {
            type(mobile, d.mobile);
        }
    }

    public void submit() {
        click(submit);
    }

    /** Two clicks in the same tick (what an impatient double-click does before the UI can disable the button). */
    public void submitTwiceImmediately() {
        waitFor(timeout).until(d -> driver.findElement(submit).isEnabled());
        ((org.openqa.selenium.JavascriptExecutor) driver)
                .executeScript("var b=arguments[0]; b.click(); b.click();", driver.findElement(submit));
    }

    public void waitUntilOpen() {
        waitUrlContains("/clients/register");
        visible(legalName);
    }

    /** True while the Register Client page (/clients/register) is still shown. */
    public boolean isOpen() {
        return driver.getCurrentUrl().contains("/clients/register");
    }

    public String mobileValue() {
        return value(mobile);
    }

    public String legalNameValue() {
        return value(legalName);
    }

    public boolean isClosed() {
        return driver.findElements(legalName).isEmpty() || !driver.findElement(legalName).isDisplayed();
    }

    public void waitClosed() {
        waitGone(legalName);
    }
}
