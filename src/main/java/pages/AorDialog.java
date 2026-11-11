package pages;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import base.BasePage;
import utils.DateInput;
import utils.ToastWatcher;
import utils.Xp;

/**
 * The windows ("dialogs") of the AOR screens: Bulk Upload Agents, Upload Pay Sheet, Download Bank Sheet, Download DA Invoices, Generate Invoice, Download Report.
 * They all work the same way: a label ("Client", "Period Start", "Payroll Session" ...) is followed by its field - a dropdown (a Radix select or a searchable list)
 * or a date box - and the lists of options open in a layer above the window. Verified on the sandbox (2026-11-10).
 */
public class AorDialog extends BasePage {

    /**
     * A window of these screens: a standard dialog, or the floating progress panel that opens after a bulk upload ("Creating Bulk Employees", "Uploading Payout
     * Sheet": a plain div fixed to the page, without a dialog role - verified 2026-11-10).
     */
    private final By dialog = By.xpath("//div[@role='dialog'] | //body/div[contains(@class,'fixed') and contains(@class,'pointer-events-auto')]");
    private final By options = By.xpath("//*[@role='option' or @cmdk-item]");

    public AorDialog(WebDriver driver) {
        super(driver);
    }

    // ------------------------------------------------------------------ the window

    /** Waits for the window with this heading. */
    public void waitOpen(String heading) {
        visible(By.xpath("//div[@role='dialog']//h2[normalize-space()=" + Xp.literal(heading) + "]"));
    }

    public boolean isOpen() {
        for (int i = 0; i < 4; i++) {
            try {
                return isDisplayedNow(dialog);
            } catch (org.openqa.selenium.StaleElementReferenceException windowJustChanged) {
                pause(100);
            }
        }
        return false;
    }

    /** All the text of the window (for the report and to read the answer of the application). */
    public String text() {
        for (int i = 0; i < 4; i++) {
            try {
                List<WebElement> d = driver.findElements(dialog);
                return d.isEmpty() ? "" : d.get(0).getText().replaceAll("\\s+", " ").trim();
            } catch (org.openqa.selenium.StaleElementReferenceException windowJustChanged) {
                pause(100);
            }
        }
        return "";
    }

    public boolean waitClosed(int seconds) {
        try {
            waitFor(seconds).until(d -> !isOpen());
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    /** Clicks a button of the window by its exact text. */
    public void clickButton(String text) {
        click(By.xpath("//div[@role='dialog']//button[normalize-space()=" + Xp.literal(text) + "]"));
    }

    public boolean buttonEnabled(String text) {
        List<WebElement> b = driver.findElements(By.xpath("//div[@role='dialog']//button[normalize-space()=" + Xp.literal(text) + "]"));
        return !b.isEmpty() && b.get(0).isEnabled() && !"true".equals(b.get(0).getAttribute("aria-disabled"));
    }

    /** Closes the window with its Cancel / Close button when it is still open (never throws). */
    public void dismiss() {
        try {
            driver.switchTo().activeElement().sendKeys(Keys.ESCAPE);
            if (isOpen()) {
                ((JavascriptExecutor) driver).executeScript(
                        "const b=Array.from(document.querySelectorAll('[role=dialog] button, body > div.fixed button')).find(x=>/^(Cancel|Close|Done|OK)$/i.test((x.innerText||x.getAttribute('aria-label')||x.title||'').trim()));if(b)b.click();");
                waitClosed(5);
            }
        } catch (RuntimeException ignored) {
            // nothing to close
        }
    }

    /** What the application answered after a click in a window (an upload, a download, Generate Invoice). */
    public static final class Answer {
        /** The text of the window while it was open (the last text before it closed, or the final one). */
        public String windowText = "";
        /** Every message that appeared on the screen for a moment. */
        public List<String> messages = new ArrayList<>();
        public boolean windowClosed;
        public long seconds;
        /** The different texts the windows showed, in order (a progress window changes its text all the time: the last few are kept). */
        public List<String> windows = new ArrayList<>();

        /** Everything the application said, in one text (window + messages). */
        public String everything() {
            return (windowText + " | " + String.join(" | ", messages)).trim();
        }

        /** Plain description for the report. */
        public String describe() {
            return "The application answered after " + seconds + " second(s). "
                    + (messages.isEmpty() ? "No message appeared on the screen. " : "Messages on the screen: \"" + String.join("\" | \"", messages) + "\". ")
                    + (windowClosed ? "The window closed by itself. " : "")
                    + (windowText.isEmpty() ? "" : "The last text in the window was: \"" + windowText + "\".");
        }
    }

    private static final Pattern PROBLEM = Pattern.compile("(?i)(fail|error|invalid|reject|could not|unable|already exist|duplicate|not found|missing|wrong)");
    private static final String PROBLEM_COUNT_WORDS = "failed|failures?|rejected|errors?|invalid|skipped";

    /** A window that is still working: a percentage below 100, or words like processing / generating / please wait. */
    private static final Pattern FINISHED = Pattern.compile("(?i)(completed|finished|done|successfully)");
    private static final Pattern WORKING = Pattern.compile("(?i)(\\b\\d{1,2}%|processing|generating|please wait|uploading|in progress|working)");

    /**
     * Clicks a button of the window and waits for the answer. A click can be followed by ANOTHER window with a progress bar ("Creating Bulk Employees ... 38%"):
     * that window is followed until it has finished - it closes, or it shows an answer and nothing changes for 3 seconds while it is no longer "working".
     * Waits at most {@code seconds}.
     */
    public Answer clickAndWait(String buttonText, int seconds) {
        ToastWatcher.clear(driver);
        String before = text();
        Instant start = Instant.now();
        clickButton(buttonText);
        Answer a = new Answer();
        String last = before;
        Instant changedAt = Instant.now();
        int noWindow = 0;
        while (Duration.between(start, Instant.now()).getSeconds() < seconds) {
            pause(500);
            boolean open = isOpen();
            collect(a);
            if (!open) {
                if (++noWindow >= (a.messages.isEmpty() ? 30 : 8)) { // 15 seconds without any window (4 once a message appeared): nothing else is coming
                    a.windowClosed = true;
                    pause(1500); // a message can appear a moment after the window closed
                    collect(a);
                    break;
                }
                continue;
            }
            noWindow = 0;
            String now = text();
            a.windowText = now;
            if (!now.equals(last)) {
                last = now;
                changedAt = Instant.now();
                if (a.windows.isEmpty() || !a.windows.get(a.windows.size() - 1).equals(now)) {
                    a.windows.add(now);
                    if (a.windows.size() > 8) {
                        a.windows.remove(0);
                    }
                }
            }
            long quiet = Duration.between(changedAt, Instant.now()).getSeconds();
            if (!now.equals(before) && (FINISHED.matcher(now).find() || !WORKING.matcher(now).find()) && quiet >= 3) {
                break; // the window shows an answer and nothing changes any more
            }
        }
        a.seconds = Duration.between(start, Instant.now()).getSeconds();
        return a;
    }

    private void collect(Answer a) {
        for (String t : ToastWatcher.all(driver)) {
            if (!a.messages.contains(t)) {
                a.messages.add(t);
            }
        }
    }

    /**
     * True when an answer tells of a problem: a count of failed / rejected / error / invalid / skipped rows that is more than 0 ("5 failed" and "Failed: 0/1" are both
     * understood), or - when the answer gives no such count - a word like failed, error, rejected, already exists.
     */
    public static boolean tellsOfProblem(String text) {
        String t = text == null ? "" : text;
        int counted = -1;
        for (String w : PROBLEM_COUNT_WORDS.split("\\|")) {
            counted = Math.max(counted, numberBefore(t, w));
        }
        if (counted >= 0) {
            return counted > 0;
        }
        return PROBLEM.matcher(t).find();
    }

    /** The first number that belongs to one of the words: in front of it ("5 agents created") or behind it ("Created: 5", "Failed: 0/1"); -1 when there is none. */
    public static int numberBefore(String text, String... words) {
        String t = text == null ? "" : text;
        for (String w : words) {
            Matcher m = Pattern.compile("(?i)(\\d+)\\s*(?:[a-z]+\\s+){0,3}(?:" + w + ")").matcher(t);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
            Matcher after = Pattern.compile("(?i)(?:" + w + ")\\W{0,3}(\\d+)").matcher(t);
            if (after.find()) {
                return Integer.parseInt(after.group(1));
            }
        }
        return -1;
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ fields

    /** The dropdown (or button) that follows the label in the window. */
    private By field(String labelStart) {
        return By.xpath("(//div[@role='dialog']//label[starts-with(normalize-space(.)," + Xp.literal(labelStart) + ")]/following::button[1])");
    }

    private By dateField(String labelStart) {
        return By.xpath("(//div[@role='dialog']//label[starts-with(normalize-space(.)," + Xp.literal(labelStart) + ")]/following::input[@type='date'][1])");
    }

    /** What the dropdown shows now ("Select a client", "Bleach" ...). */
    public String shown(String labelStart) {
        return text(field(labelStart));
    }

    public boolean fieldEnabled(String labelStart) {
        WebElement e = visible(field(labelStart));
        return e.isEnabled() && !"true".equals(e.getAttribute("aria-disabled")) && e.getAttribute("disabled") == null;
    }

    /** Opens a dropdown, reads its options and closes it again. */
    public List<String> optionsOf(String labelStart) {
        open(labelStart);
        List<String> out = readOptions();
        close();
        return out;
    }

    /** Picks the option with exactly this text. */
    public void choose(String labelStart, String option) {
        String shown = "";
        for (int attempt = 1; attempt <= 3; attempt++) {
            open(labelStart);
            pick(By.xpath("//*[(@role='option' or @cmdk-item) and normalize-space()=" + Xp.literal(option) + "]"), option);
            close();
            shown = shown(labelStart);
            if (shown.equals(option)) {
                return;
            }
            // a click in a long list can land on a neighbouring entry (seen: "QA AOR Project" instead of "The Substitute Shinigami"): try again
        }
        throw new AssertionError("The dropdown \"" + labelStart + "\" shows \"" + shown + "\" instead of \"" + option + "\" after choosing it three times.");
    }

    /** Picks the first option that contains the text (a session id inside a longer label, ...). */
    public void chooseContaining(String labelStart, String part) {
        open(labelStart);
        pick(By.xpath("//*[(@role='option' or @cmdk-item)][contains(normalize-space(.)," + Xp.literal(part) + ")]"), part);
        close();
    }

    /** Ticks the option with this text in a dropdown that allows several choices (the list stays open for more, and is closed at the end). */
    public void tick(String labelStart, String option) {
        open(labelStart);
        pick(By.xpath("//*[(@role='option' or @cmdk-item)][contains(normalize-space(.)," + Xp.literal(option) + ")]"), option);
        close();
    }

    /** Ticks EVERY option of a multi-choice dropdown ("select all" when it offers that, otherwise one by one). Returns how many options the list had. */
    public int tickAll(String labelStart) {
        open(labelStart);
        List<String> all = readOptions();
        List<WebElement> selectAll = driver.findElements(By.xpath("//*[(@role='option' or @cmdk-item)][contains(translate(normalize-space(.),'SELECTALL','selectall'),'select all')]"));
        if (!selectAll.isEmpty()) {
            selectAll.get(0).click();
        } else {
            for (String o : all) {
                List<WebElement> opt = driver.findElements(By.xpath("//*[(@role='option' or @cmdk-item) and normalize-space()=" + Xp.literal(o) + "]"));
                if (!opt.isEmpty()) {
                    scrollInto(opt.get(0));
                    opt.get(0).click();
                }
            }
        }
        close();
        return all.size();
    }

    /**
     * Fills the plain text box that follows the label (used for the month "2026-11" of the invoice report): typed like a user, and - when the box does not hold the
     * value afterwards - set directly and the application told. Fails when the box still does not show the value.
     */
    public void setText(String labelStart, String value) {
        By box = By.xpath("(//div[@role='dialog']//label[starts-with(normalize-space(.)," + Xp.literal(labelStart) + ")]/following::input[1])");
        WebElement e = visible(box);
        e.click();
        e.sendKeys(value);
        if (!value.equals(e.getAttribute("value"))) {
            ((JavascriptExecutor) driver).executeScript(
                    "const el=arguments[0];Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,arguments[1]);"
                            + "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));", e, value);
        }
        if (!value.equals(e.getAttribute("value"))) {
            throw new AssertionError("The box \"" + labelStart + "\" shows \"" + e.getAttribute("value") + "\" instead of \"" + value + "\".");
        }
    }

    public void setDate(String labelStart, LocalDate date) {
        DateInput.type(driver, visible(dateField(labelStart)), date);
    }

    public String dateValue(String labelStart) {
        return visible(dateField(labelStart)).getAttribute("value");
    }

    /** Puts a file into the file box of the window (the box is hidden: the file is handed to it directly, like a drag and drop). */
    public void attach(Path file) {
        By input = By.xpath("//div[@role='dialog']//input[@type='file']");
        driver.findElement(input).sendKeys(file.toAbsolutePath().toString());
    }

    /** The names of the files the window says are chosen (what is written in it after the file was handed over). */
    public boolean mentions(String text) {
        return text().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ internals

    private void open(String labelStart) {
        By f = field(labelStart);
        WebElement e = visible(f);
        scrollInto(e);
        click(f);
        visible(options);
    }

    @SuppressWarnings("unchecked")
    private List<String> readOptions() {
        Object o = ((JavascriptExecutor) driver).executeScript(
                "return Array.from(document.querySelectorAll('[role=option],[cmdk-item]')).map(e=>(e.innerText||'').trim().replace(/\\s+/g,' ')).filter(t=>t);");
        return o == null ? new ArrayList<>() : new ArrayList<>((List<String>) o);
    }

    private void pick(By option, String what) {
        List<WebElement> found = driver.findElements(option);
        if (found.isEmpty()) {
            throw new AssertionError("The list has no entry '" + what + "' (entries: " + readOptions() + ").");
        }
        WebElement e = found.get(0);
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'})", e);
        pause(300); // the list has to stop moving before the click
        try {
            e.click();
        } catch (RuntimeException covered) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click()", e);
        }
    }

    private void close() {
        try {
            if (!driver.findElements(options).isEmpty()) {
                driver.switchTo().activeElement().sendKeys(Keys.ESCAPE);
            }
        } catch (RuntimeException ignored) {
            // the list closed by itself
        }
        try {
            waitFor(3).ignoring(org.openqa.selenium.StaleElementReferenceException.class)
                    .until(d -> d.findElements(options).stream().noneMatch(x -> {
                        try {
                            return x.isDisplayed();
                        } catch (org.openqa.selenium.StaleElementReferenceException gone) {
                            return false;
                        }
                    }));
        } catch (TimeoutException stillOpen) {
            ((JavascriptExecutor) driver).executeScript("document.body.click()");
        }
    }

    private void scrollInto(WebElement e) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'nearest'})", e);
    }
}
