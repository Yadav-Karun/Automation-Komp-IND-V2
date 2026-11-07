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
