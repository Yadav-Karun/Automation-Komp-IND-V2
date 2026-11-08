package pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import base.BasePage;

/** Landing page for admin / EOR users after sign-in. */
public class AdminHomePage extends BasePage {

    private final By clientsLink = By.xpath("//a[@href='/clients']");
    private final By profile = By.xpath("//button[.//*[contains(normalize-space(),'Online')]]");

    public AdminHomePage(WebDriver driver) {
        super(driver);
    }

    public boolean isLoaded() {
        return isDisplayed(clientsLink, timeout) && !urlContains("/login");
    }

    public String signedInAs() {
        return isDisplayed(profile, 3) ? text(profile).replace("\n", " | ") : "(profile not shown)";
    }

    /**
     * Opens the profile menu with a REAL click (it opens on pointer events, a script click does nothing), clicks its Sign out / Log out item and confirms the
     * 'Log out?' dialog. True when the login page (password field) is shown afterwards. The menu items found are listed when none matches.
     */
    public boolean signOut() {
        driver.findElement(By.xpath("(//button[contains(normalize-space(.),'@')])[last()]")).click();
        try {
            waitFor(5).until(d -> !d.findElements(By.xpath("//*[@role='menu']")).isEmpty());
        } catch (org.openqa.selenium.TimeoutException e) {
            throw new AssertionError("The profile menu did not open after clicking the profile button.");
        }
        java.util.List<String> seen = new java.util.ArrayList<>();
        By items = By.xpath("//*[@role='menuitem' or @role='menu']//*[self::div or self::button or self::a or self::span]");
        for (org.openqa.selenium.WebElement e : driver.findElements(items)) {
            String t = e.getText().trim();
            if (!t.isEmpty()) {
                seen.add(t.replace("\n", " "));
            }
            if (t.matches("(?i)(sign ?out|log ?out)")) {
                e.click();
                By confirm = By.xpath("//div[@role='dialog']//button[normalize-space()='Log out']");
                try {
                    waitFor(5).until(org.openqa.selenium.support.ui.ExpectedConditions.elementToBeClickable(confirm));
                    driver.findElement(confirm).click();
                } catch (org.openqa.selenium.TimeoutException noDialog) {
                    // signed out without a confirmation
                }
                try {
                    waitFor(10).until(d -> !d.findElements(By.id("password")).isEmpty());
                    return true;
                } catch (org.openqa.selenium.TimeoutException e2) {
                    return false;
                }
            }
        }
        throw new AssertionError("No 'Sign out' / 'Log out' item in the profile menu. Items seen: " + seen);
    }
}
