package pages;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import base.BasePage;
import utils.FormFeedback;
import utils.ToastWatcher;
import utils.Xp;

/**
 * Projects > AOR / EOR > "Add Project" (/projects/aor/add, /projects/eor/add). Verified on the sandbox (2026-11-08):
 * <ul>
 *   <li>AOR: Client Name (Radix select), Project Name, Project Code, Reference ID Format, Project Start Date (*), Project End Date, Country, State.
 *       There is no End Client Name and no City.</li>
 *   <li>EOR: Project Name, Project Code, Client Name, End Client Name, Project Start Date (*), Project End Date, then "Project Location" and
 *       "End Client Location", each with Country, State and City. The city is a free-text input. There is no Reference ID.</li>
 * </ul>
 * Text fields are found by their {@code name}; the Radix selects by the label in front of them (Country / State occur once for AOR and twice for EOR:
 * occurrence 1 = Project Location, 2 = End Client Location).
 */
public class AddProjectPage extends BasePage {

    public static final String NAME = "project_name";
    public static final String CODE = "project_code";
    public static final String REFERENCE_ID = "ref_id_format";
    public static final String END_CLIENT = "end_client_name";
    public static final String START_DATE = "project_start_date";
    public static final String END_DATE = "project_end_date";
    public static final String CITY = "project_city";
    public static final String END_CLIENT_CITY = "end_client_city";

    private static final Pattern CREATED = Pattern.compile("(?i)(success|created|added)");

    private final By heading = By.xpath("//h1[normalize-space()='Add Project']");
    private final By create = By.xpath("//button[@type='submit' and normalize-space()='Create Project']");
    private final By cancel = By.xpath("//button[normalize-space()='Cancel']");
    private final By openOptions = By.xpath("//div[@role='option']");

    public AddProjectPage(WebDriver driver) {
        super(driver);
    }

    // ------------------------------------------------------------------ the page

    public void waitOpen() {
        visible(heading);
        visible(create);
    }

    public boolean isOpen() {
        return isDisplayedNow(heading) && driver.getCurrentUrl().contains("/add");
    }

    /** The sentence under the heading, e.g. "AOR project details, reference format, and lifecycle dates." */
    public String description() {
        List<WebElement> p = driver.findElements(By.xpath("//h1[normalize-space()='Add Project']/following-sibling::*[1]"));
        return p.isEmpty() ? "" : p.get(0).getText().trim();
    }

    /** True when the form has a text / date input with that name. */
    public boolean hasField(String name) {
        return !driver.findElements(By.name(name)).isEmpty();
    }

    /** Number of labels that start with the text (Country and State occur twice on the EOR form). */
    public int labelCount(String labelStart) {
        return driver.findElements(By.xpath("//label[starts-with(normalize-space(.)," + Xp.literal(labelStart) + ")]")).size();
    }

    public void clickCancel() {
        click(cancel);
    }

    // ------------------------------------------------------------------ text fields

    public void setText(String field, String value) {
        By by = By.name(field);
        scrollIntoView(by);
        try {
            type(by, value);
        } catch (org.openqa.selenium.WebDriverException notTypable) {
            // ChromeDriver cannot type characters outside the Basic Multilingual Plane (emoji): set the value the way a paste does and tell the app
            if (!String.valueOf(notTypable.getMessage()).contains("BMP")) {
                throw notTypable;
            }
            ((JavascriptExecutor) driver).executeScript(
                    "const el=arguments[0];"
                            + "Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,arguments[1]);"
                            + "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));",
                    driver.findElement(by), value);
        }
    }

    /** True when the label of the field carries the red asterisk (the form marks it as required). */
    public boolean isMarkedRequired(String labelStart) {
        List<WebElement> l = driver.findElements(By.xpath("//label[starts-with(normalize-space(.)," + Xp.literal(labelStart) + ")]"));
        return !l.isEmpty() && l.get(0).getText().contains("*");
    }

    /** What the text field holds. */
    public String textOf(String field) {
        return value(By.name(field));
    }

    // ------------------------------------------------------------------ dates

    /**
     * Enters a date the way a user types it: the key order follows the browser's date format (dd-mm-yyyy, mm/dd/yyyy ...), so it works on any machine
     * locale. When the field does not hold the date afterwards (the value of a date input is always ISO) it is set directly and the app is told.
     */
    public void setDate(String field, LocalDate date) {
        By by = By.name(field);
        scrollIntoView(by);
        WebElement input = driver.findElement(by);
        input.click();
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        input.sendKeys(keysFor(date));
        if (!date.toString().equals(dateValue(field))) {
            ((JavascriptExecutor) driver).executeScript(
                    "const el=arguments[0];"
                            + "Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,arguments[1]);"
                            + "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));",
                    driver.findElement(by), date.toString());
        }
    }

    /** Empties a date field (a date input cannot be cleared with Ctrl+A / Backspace: each part is cleared on its own), and tells the app. */
    public void clearDate(String field) {
        ((JavascriptExecutor) driver).executeScript(
                "const el=arguments[0];"
                        + "Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,'');"
                        + "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));",
                driver.findElement(By.name(field)));
    }

    /** The key presses for a date input in the browser's own order: "15032024" for dd-mm-yyyy, "03152024" for mm-dd-yyyy, "20240315" for yyyy-mm-dd. */
    private String keysFor(LocalDate d) {
        Object parts = ((JavascriptExecutor) driver).executeScript(
                "return new Intl.DateTimeFormat(navigator.language,{day:'2-digit',month:'2-digit',year:'numeric'})"
                        + ".formatToParts(new Date(2000,10,25)).filter(p=>p.type!=='literal').map(p=>p.type).join(',');");
        StringBuilder keys = new StringBuilder();
        for (String p : String.valueOf(parts).split(",")) {
            switch (p) {
                case "day" -> keys.append(String.format("%02d", d.getDayOfMonth()));
                case "month" -> keys.append(String.format("%02d", d.getMonthValue()));
                case "year" -> keys.append(String.format("%04d", d.getYear()));
                default -> { }
            }
        }
        return keys.toString();
    }

    /** The date the field holds, ISO yyyy-MM-dd; empty when the field is empty. */
    public String dateValue(String field) {
        return value(By.name(field));
    }

    // ------------------------------------------------------------------ Radix selects

    private By client() {
        return comboAfterLabel("Client Name");
    }

    private By combo(String label, int occurrence) {
        return By.xpath("(//label[starts-with(normalize-space(.)," + Xp.literal(label) + ")])[" + occurrence + "]/following::button[@role='combobox'][1]");
    }

    /** The options of the Client Name dropdown (the clients that exist for this project type). */
    public List<String> clientOptions() {
        return optionsOf(client());
    }

    public void selectClient(String name) {
        choose(client(), name);
    }

    public String clientShown() {
        return text(client());
    }

    public List<String> countryOptions(int occurrence) {
        return optionsOf(combo("Country", occurrence));
    }

    public void selectCountry(int occurrence, String country) {
        choose(combo("Country", occurrence), country);
    }

    public String countryShown(int occurrence) {
        return text(combo("Country", occurrence));
    }

    public List<String> stateOptions(int occurrence) {
        return optionsOf(combo("State", occurrence));
    }

    public void selectState(int occurrence, String state) {
        choose(combo("State", occurrence), state);
    }

    public String stateShown(int occurrence) {
        return text(combo("State", occurrence));
    }

    /** False while the State dropdown is disabled ("Select a country first"). */
    public boolean stateEnabled(int occurrence) {
        WebElement e = visible(combo("State", occurrence));
        return e.isEnabled() && !"true".equals(e.getAttribute("aria-disabled")) && e.getAttribute("disabled") == null;
    }

    /** Opens a dropdown, reads its options and closes it again. */
    private List<String> optionsOf(By combo) {
        scrollIntoView(combo);
        click(combo);
        visible(openOptions);
        List<String> out = readOpenOptions();
        closeList();
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> readOpenOptions() {
        Object o = ((JavascriptExecutor) driver).executeScript(
                "return Array.from(document.querySelectorAll('[role=option]')).map(e=>(e.innerText||'').trim()).filter(t=>t);");
        return o == null ? new ArrayList<>() : new ArrayList<>((List<String>) o);
    }

    private void closeList() {
        driver.switchTo().activeElement().sendKeys(Keys.ESCAPE);
        try {
            waitGone(openOptions);
        } catch (org.openqa.selenium.TimeoutException stillOpen) {
            ((JavascriptExecutor) driver).executeScript("document.body.click()");
        }
    }

    /** Opens the dropdown, picks the option with exactly this text and waits until the list closes; the dropdown then shows that text. */
    private void choose(By combo, String option) {
        scrollIntoView(combo);
        click(combo);
        By item = By.xpath("//div[@role='option' and normalize-space()=" + Xp.literal(option) + "]");
        visible(item);
        WebElement e = driver.findElement(item);
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'nearest'})", e);
        try {
            e.click();
        } catch (RuntimeException covered) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click()", driver.findElement(item));
        }
        waitGone(item);
        String shown = text(combo);
        if (!shown.equals(option)) {
            throw new AssertionError("The dropdown shows '" + shown + "' after choosing '" + option + "'.");
        }
    }

    // ------------------------------------------------------------------ submit

    /**
     * Clicks Create Project and waits for the application's answer. {@code accepted} = the page left /add (the listing opens) or a toast says the project
     * was created; otherwise the inline messages and toasts that explain the refusal are in the outcome.
     */
    public FormFeedback.Outcome clickCreate() {
        scrollIntoView(create);
        return FormFeedback.submit(driver, 20, () -> {
            try {
                click(create);
            } catch (RuntimeException covered) {
                jsClick(create); // a toast can cover the button
            }
        }, this::leftFormOrCreated);
    }

    /** Clicks Create Project twice in the same instant (a double click) and waits for the application's answer like {@link #clickCreate()}. */
    public FormFeedback.Outcome doubleClickCreate() {
        scrollIntoView(create);
        return FormFeedback.submit(driver, 20, () -> ((JavascriptExecutor) driver).executeScript(
                "const b=arguments[0]; b.click(); b.click();", driver.findElement(create)), this::leftFormOrCreated);
    }

    private boolean leftFormOrCreated() {
        return !driver.getCurrentUrl().contains("/add") || ToastWatcher.all(driver).stream().anyMatch(t -> CREATED.matcher(t).find()
                && !t.toLowerCase(Locale.ROOT).contains("fail") && !t.toLowerCase(Locale.ROOT).contains("error"));
    }
}
