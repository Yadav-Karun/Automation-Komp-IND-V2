package pages;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import base.BasePage;
import utils.SecretMasker;
import utils.Xp;

/**
 * The Yopmail inbox of one EOR employee, in its own browser tab: the "Letter of intent" mail (its button "Accept Offer" opens a page that says "Congratulations")
 * and the "Candidate Login" mail (the password of the employee and the link "Access Portal").
 */
public class EorMailbox extends BasePage {

    private static final Pattern PASSWORD = Pattern.compile("(?im)password\\s*:\\s*(\\S+)");

    private final YopmailPage inbox;
    private final By inboxFrame = By.id("ifinbox");
    private final By mailFrame = By.id("ifmail");

    /** The mail is read in its own browser window ({@link utils.MailBrowser}), never in the window of the test; the argument is only the browser of the test. */
    public EorMailbox(WebDriver testBrowser) {
        super(utils.MailBrowser.get());
        this.inbox = new YopmailPage(driver);
    }

    /** The browser window of the mail (for a picture of the mail). */
    public WebDriver mailDriver() {
        return driver;
    }

    public void open(String address) {
        inbox.openInbox(address.trim().toLowerCase(java.util.Locale.ROOT));
        waitForHumanCheck();
    }

    /**
     * Yopmail sometimes puts a window "Please confirm you're human to continue" (Cloudflare, "Verifying...") over the inbox: it clears by itself after a few seconds. The inbox is used
     * only when its refresh button can be clicked (up to 300 seconds, -Dyopmail.human.wait.seconds).
     */
    private void waitForHumanCheck() {
        int seconds = utils.ConfigManager.getInt("yopmail.human.wait.seconds", 300);
        long end = System.currentTimeMillis() + seconds * 1000L;
        boolean told = false;
        By refresh = By.id("refresh");
        while (System.currentTimeMillis() < end) {
            try {
                List<WebElement> r = driver.findElements(refresh);
                if (!r.isEmpty() && r.get(0).isDisplayed() && driver.findElements(By.xpath("//*[contains(normalize-space(.),\"confirm you're human\")]")).stream().noneMatch(WebElement::isDisplayed)) {
                    return;
                }
                if (!told) {
                    told = true;
                    utils.Log.warn("Yopmail shows \"Please confirm you're human\". If it does not clear by itself, click the check in the browser window (the run waits up to " + seconds + " seconds).");
                }
                Thread.sleep(2000); // documented: the check of the mail provider clears by itself or is clicked by the person watching
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                // the page is still loading
            }
        }
        throw new AssertionError("The mail provider (Yopmail) keeps asking to confirm that this is a human (\"Please confirm you're human to continue\") and did not open the inbox within " + seconds + " seconds.");
    }

    public void close() {
        inbox.closeAndReturn();
    }

    private void openMail(String subjectPart, int seconds) {
        inbox.waitForMail(subjectPart, seconds, 4);
        driver.switchTo().defaultContent();
        driver.switchTo().frame(driver.findElement(inboxFrame));
        click(By.xpath("(//*[contains(normalize-space(text())," + Xp.literal(subjectPart) + ")])[1]"));
        driver.switchTo().defaultContent();
        driver.switchTo().frame(driver.findElement(mailFrame));
        visible(By.tagName("body"));
    }

    /** Opens the "Letter of intent" mail and clicks "Accept Offer"; returns what the page that opens says (it should say "Congratulations"). */
    public String acceptLetterOfIntent(int seconds) {
        openMail("Letter of intent", seconds);
        String mailText = bodyText();
        String mainWindow = driver.getWindowHandle();
        List<String> before = new ArrayList<>(driver.getWindowHandles());
        click(By.xpath("//a[normalize-space()='Accept Offer'] | //a[contains(normalize-space(.),'Accept')]"));
        waitFor(timeout).until(d -> d.getWindowHandles().size() > before.size());
        String opened = null;
        for (String h : driver.getWindowHandles()) {
            if (!before.contains(h)) {
                opened = h;
            }
        }
        driver.switchTo().window(opened);
        String answer;
        try {
            visible(By.xpath("//*[contains(normalize-space(.),'Congratulations')]"));
        } finally {
            answer = bodyText();
        }
        driver.close();
        driver.switchTo().window(mainWindow);
        driver.switchTo().defaultContent();
        Matcher subject = Pattern.compile("Letter of intent[^\n\r?]*").matcher(mailText);
        String name = subject.find() ? subject.group().trim() : "Letter of intent";
        return "The mail \"" + name + "\" arrived; the page that its button \"Accept Offer\" opens says: \"" + shorten(answer) + "\"";
    }

    /** Opens the "Candidate Login" mail and reads the password from it. */
    public String readLoginPassword(int seconds) {
        openMail("Candidate Login", seconds);
        String body = bodyText();
        Matcher m = PASSWORD.matcher(body);
        if (!m.find()) {
            throw new AssertionError("The mail \"Candidate Login\" does not contain a password. It says: \"" + shorten(body) + "\"");
        }
        String password = m.group(1).trim();
        SecretMasker.register(password);
        return password;
    }

    /** Opens the mail whose subject contains the text and returns its text (the mail stays open: its links can be read with {@link #links()}). */
    public String readMail(String subjectPart, int seconds) {
        openMail(subjectPart, seconds);
        return bodyText();
    }

    /** The links (text -> address) of the mail that is open. */
    public List<String> links() {
        List<String> out = new ArrayList<>();
        for (WebElement a : driver.findElements(By.tagName("a"))) {
            out.add(a.getText().trim() + " -> " + a.getAttribute("href"));
        }
        return out;
    }

    /** The link "Access Portal" of the mail that is open. */
    public String portalLink() {
        List<WebElement> l = driver.findElements(By.xpath("//a[normalize-space()='Access Portal']"));
        return l.isEmpty() ? "" : l.get(0).getAttribute("href");
    }

    public String mailText() {
        return bodyText();
    }

    private static String shorten(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() > 220 ? t.substring(0, 220) + "..." : t;
    }
}
