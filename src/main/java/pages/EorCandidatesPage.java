package pages;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;

import base.BasePage;
import utils.ConfigManager;
import utils.TableReader;
import utils.Xp;

/** Admin side: sidebar Candidates (filter by name or e-mail, select a row, Send Offer Letter, Mark as Joined). */
public class EorCandidatesPage extends BasePage {

    private final By link = By.xpath("//a[@href='/candidates']");
    private final By heading = By.xpath("//h1[normalize-space()='Candidates']");
    private final By filter = By.xpath("//input[@placeholder='Filter by name or email']");
    private final By sendOffer = By.xpath("//button[contains(normalize-space(.),'Send Offer Letter')]");
    private final By templateBox = By.xpath("//div[@role='dialog']//button[contains(normalize-space(.),'Choose a template')]");
    private final By sendButton = By.xpath("//div[@role='dialog']//div[@data-slot='dialog-footer']//button[normalize-space()='Send']");
    private final By markJoined = By.xpath("//button[normalize-space()='Mark as Joined']");
    private final By confirmYes = By.xpath("//div[@role='dialog' or @role='alertdialog']//button[normalize-space()='Yes']");

    public EorCandidatesPage(WebDriver driver) {
        super(driver);
    }

    public void open() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            click(link);
            try {
                waitFor(8).until(d -> d.getCurrentUrl().contains("/candidates"));
                visible(heading);
                waitFor(timeout).until(d -> !TableReader.showing(d).isEmpty() || !TableReader.rows(d).isEmpty());
                return;
            } catch (org.openqa.selenium.TimeoutException e) {
                if (attempt == 3) {
                    throw e;
                }
            }
        }
    }

    /** Filters by e-mail and waits until the list shows exactly the rows that contain it. */
    public List<Map<String, String>> filterBy(String email) {
        type(filter, email);
        String wanted = email.toLowerCase(Locale.ROOT);
        Instant end = Instant.now().plus(Duration.ofSeconds(timeout + 10));
        List<Map<String, String>> rows = List.of();
        int stable = 0;
        String last = "";
        while (Instant.now().isBefore(end)) {
            pause(500);
            rows = TableReader.rows(driver);
            boolean match = !rows.isEmpty() && rows.stream().allMatch(r -> String.join(" ", r.values()).toLowerCase(Locale.ROOT).contains(wanted));
            String now = TableReader.fingerprint(driver);
            stable = now.equals(last) ? stable + 1 : 0;
            last = now;
            if ((match && stable >= 2) || (rows.isEmpty() && stable >= 6)) {
                break;
            }
        }
        return rows;
    }

    /** The value of a column of the filtered row ("" when there is none). */
    public String cell(String email, String headerPart) {
        for (Map<String, String> r : TableReader.rows(driver)) {
            if (String.join(" ", r.values()).toLowerCase(Locale.ROOT).contains(email.toLowerCase(Locale.ROOT))) {
                return TableReader.column(r, headerPart);
            }
        }
        return "";
    }

    /** True once any cell of the row carries the word (e.g. "verified", "pending", "accepted"). */
    public boolean rowShows(String email, String word) {
        for (Map<String, String> r : TableReader.rows(driver)) {
            String all = String.join(" | ", r.values()).toLowerCase(Locale.ROOT);
            if (all.contains(email.toLowerCase(Locale.ROOT)) && all.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** True when the column (matched by part of its header, e.g. "OFFER LETTER", "PROFILE STATUS", "LOI STATUS") of the row of this e-mail shows the word. */
    public boolean columnShows(String email, String headerPart, String word) {
        String shown = cell(email, headerPart);
        return shown.toLowerCase(Locale.ROOT).contains(word.toLowerCase(Locale.ROOT));
    }

    public void selectRow(String email) {
        By box = By.xpath("//tr[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')," + Xp.literal(email.toLowerCase(Locale.ROOT))
                + ")]//button[@aria-label='Select row']");
        WebElement checkbox = waitFor(timeout).until(d -> {
            List<WebElement> l = d.findElements(box);
            return l.isEmpty() || !l.get(0).isDisplayed() ? null : l.get(0);
        });
        utils.SmoothScroll.to(driver, checkbox);
        if (!selected(checkbox)) {
            new Actions(driver).moveToElement(checkbox).click().perform(); // a plain click did not tick this box in the earlier project
        }
        waitFor(timeout).until(d -> selected(d.findElement(box)));
    }

    private static boolean selected(WebElement e) {
        return "true".equalsIgnoreCase(e.getAttribute("aria-checked")) || "true".equalsIgnoreCase(e.getAttribute("aria-pressed")) || "checked".equalsIgnoreCase(e.getAttribute("data-state"));
    }

    /** Send Offer Letter: chooses the template and sends. Returns the name of the template that was used. */
    public String sendOfferLetter() {
        click(sendOffer);
        click(templateBox);
        String wanted = ConfigManager.get("eor.offer.template", "Offer Letter");
        By option = By.xpath("//*[@role='option' and contains(normalize-space(.)," + Xp.literal(wanted) + ")] | //div[@role='dialog' or @data-radix-popper-content-wrapper]//span[contains(normalize-space(.)," + Xp.literal(wanted) + ")]");
        WebElement o = waitFor(timeout).until(d -> {
            List<WebElement> l = d.findElements(option);
            return l.isEmpty() ? null : l.get(0);
        });
        String name = o.getText().trim();
        new Actions(driver).moveToElement(o).click().perform();
        visible(sendButton);
        click(sendButton);
        return name;
    }

    public void markAsJoined() {
        click(markJoined);
        click(confirmYes);
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms); // documented: waiting for a list to re-render after a filter
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
