package pages;

import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import base.BasePage;
import model.ClientData;
import utils.Xp;

/** Client onboarding wizard - step 1: Organization Details (+ CIN / GST documents). */
public class OrganizationDetailsPage extends BasePage {

    private final By orgName = By.name("organization_name");
    private final By years = By.name("years_in_industry");
    private final By website = By.name("website_link");
    private final By description = By.name("product_or_service_description");
    private final By pincode = By.name("registered_address.pincode");
    private final By address = By.name("registered_address.address");
    private final By sameAsRegistered = By.id("same-as-registered");
    private final By email = By.name("email");
    private final By mobile = By.name("mobile_number");
    private final By cin = By.name("company_registration_number");
    private final By gst = By.name("gst_number");
    private final By cinFile = By.name("upload_cin_doc");
    private final By gstFile = By.name("upload_gst_doc");
    private final By companyType = comboAfterLabel("Company Type");
    private final By stateTrigger = By.xpath("(//label[starts-with(normalize-space(.),'State')]/following::button)[1]");
    private final By cityTrigger = By.xpath("(//label[starts-with(normalize-space(.),'City')]/following::button)[1]");
    private final By continueButton = By.xpath("//button[@type='submit' and normalize-space()='Continue']");
    private final By removeButtons = By.xpath("//button[normalize-space()='Remove']");

    public OrganizationDetailsPage(WebDriver driver) {
        super(driver);
    }

    public void waitUntilOpen() {
        visible(orgName);
    }

    public String prefilledOrganizationName() {
        return value(orgName);
    }

    public String prefilledEmail() {
        return value(email);
    }

    public String prefilledMobile() {
        return value(mobile);
    }

    /** Fills the form. Returns the city that was actually selected. */
    public String fill(ClientData d) {
        type(years, d.yearsInIndustry);
        type(website, d.website);
        type(description, d.description);
        type(pincode, d.pincode);
        waitForStatePopulated();
        String city = resolveCity(d.city);
        type(address, d.address);
        ensureMailingSameAsRegistered();
        selectRadix(companyType, d.companyType);
        // registered email / mobile: keep when pre-filled, otherwise enter the registration values
        if (value(email).isEmpty()) {
            type(email, d.email);
        }
        if (value(mobile).isEmpty()) {
            type(mobile, d.mobile);
        }
        type(cin, d.cin);
        type(gst, d.gst);
        return city;
    }

    /** The checkbox is ticked by default; only click it when it is not already ticked. */
    private void ensureMailingSameAsRegistered() {
        if (!"true".equals(driver.findElement(sameAsRegistered).getAttribute("aria-checked"))) {
            click(sameAsRegistered);
        }
        waitFor(timeout).until(x -> "true".equals(driver.findElement(sameAsRegistered).getAttribute("aria-checked")));
    }

    /** State (and usually city) are auto-populated from the pincode; this only waits for them. Correctness is out of scope. */
    private void waitForStatePopulated() {
        try {
            waitFor(timeout).until(x -> {
                String s = driver.findElement(stateTrigger).getText().trim();
                return !s.isEmpty() && !s.toLowerCase().startsWith("select");
            });
        } catch (TimeoutException e) {
            throw new AssertionError("State was not populated after entering the pincode; cannot continue.");
        }
    }

    /**
     * The pincode lookup normally auto-selects a city. Keep it when present (the mapping is not asserted);
     * only if none arrives, pick the configured city or the first one offered. Returns the city in effect.
     */
    private String resolveCity(String wanted) {
        try {
            waitFor(8).until(x -> !driver.findElement(cityTrigger).getText().trim().toLowerCase().startsWith("select"));
            return driver.findElement(cityTrigger).getText().trim();
        } catch (TimeoutException e) {
            return selectCity(wanted);
        }
    }

    private String selectCity(String wanted) {
        click(cityTrigger);
        By exact = By.xpath("//div[@role='option' and normalize-space()=" + Xp.literal(wanted) + "]");
        String chosen = wanted;
        if (isDisplayed(exact, 5)) {
            click(exact);
        } else {
            List<WebElement> options = driver.findElements(By.xpath("//div[@role='option']"));
            if (options.isEmpty()) {
                throw new AssertionError("No city options offered for pincode");
            }
            chosen = options.get(0).getText().trim();
            options.get(0).click();
        }
        return chosen;
    }

    // ---- read-back for "values reflected in the UI" -----------------------------------------

    public String yearsValue() { return value(years); }
    public String websiteValue() { return value(website); }
    public String descriptionValue() { return value(description); }
    public String pincodeValue() { return value(pincode); }
    public String addressValue() { return value(address); }
    public String cinValue() { return value(cin); }
    public String gstValue() { return value(gst); }
    public String emailValue() { return value(email); }
    public String mobileValue() { return value(mobile); }
    public String cityShown() { return text(cityTrigger); }
    public String stateShown() { return text(stateTrigger); }
    public String companyTypeShown() { return text(companyType); }

    // ---- documents ------------------------------------------------------------------------

    public void uploadCin(String path, String fileName) {
        uploadAndWait(cinFile, path, fileName);
    }

    public void uploadTaxId(String path, String fileName) {
        uploadAndWait(gstFile, path, fileName);
    }

    private void uploadAndWait(By input, String path, String fileName) {
        int before = driver.findElements(removeButtons).size();
        upload(input, path);
        try {
            waitFor(timeout).until(x -> driver.findElements(removeButtons).size() > before
                    && !driver.findElements(By.xpath("//*[normalize-space(text())=" + Xp.literal(fileName) + "]")).isEmpty());
        } catch (TimeoutException e) {
            throw new AssertionError("Upload of '" + fileName + "' did not complete (file name + Remove control never appeared).");
        }
    }

    public boolean isFileShown(String fileName) {
        return !driver.findElements(By.xpath("//*[normalize-space(text())=" + Xp.literal(fileName) + "]")).isEmpty();
    }

    public void clickContinue() {
        scrollIntoView(continueButton);
        jsClick(continueButton); // a toast can overlap the button; a script click cannot be intercepted
    }

    /** Re-enters all baseline text values when any of them drifted (e.g. after an earlier case). */
    public void ensureValues(ClientData d) {
        String[][] pairs = {
            { "years_in_industry", d.yearsInIndustry }, { "website_link", d.website }, { "product_or_service_description", d.description },
            { "registered_address.pincode", d.pincode }, { "registered_address.address", d.address },
            { "company_registration_number", d.cin }, { "gst_number", d.gst } };
        for (String[] p : pairs) {
            if (!p[1].equals(fieldValue(p[0]))) {
                if (p[0].equals("registered_address.pincode")) {
                    restorePincode(p[1], d.city);
                } else {
                    setField(p[0], p[1]);
                }
            }
        }
    }

    // ---- negative-test helpers ------------------------------------------------------------------

    private final By continueSetupPrompt = By.xpath("//button[normalize-space()='Continue Setup']");
    private final By nextStepMarker = By.name("business.name");

    /** Sets a text input by its form name (empty string clears it). */
    public void setField(String name, String value) {
        By by = By.name(name);
        WebElement e = visible(by);
        scrollIntoView(by);
        e.sendKeys(org.openqa.selenium.Keys.chord(org.openqa.selenium.Keys.CONTROL, "a"), org.openqa.selenium.Keys.BACK_SPACE);
        if (value != null && !value.isEmpty()) {
            e.sendKeys(value);
        }
    }

    /** False when the input is disabled / read-only (e.g. pre-filled registration values). */
    public boolean isEditable(String name) {
        WebElement e = visible(By.name(name));
        return e.isEnabled() && e.getAttribute("readonly") == null;
    }

    public String fieldValue(String name) {
        return value(By.name(name));
    }

    /** Re-enters the pincode and waits for State (and City) to be auto-populated again. */
    public void restorePincode(String pin, String wantedCity) {
        setField("registered_address.pincode", pin);
        waitForStatePopulated();
        resolveCity(wantedCity);
    }

    public boolean isOpen() {
        return isDisplayedNow(orgName);
    }

    /** True once the wizard has moved past step 1 (form gone, or the Continue Setup prompt / step 2 appears). */
    public boolean advancedPastStep1() {
        return !isOpen() || isDisplayedNow(continueSetupPrompt) || isDisplayedNow(nextStepMarker);
    }

    public utils.FormFeedback.Outcome continueAttempt() {
        return utils.FormFeedback.submit(driver, 8, this::clickContinue, this::advancedPastStep1);
    }

    public int documentCount() {
        return driver.findElements(removeButtons).size();
    }

    /** Removes every uploaded document (CIN and GST) so the upload slots are empty. */
    public void removeAllDocuments() {
        for (int guard = 0; guard < 6 && documentCount() > 0; guard++) {
            int before = documentCount();
            click(removeButtons);
            waitFor(timeout).until(x -> documentCount() < before);
        }
    }

    public void removeFirstDocument() {
        int before = documentCount();
        click(removeButtons);
        waitFor(timeout).until(x -> documentCount() < before);
    }

    /** Uploads without asserting: the result says whether the app accepted the file (name + Remove control shown). */
    public utils.FormFeedback.Outcome uploadAttempt(boolean cinSlot, String path, String fileName) {
        int before = documentCount();
        return utils.FormFeedback.submit(driver, 10, () -> upload(cinSlot ? cinFile : gstFile, path),
                () -> documentCount() > before);
    }
}
