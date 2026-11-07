package pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import base.BasePage;

/** "Set a new password" dialog shown on a client's first login. */
public class PasswordResetPage extends BasePage {

    private final By dialog = By.xpath("//div[@role='dialog'][.//h2[normalize-space()='Set a new password']]");
    private final By current = By.id("currentPassword");
    private final By newPassword = By.id("newPassword");
    private final By confirm = By.id("confirmNewPassword");
    private final By submit = By.xpath("//button[@type='submit' and normalize-space()='Update password']");

    public PasswordResetPage(WebDriver driver) {
        super(driver);
    }

    public boolean isDisplayed() {
        return isDisplayed(dialog, timeout);
    }

    /** Negative-test submit; a null value leaves the field empty. "Accepted" = the dialog went away. */
    public utils.FormFeedback.Outcome attempt(String currentPw, String newPw, String confirmPw) {
        if (currentPw != null) {
            type(current, currentPw);
        }
        if (newPw != null) {
            type(newPassword, newPw);
        }
        if (confirmPw != null) {
            type(confirm, confirmPw);
        }
        return utils.FormFeedback.submit(driver, 8, () -> click(submit), () -> !isDisplayedNow(dialog));
    }

    public boolean isOpenNow() {
        return isDisplayedNow(dialog);
    }

    /** Clears the three inputs so the next attempt starts empty. */
    public void clearAll() {
        for (By b : new By[] { current, newPassword, confirm }) {
            org.openqa.selenium.WebElement e = visible(b);
            e.sendKeys(org.openqa.selenium.Keys.chord(org.openqa.selenium.Keys.CONTROL, "a"), org.openqa.selenium.Keys.BACK_SPACE);
        }
    }

    public void changePassword(String temporary, String permanent) {
        type(current, temporary);
        type(newPassword, permanent);
        type(confirm, permanent);
        click(submit);
    }
}
