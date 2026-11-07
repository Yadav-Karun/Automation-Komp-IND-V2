package pages;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WindowType;

import base.BasePage;
import utils.ConfigManager;
import utils.SecretMasker;
import utils.Xp;

/** Yopmail inbox in its own browser tab. Credential-bearing text is masked before any screenshot. */
public class YopmailPage extends BasePage {

    private static final Pattern TEMP_PASSWORD = Pattern.compile("Temporary password:\\s*(\\S+)");

    private final By loginField = By.id("login");
    private final By checkInbox = By.cssSelector("#refreshbut button");
    private final By refresh = By.id("refresh");
    private final By inboxFrame = By.id("ifinbox");
    private final By mailFrame = By.id("ifmail");

    private String appWindow;
    private String yopmailWindow;

    public YopmailPage(WebDriver driver) {
        super(driver);
    }

    public void openInbox(String address) {
        appWindow = driver.getWindowHandle();
        driver.switchTo().newWindow(WindowType.TAB);
        yopmailWindow = driver.getWindowHandle();
        driver.get(ConfigManager.get("yopmail.url", "https://yopmail.com/en/"));
        type(loginField, address);
        driver.findElement(loginField).sendKeys(Keys.ENTER);
        try {
            waitFor(10).until(d -> d.getCurrentUrl().contains("/wm"));
        } catch (Exception e) {
            click(checkInbox);
            waitFor(timeout).until(d -> d.getCurrentUrl().contains("/wm"));
        }
    }

    /** Polls (refreshing) until a mail whose subject contains the fragment arrives, or the timeout expires. */
    public void waitForMail(String subjectContains, int timeoutSeconds, int intervalSeconds) {
        By mail = By.xpath("//*[contains(normalize-space(text())," + Xp.literal(subjectContains) + ")]");
        Instant end = Instant.now().plus(Duration.ofSeconds(timeoutSeconds));
        while (true) {
            driver.switchTo().defaultContent();
            driver.switchTo().frame(driver.findElement(inboxFrame));
            if (!driver.findElements(mail).isEmpty()) {
                return;
            }
            driver.switchTo().defaultContent();
            if (Instant.now().isAfter(end)) {
                throw new AssertionError("Email with subject containing '" + subjectContains
                        + "' not received within " + timeoutSeconds + "s.");
            }
            try {
                Thread.sleep(intervalSeconds * 1000L); // documented external-poll interval: mail delivery is async
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            click(refresh);
        }
    }

    public String openMailAndReadBody(String subjectContains) {
        driver.switchTo().defaultContent();
        driver.switchTo().frame(driver.findElement(inboxFrame));
        click(By.xpath("//*[contains(normalize-space(text())," + Xp.literal(subjectContains) + ")]"));
        driver.switchTo().defaultContent();
        driver.switchTo().frame(driver.findElement(mailFrame));
        visible(By.xpath("//*[contains(normalize-space(),'Hello')]"));
        return bodyText();
    }

    public String extractTemporaryPassword(String mailBody) {
        Matcher m = TEMP_PASSWORD.matcher(mailBody);
        if (!m.find()) {
            throw new AssertionError("Temporary password not found in the welcome email body.");
        }
        SecretMasker.register(m.group(1));
        return m.group(1);
    }

    /** First link in the mail pointing to the configured KOMP host. */
    public String findPortalLink() {
        String host = URI.create(ConfigManager.baseUrl()).getHost();
        for (var a : driver.findElements(By.tagName("a"))) {
            String href = a.getAttribute("href");
            if (href != null && href.contains(host)) {
                return href;
            }
        }
        throw new NoSuchElementException("No link to " + host + " found in the welcome email");
    }

    /**
     * Replace the password text in the DOM so the evidence screenshot cannot reveal it - only while masking is on
     * (-Dmask.secrets=true, or a production run); by default the temporary password stays visible in the screenshot.
     */
    public void maskForEvidence(String secret) {
        if (!utils.SecretMasker.enabled()) {
            // the password is further down in the mail (which scrolls inside its own frame): scroll it into view and outline it,
            // so the evidence screenshot shows it
            try {
                ((JavascriptExecutor) driver).executeScript(
                        "const s=arguments[0];const w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);"
                                + "let n;while(n=w.nextNode()){if(n.nodeValue.includes(s)){const e=n.parentElement;"
                                + "e.style.outline='3px solid #e53935';e.style.outlineOffset='3px';e.scrollIntoView({block:'center'});break;}}",
                        secret);
            } catch (RuntimeException ignored) {
                // evidence only: the journey must not fail because of it
            }
            driver.switchTo().defaultContent();
            return;
        }
        ((JavascriptExecutor) driver).executeScript(
                "const s=arguments[0];const w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);"
                        + "let n;while(n=w.nextNode()){if(n.nodeValue.includes(s)){n.nodeValue=n.nodeValue.split(s).join('••••••••');}}",
                secret);
        driver.switchTo().defaultContent();
    }

    public void closeAndReturn() {
        if (yopmailWindow != null) {
            driver.switchTo().window(yopmailWindow);
            driver.close();
            driver.switchTo().window(appWindow);
            yopmailWindow = null;
        }
    }
}
