package pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import base.BasePage;
import model.ClientData;

/** Client onboarding wizard - step 2: Points of Contact (Business, Finance, HR). */
public class PointOfContactPage extends BasePage {

    private final By continueSetup = By.xpath("//button[normalize-space()='Continue Setup']");
    private final By businessName = By.name("business.name");
    private final By continueButton = By.xpath("//button[@type='submit' and normalize-space()='Continue']");

    public PointOfContactPage(WebDriver driver) {
        super(driver);
    }

    /** A "Finish setting up your company -> Continue Setup" prompt appears between steps 1 and 2. */
    public boolean dismissContinueSetupPrompt() {
        if (isDisplayed(continueSetup, 8)) {
            try {
                click(continueSetup);
                waitGone(continueSetup);
            } catch (org.openqa.selenium.TimeoutException closedByItself) {
                // the dialog was already fading out (e.g. it had just been used on the portal home): nothing left to dismiss
            }
            return true;
        }
        return false;
    }

    public void waitUntilOpen() {
        visible(businessName);
    }

    public void fill(ClientData d) {
        fillSection("business", d.business);
        fillSection("finance", d.finance);
        fillSection("hr", d.hr);
    }

    private void fillSection(String prefix, ClientData.Contact c) {
        type(By.name(prefix + ".name"), c.name);
        type(By.name(prefix + ".designation"), c.designation);
        type(By.name(prefix + ".email"), c.email);
        type(By.name(prefix + ".contact_no"), c.phone);
    }

    /** Sets one contact field by form name, e.g. ("finance", "email"); empty string clears it. */
    public void setField(String prefix, String field, String value) {
        By by = By.name(prefix + "." + field);
        org.openqa.selenium.WebElement e = visible(by);
        scrollIntoView(by);
        e.sendKeys(org.openqa.selenium.Keys.chord(org.openqa.selenium.Keys.CONTROL, "a"), org.openqa.selenium.Keys.BACK_SPACE);
        if (value != null && !value.isEmpty()) {
            e.sendKeys(value);
        }
    }

    /** The wizard's "Back" button (bottom-left of steps 2 and 3). Returns false when it is not shown. */
    public boolean clickBack() {
        By back = By.xpath("//button[normalize-space()='Back']");
        if (!isDisplayed(back, 5)) {
            return false;
        }
        scrollIntoView(back);
        click(back);
        return true;
    }

    public boolean isOpen() {
        return isDisplayedNow(businessName);
    }

    public utils.FormFeedback.Outcome continueAttempt() {
        return utils.FormFeedback.submit(driver, 8, this::clickContinue, () -> !isOpen());
    }

    public String read(String prefix, String field) {
        return value(By.name(prefix + "." + field));
    }

    public void clickContinue() {
        scrollIntoView(continueButton);
        click(continueButton);
    }
}
