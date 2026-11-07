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
}
