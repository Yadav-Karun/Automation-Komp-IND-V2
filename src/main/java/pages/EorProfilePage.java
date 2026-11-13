package pages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

import base.BasePage;
import utils.Xp;

/**
 * Admin side: the profile of an EOR employee ({@code /employees/<candidate id>/profile}, opened with the eye button of Employees &gt; EOR).
 * Header (name, status, designation, phone, e-mail, employee code, client, project), the buttons "Unmask all" / "Mask all" and "Deactivate employee", and five tabs:
 * Personal &amp; Contact, Professional, Statutory Details, Documents, Employment Documents. Every value is a {@code dt} (label) and {@code dd} (value) pair under a heading.
 */
public class EorProfilePage extends BasePage {

    private static final String PAIRS_JS = "const out=[];let heading='';const root=document.querySelector('main')||document.body;"
            + "for(const e of root.querySelectorAll('h1,h2,h3,h4,h5,dt')){"
            + "if(e.tagName==='DT'){const dd=e.nextElementSibling;let v='';if(dd){const t=dd.querySelector('[title]');v=((t&&t.getAttribute('title'))||dd.innerText||'').replace(/\\s+/g,' ').trim();}"
            + "out.push([heading,e.innerText.replace(/\\s+/g,' ').trim(),v]);}"
            + "else{heading=e.innerText.replace(/\\s+/g,' ').trim();}}return out;";

    private final By unmask = By.xpath("//button[normalize-space()='Unmask all']");
    private final By mask = By.xpath("//button[normalize-space()='Mask all']");
    private final By back = By.xpath("//button[normalize-space()='Back to Employees']");

    public EorProfilePage(WebDriver driver) {
        super(driver);
    }

    public boolean isOpen() {
        return urlContains("/profile") && isDisplayed(back, timeout);
    }

    public void selectTab(String tab) {
        By t = By.xpath("//*[@role='tab' and contains(normalize-space(.)," + Xp.literal(tab) + ")]");
        clickCentered(t);
        waitFor(timeout).until(d -> "true".equals(d.findElement(t).getAttribute("aria-selected")) || "active".equals(d.findElement(t).getAttribute("data-state")));
        pause(800); // the tab fills a moment after it is selected
        utils.SmoothScroll.tour(driver); // the whole tab glides past, down and up, for the person who watches
    }

    /** Shows every masked value (phone numbers, account numbers, Aadhaar, PAN): the button turns into "Mask all". */
    public void unmaskAll() {
        if (isDisplayedNow(unmask)) {
            clickCentered(unmask);
            visible(mask);
            pause(1500);
        }
    }

    /** True while a value of the page is still hidden behind X characters (phone numbers, account numbers, Aadhaar, PAN). */
    public boolean stillMasked() {
        for (String[] p : pairs()) {
            if (p[2].contains("XXX")) {
                return true;
            }
        }
        return false;
    }

    /** The values of a tab can come back masked after the tab was opened: the page is told once more to show them (Mask all, Unmask all). Returns true when that was needed. */
    public boolean unmaskAgainIfNeeded() {
        if (!stillMasked()) {
            return false;
        }
        if (isDisplayedNow(mask)) {
            clickCentered(mask);
            visible(unmask);
        }
        clickCentered(unmask);
        visible(mask);
        pause(2000);
        return true;
    }

    /** Loads the profile again (F5), unmasks and opens the tab. */
    public void reload(String tab) {
        driver.navigate().refresh();
        visible(back);
        unmaskAll();
        selectTab(tab);
        unmaskAgainIfNeeded();
    }

    /** The button "Mask all": every value is hidden again. */
    public void maskAll() {
        if (isDisplayedNow(mask)) {
            clickCentered(mask);
            visible(unmask);
            pause(1500);
        }
    }

    public boolean unmaskOffered() {
        return isDisplayedNow(unmask);
    }

    /** The small eye button of ONE value (title "Show Account No", "Show Aadhar No" ...): shows only that value. Returns false when the tab has no such button. */
    public boolean showOne(String title) {
        List<org.openqa.selenium.WebElement> b = driver.findElements(By.xpath("//main//button[@title=" + Xp.literal(title) + "]"));
        if (b.isEmpty()) {
            return false;
        }
        utils.SmoothScroll.click(driver, b.get(0));
        pause(1200);
        return true;
    }

    public boolean unmasked() {
        return isDisplayedNow(mask);
    }

    /** The lines of the header, down to the button "Deactivate employee" (name, status, designation and phone, e-mail, employee code, client, project). */
    public List<String> headerLines() {
        String all = (String) ((JavascriptExecutor) driver).executeScript("return (document.querySelector('main')||document.body).innerText;");
        List<String> lines = new ArrayList<>();
        for (String l : all.split("\\R")) {
            String t = l.trim();
            if (t.equals("Deactivate employee")) {
                break;
            }
            if (!t.isEmpty() && !t.equals("Back to Employees")) {
                lines.add(t);
            }
        }
        return lines;
    }

    /** Every label / value pair of the page now, in page order: {heading, label, value}. */
    @SuppressWarnings("unchecked")
    public List<String[]> pairs() {
        List<String[]> out = new ArrayList<>();
        Object o = ((JavascriptExecutor) driver).executeScript(PAIRS_JS);
        if (o instanceof List) {
            for (Object row : (List<Object>) o) {
                List<Object> r = (List<Object>) row;
                out.add(new String[] {String.valueOf(r.get(0)), String.valueOf(r.get(1)), String.valueOf(r.get(2))});
            }
        }
        return out;
    }

    /** The value of the label under a heading (heading matched by part of its text, label ignoring case); {@code occurrence} counts from 0 when the label repeats. null when it is not on the page. */
    public static String value(List<String[]> pairs, String heading, String label, int occurrence) {
        int seen = 0;
        for (String[] p : pairs) {
            if (p[0].toLowerCase(Locale.ROOT).contains(heading.toLowerCase(Locale.ROOT)) && p[1].equalsIgnoreCase(label)) {
                if (seen++ == occurrence) {
                    return p[2];
                }
            }
        }
        return null;
    }

    /** The value of a label anywhere on the page. */
    public static String value(List<String[]> pairs, String label) {
        return value(pairs, "", label, 0);
    }

    // ------------------------------------------------------------------ edit (every block of a tab has an Edit button; the values turn into inputs, Cancel / Submit)

    /** One value of the block that is being edited. */
    public static final class Field {
        public String label;
        /** text, tel, date, textarea, combobox */
        public String kind;
        public String value;
        /** The number of the label among all labels of the page (the same before and after the change). */
        public int index;
    }

    private static final String ROOT = "(//button[normalize-space()='Cancel'])[1]/ancestor::div[.//dl][1]";

    /** The Edit buttons are shown on hover only (Selenium counts them as hidden): they are found and clicked through the page. */
    private static final String EDIT_BUTTONS_JS = "const f=()=>Array.from(document.querySelectorAll('main button')).filter(b=>b.offsetParent!==null&&/^edit/i.test((b.title||b.getAttribute('aria-label')||'').trim()));";

    public int editButtons() {
        return ((Number) ((JavascriptExecutor) driver).executeScript(EDIT_BUTTONS_JS + "return f().length;")).intValue();
    }

    /** Opens the block with this number (0, 1, ...) of the Edit buttons of the tab. */
    public void startEdit(int index) {
        ((JavascriptExecutor) driver).executeScript(EDIT_BUTTONS_JS + "const b=f()[arguments[0]];b.scrollIntoView({block:'center'});b.click();", index);
        visible(By.xpath("//button[normalize-space()='Cancel']"));
        pause(600);
    }

    public String editHeading() {
        List<org.openqa.selenium.WebElement> h = driver.findElements(By.xpath(ROOT + "/preceding-sibling::div[1]//h3 | " + ROOT + "/ancestor::*[.//h3][1]//h3"));
        return h.isEmpty() ? "" : h.get(0).getText().trim();
    }

    /** The fields of the open block that can be changed (the ones that turned into an input). */
    @SuppressWarnings("unchecked")
    public List<Field> fields() {
        Object o = ((JavascriptExecutor) driver).executeScript(
                "const cancel=Array.from(document.querySelectorAll('button')).find(b=>b.innerText.trim()==='Cancel');if(!cancel)return [];"
                        + "let root=cancel.parentElement;while(root&&!root.querySelector('dl'))root=root.parentElement;const out=[];const all=Array.from(document.querySelectorAll('main dt'));"
                        + "for(const dt of root.querySelectorAll('dt')){const c=dt.nextElementSibling;if(!c)continue;const ix=all.indexOf(dt);"
                        + "const i=c.matches('input,textarea')?c:c.querySelector('input,textarea');"
                        + "if(i&&i.type!=='file'&&i.type!=='hidden'&&!i.readOnly&&!i.disabled){out.push([dt.innerText.trim(),i.tagName==='TEXTAREA'?'textarea':i.type,i.value,ix]);continue;}"
                        + "const b=c.matches('button')?c:c.querySelector('button[role=combobox],button[aria-haspopup]');"
                        + "if(b&&!b.disabled&&!/preview|download|replace|delete/i.test((b.title||'')+(b.innerText||'')))out.push([dt.innerText.trim(),'combobox',b.innerText.trim(),ix]);}"
                        + "return out;");
        List<Field> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object row : (List<Object>) o) {
                List<Object> r = (List<Object>) row;
                Field f = new Field();
                f.label = String.valueOf(r.get(0));
                f.kind = String.valueOf(r.get(1));
                f.value = String.valueOf(r.get(2));
                f.index = ((Number) r.get(3)).intValue();
                out.add(f);
            }
        }
        return out;
    }

    private By control(String label) {
        // the label is shown in capitals by the page's style sheet: it is compared in capitals
        String dt = ROOT + "//dt[translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ')=" + Xp.literal(label.toUpperCase(Locale.ROOT)) + "]/following-sibling::*[1]";
        return By.xpath(dt + "/descendant-or-self::input[not(@type='file')] | " + dt + "/descendant-or-self::textarea | " + dt + "/descendant-or-self::button[@role='combobox' or @aria-haspopup]");
    }

    /** What the box of the label holds now (after typing: the text that the box really kept, e.g. cut at its maximum length). */
    public String currentValue(String label) {
        org.openqa.selenium.WebElement e = visible(control(label));
        String v = e.getAttribute("value");
        return v == null ? e.getText().trim() : v;
    }

    public void setText(String label, String text) {
        org.openqa.selenium.WebElement e = visible(control(label));
        utils.SmoothScroll.to(driver, e);
        try {
            e.sendKeys(org.openqa.selenium.Keys.chord(org.openqa.selenium.Keys.CONTROL, "a"), org.openqa.selenium.Keys.BACK_SPACE);
            e.sendKeys(text);
        } catch (org.openqa.selenium.WebDriverException covered) {
            setNative(e, text); // the box lies under the sticky header or is not reachable by the keyboard: the value is set the way the page itself does
        }
        if (!text.equals(currentValue(label))) {
            setNative(e, text);
        }
    }

    /** A date box takes the digits in the order of the browser; the value is checked and, when needed, set the way the page itself does. */
    public void setDate(String label, java.time.LocalDate date) {
        org.openqa.selenium.WebElement e = visible(control(label));
        utils.SmoothScroll.to(driver, e);
        try {
            utils.DateInput.type(driver, e, date);
        } catch (org.openqa.selenium.WebDriverException covered) {
            setNative(e, date.toString());
        }
        if (!date.toString().equals(currentValue(label))) {
            setNative(e, date.toString());
        }
    }

    private void setNative(org.openqa.selenium.WebElement e, String value) {
        ((JavascriptExecutor) driver).executeScript(
                "const i=arguments[0];const proto=i.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                        + "Object.getOwnPropertyDescriptor(proto,'value').set.call(i,arguments[1]);"
                        + "i.dispatchEvent(new Event('input',{bubbles:true}));i.dispatchEvent(new Event('change',{bubbles:true}));", e, value);
    }

    /** Opens the drop-down of the label and chooses an entry that is not the current one; returns the text of the entry. The list is looked up again on every try (the page redraws it). */
    public String chooseDifferent(String label, String current) {
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                org.openqa.selenium.WebElement t = visible(control(label));
                utils.SmoothScroll.to(driver, t);
                if (!waitEnabled(t, 8)) {
                    pause(1500); // State / City unlock after the country / state was chosen: the list is tried again
                    continue;
                }
                closeOpenLists(t);
                ((JavascriptExecutor) driver).executeScript("arguments[0].click()", t);
                By options = By.xpath("(//div[@data-radix-popper-content-wrapper])[last()]//*[@role='option']"); // the entries of THIS list only
                if (isDisplayed(options, 5)) {
                    pause(700); // the entries of a list that depends on another (City after State) are filled a moment after it opens
                    for (org.openqa.selenium.WebElement o : driver.findElements(options)) {
                        String txt = o.getText().trim();
                        if (!txt.isEmpty() && !txt.equalsIgnoreCase(current.trim())) {
                            utils.SmoothScroll.click(driver, o);
                            return txt;
                        }
                    }
                }
                t.sendKeys(org.openqa.selenium.Keys.ESCAPE);
            } catch (org.openqa.selenium.StaleElementReferenceException redrawn) {
                // the page redrew the list: it is found again
            }
            pause(1000);
        }
        throw new AssertionError("The list \"" + label + "\" offered no other entry than \"" + current + "\".");
    }

    /** A list that is still open (or still closing) from an earlier choice is closed first, so that its entries are not mistaken for the entries of the next list. */
    private void closeOpenLists(org.openqa.selenium.WebElement trigger) {
        By any = By.xpath("//div[@data-radix-popper-content-wrapper]//*[@role='option']");
        for (int i = 0; i < 6 && !driver.findElements(any).isEmpty(); i++) {
            try {
                trigger.sendKeys(org.openqa.selenium.Keys.ESCAPE);
            } catch (RuntimeException ignored) {
                // the list closes anyway
            }
            pause(500);
        }
    }

    private boolean waitEnabled(org.openqa.selenium.WebElement t, int seconds) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            if (t.isEnabled() && !"true".equals(t.getAttribute("aria-disabled")) && t.getAttribute("disabled") == null) {
                return true;
            }
            pause(500);
        }
        return false;
    }

    /** Opens the drop-down of the label and chooses the named entry (a searchable list is searched for it first). */
    public String chooseNamed(String label, String option) {
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                org.openqa.selenium.WebElement t = visible(control(label));
                if (!waitEnabled(t, 8)) {
                    pause(1500);
                    continue;
                }
                closeOpenLists(t);
                utils.SmoothScroll.click(driver, t);
                By search = By.xpath("(//div[@data-radix-popper-content-wrapper])[last()]//input[contains(@placeholder,'Search')]");
                if (isDisplayed(search, 2)) {
                    org.openqa.selenium.WebElement box = driver.findElement(search);
                    box.sendKeys(org.openqa.selenium.Keys.chord(org.openqa.selenium.Keys.CONTROL, "a"), org.openqa.selenium.Keys.BACK_SPACE);
                    box.sendKeys(option);
                }
                By entry = By.xpath("(//div[@data-radix-popper-content-wrapper])[last()]//*[@role='option' and normalize-space()=" + Xp.literal(option) + "]");
                if (isDisplayed(entry, 6)) {
                    utils.SmoothScroll.click(driver, driver.findElement(entry));
                    return option;
                }
                t.sendKeys(org.openqa.selenium.Keys.ESCAPE);
            } catch (org.openqa.selenium.StaleElementReferenceException redrawn) {
                // found again on the next try
            }
            pause(1000);
        }
        throw new AssertionError("The list \"" + label + "\" did not offer \"" + option + "\".");
    }

    /** Submit: waits until the block is back in its reading form. Returns what the application said (messages). */
    public String submit() {
        utils.ToastWatcher.clear(driver);
        clickCentered(By.xpath("//button[normalize-space()='Submit']"));
        try {
            waitFor(timeout).until(d -> d.findElements(By.xpath("//button[normalize-space()='Cancel']")).isEmpty());
        } catch (org.openqa.selenium.TimeoutException e) {
            throw new AssertionError("The block did not accept the change (it is still open after \"Submit\"). The application said: " + utils.ToastWatcher.all(driver)
                    + " " + utils.FormFeedback.inlineMessages(driver));
        }
        pause(1500);
        return String.join(" | ", utils.ToastWatcher.all(driver));
    }

    public void cancelEdit() {
        List<org.openqa.selenium.WebElement> c = driver.findElements(By.xpath("//button[normalize-space()='Cancel']"));
        if (!c.isEmpty()) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click()", c.get(0));
            pause(500);
        }
    }

    /** The page has a sticky header: the button is brought to the middle of the window before it is clicked (a click by script when something still lies over it). */
    private void clickCentered(By by) {
        org.openqa.selenium.WebElement e = visible(by);
        utils.SmoothScroll.to(driver, e);
        try {
            e.click();
        } catch (org.openqa.selenium.WebDriverException covered) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click()", e);
        }
    }

    public String bodyTextNow() {
        return bodyText();
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms); // documented: a tab / the unmasked values need a moment to render
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
