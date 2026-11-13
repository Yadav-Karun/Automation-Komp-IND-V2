package pages;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.Select;

import base.BasePage;
import model.EorEmployee;
import utils.ConfigManager;
import utils.DateInput;
import utils.Log;
import utils.Xp;

/**
 * The candidate portal of an EOR employee ({@code /candidate/login}): sign in, "Start Now", the six profile sections (Personal, Contact, Bank, Professional,
 * Education, Identification) each ending with "Save &amp; Continue", the three acknowledgements, the completion window ("Done"), and later "Accept Offer".
 * An experienced employee also fills in the previous employment. Locators are the field names of the portal (verified in the QA owner's earlier project).
 */
public class EorCandidatePortalPage extends BasePage {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final By email = By.id("email");
    private final By password = By.id("password");
    private final By signIn = By.xpath("//button[@type='submit' and normalize-space()='Sign in'] | //button[normalize-space()='Sign in']");
    private final By startNow = By.xpath("//button[starts-with(normalize-space(.), 'Start Now')]");
    private final By saveContinue = By.xpath("//button[normalize-space()='Save & Continue']");
    private final By acknowledge = By.xpath("//button[normalize-space()='I Acknowledge']");
    private final By done = By.xpath("//button[normalize-space()='Done']");
    private final By acceptOffer = By.xpath("//button[contains(normalize-space(.), 'Accept Offer')]");
    private final By logout = By.xpath("//button[normalize-space()='Logout']");

    public EorCandidatePortalPage(WebDriver driver) {
        super(driver);
    }

    // ------------------------------------------------------------------ sign in

    /** Ends any session and signs in on the candidate login page with the e-mail and the password from the mail. */
    public void signIn(String user, String pass) {
        user = user.trim().toLowerCase(Locale.ROOT); // the login uses small letters only
        driver.get(ConfigManager.baseUrl() + "/candidate/login");
        ((JavascriptExecutor) driver).executeScript("try{localStorage.clear();sessionStorage.clear();}catch(e){}");
        ((org.openqa.selenium.chromium.ChromiumDriver) driver).executeCdpCommand("Network.clearBrowserCookies", java.util.Map.of());
        driver.get(ConfigManager.baseUrl() + "/candidate/login");
        type(email, user);
        type(password, pass);
        click(signIn);
        try {
            waitUrlNotContains("/login");
        } catch (TimeoutException e) {
            List<String> shown = new ArrayList<>(utils.ToastWatcher.all(driver));
            shown.addAll(utils.FormFeedback.inlineMessages(driver));
            if (LoginPage.isRateLimited(shown)) {
                throw new utils.RateLimitedLogin("The application answered: " + shown);
            }
            throw new AssertionError("The candidate could not sign in with the password of the mail. The page says: \"" + bodyText().replaceAll("\\s+", " ").trim() + "\"");
        }
    }

    public boolean startNowOffered(int seconds) {
        return isDisplayed(startNow, seconds);
    }

    public void startNow() {
        click(startNow);
    }

    // ------------------------------------------------------------------ the sections

    public void personal(EorEmployee e) {
        gotoStep("Personal Information");
        if (alreadyFilled("father_name")) {
            return;
        }
        field("father_name", e.fatherName);
        field("mother_name", e.motherName);
        choose("Marital Status", e.maritalStatus);
        if ("Married".equalsIgnoreCase(e.maritalStatus)) {
            field("spouse_name", e.spouseName);
        }
        choose("Nationality", e.nationality);
        choose("Blood Group", e.bloodGroup);
        date("emp_dob", e.dateOfBirth);
        choose("Physically Handicapped", e.physicallyHandicapped);
        if ("Yes".equalsIgnoreCase(e.physicallyHandicapped)) {
            attachByLabel("Disability Certificate", "disabledEmployeePath"); // the upload box appears after "Yes" is chosen
        }
        saveAndContinue();
    }

    public void contact(EorEmployee e) {
        gotoStep("Contact Information");
        if (alreadyFilled("current.address")) {
            return;
        }
        field("alternate_mobile_no", e.alternateMobile);
        field("emergency_name", e.emergencyName);
        choose("Relationship", e.emergencyRelationship);
        field("emergency_contact_no", e.emergencyNumber);
        // the current address comes first ...
        chooseAt("Country", e.addressCountry, 1);
        chooseAt("State", e.addressState, 1);
        chooseAt("City", e.addressCity, 1);
        field("current.pincode", e.postalCode);
        field("current.address", e.address);
        // ... then the permanent address is either the same (the box is ticked) or entered on its own
        if (e.permanentSame) {
            tickSameAsCurrent(true);
        } else {
            tickSameAsCurrent(false);
            chooseAt("Country", e.addressCountry, 2);
            chooseAt("State", e.permanentState, 2);
            chooseAt("City", e.permanentCity, 2);
            field("permanent.pincode", e.permanentPostalCode);
            field("permanent.address", e.permanentAddress);
        }
        saveAndContinue();
    }

    public void bank(EorEmployee e) {
        gotoStep("Banking Details");
        if (alreadyFilled("account_no")) {
            return;
        }
        field("account_holder_name", e.accountHolder);
        field("bank_name", e.bankName);
        field("account_no", e.accountNumber);
        field("confirm_account_no", e.accountNumber);
        field("ifsc_code", e.ifsc);
        choose("Account Type", e.accountType);
        field("branch_name", e.branchName);
        field("bank_address", e.bankAddress);
        upload("cancelled_cheque", "chequePdfPath");
        saveAndContinue();
    }

    public void professional(EorEmployee e) {
        gotoStep("Professional Information");
        if (alreadyFilled(e.experienced() ? "work_experience.0.previous_employer" : "primary_skills")) {
            return;
        }
        field("uan_no", e.uan);
        if (!driver.findElements(By.xpath("//input[@name='esi_acc_no']")).isEmpty()) {
            field("esi_acc_no", e.esic);
        }
        typeInto(By.xpath("//input[@placeholder='Comma-separated, e.g. React, Node.js']"), e.primarySkills);
        typeInto(By.xpath("//input[@placeholder='Comma-separated']"), e.secondarySkills);
        field("languages.0.Language_known", e.language);
        choose("Proficiency", e.proficiency);
        if (e.experienced()) {
            previousEmployment(e);
        }
        saveAndContinue();
    }

    /**
     * The "Work Experience" block that the portal asks of an experienced employee: previous employer, designation, joining month, last working date, key
     * responsibilities and a document. The block is opened with "Add" when it is not on the page yet.
     */
    private void previousEmployment(EorEmployee e) {
        By employer = By.xpath("//input[@name='work_experience.0.previous_employer']");
        if (driver.findElements(employer).isEmpty()) {
            By add = By.xpath("(//*[normalize-space()='Work Experience']/following::button[normalize-space()='Add'])[1]");
            scrollTo(add);
            click(add);
        }
        field("work_experience.0.previous_employer", e.previousEmployer);
        field("work_experience.0.designation", e.previousDesignation);
        monthIn("work_experience.0.joining_date", e.previousFrom);
        dateIn("//input[@name='work_experience.0.last_working_date']", e.previousTo);
        typeInto(By.xpath("//textarea[@name='work_experience.0.key_responsibilities']"), e.previousResponsibilities);
        String doc = ConfigManager.get("workExperienceDocPath");
        List<WebElement> box = driver.findElements(By.xpath("//input[@name='work_experience.0.documents.0.upload_work_doc']"));
        if (!box.isEmpty()) {
            box.get(0).sendKeys(doc != null && !doc.isBlank() ? doc : ConfigManager.document("epfPdfPath"));
        }
    }

    /** A month box ("Select Month"): the month and the year of the date are set the way the browser's own month field takes them (yyyy-MM). */
    private void monthIn(String name, String dmy) {
        WebElement input = visible(By.xpath("//input[@name=" + Xp.literal(name) + "]"));
        String month = LocalDate.parse(dmy, DMY).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        ((JavascriptExecutor) driver).executeScript(
                "const i=arguments[0];const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;set.call(i,arguments[1]);"
                        + "i.dispatchEvent(new Event('input',{bubbles:true}));i.dispatchEvent(new Event('change',{bubbles:true}));",
                input, month);
    }

    public void education(EorEmployee e) {
        gotoStep("Educational Background");
        By addEdu = By.xpath("//section[.//h3[normalize-space()='Education']]//button[normalize-space()='Add']");
        scrollTo(addEdu);
        click(addEdu);
        chooseByLabel("Education Level", e.educationLevel);
        fillByLabel("Institution Name", e.educationInstitution);
        fillByLabel("Field of Study", e.fieldOfStudy);
        fillByLabel("University Name", e.university);
        chooseByLabel("Mode of Education", e.modeOfEducation);
        fillByLabel("Passing Year", e.passingYear);
        By addCert = By.xpath("//section[.//h3[normalize-space()='Certificates']]//button[normalize-space()='Add']");
        scrollTo(addCert);
        click(addCert);
        fillIn("//section[.//h3[normalize-space()='Certificates']]//label[normalize-space(translate(., '*', ''))='Certificate Name']/following::input[1]", e.certificateName);
        fillIn("//section[.//h3[normalize-space()='Certificates']]//label[normalize-space(translate(., '*', ''))='Institution Name']/following::input[1]", e.certificateInstitution);
        dateIn("//section[.//h3[normalize-space()='Certificates']]//label[normalize-space(translate(., '*', ''))='Issue Date']/following::input[1]", e.certificateIssueDate);
        upload("certificates.0.upload_certifications", "certificatefPath");
        saveAndContinue();
    }

    public void identification(EorEmployee e) {
        gotoStep("Identification Info");
        if (alreadyFilled("aadhar_no")) {
            return;
        }
        field("aadhar_no", e.aadhaar);
        field("pan_no", e.pan);
        field("licence_no", e.drivingLicence);
        field("passport_no", e.passport);
        upload("upload_aadhar_front", "aadhaarFrontPdfPath");
        upload("upload_aadhar_back", "aadhaarBackPdfPath");
        upload("upload_pancard", "panPdfPath");
        upload("upload_driving_licence", "drivingPdfPath");
        upload("upload_passport", "pasportPdfPath");
        for (String policy : new String[] {"Anti-Financial Fraud Declaration", "Prevention of Sexual Harassment (POSH) Policy", "Employee Declaration"}) {
            By view = By.xpath("//div[contains(@class,'rounded-[9px]')][.//div[normalize-space()=" + Xp.literal(policy) + "]]//button[normalize-space()='View & Acknowledge']");
            scrollTo(view);
            click(view);
            click(acknowledge);
        }
        saveAndContinue();
    }

    /** The window that says the profile is complete: "Done" ends the session. */
    public void finish() {
        click(done);
    }

    public boolean doneOffered(int seconds) {
        return isDisplayed(done, seconds);
    }

    // ------------------------------------------------------------------ offer

    /** Accepts the offer when it is offered within the given seconds; false when it is not there (yet). */
    public boolean acceptOfferIfAvailable(int seconds) {
        if (!isDisplayed(acceptOffer, seconds)) {
            return false;
        }
        By accepted = By.xpath("//*[contains(normalize-space(.),'You have accepted this offer')]");
        for (int attempt = 1; attempt <= 4; attempt++) {
            scrollTo(acceptOffer); // the contract is long: the button lies below it
            waitFor(timeout).until(d -> d.findElement(acceptOffer).isEnabled());
            click(acceptOffer);
            if (isDisplayed(accepted, 15)) {
                break; // the portal says "You have accepted this offer"
            }
            if (attempt == 4) {
                throw new AssertionError("The click on \"Accept Offer\" did not accept the offer: the page never said \"You have accepted this offer\". It shows: \"" + pageText() + "\"");
            }
        }
        if (isDisplayed(logout, 20)) {
            click(logout);
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Opens a step of the left menu ("ONBOARDING STEPS") unless its form is already shown. What was saved before stays in the form (it can be edited), and the portal
     * may open on a later step than the first, so every section starts by going to its own step.
     */
    private void gotoStep(String step) {
        By shown = By.xpath("//*[self::h2 or self::h3][normalize-space()=" + Xp.literal(step) + "]");
        if (isDisplayed(shown, 3)) {
            return;
        }
        By item = By.xpath("//button[contains(normalize-space(.)," + Xp.literal(step) + ")]");
        if (!driver.findElements(item).isEmpty()) {
            try {
                click(item);
                isDisplayed(shown, 8);
            } catch (TimeoutException locked) {
                // a step that is still locked (the section before it is not saved): the form on the page is the one to fill
            }
        }
    }

    /**
     * The section was filled in before (what was saved stays in the form): nothing is typed again, the page is only saved and continued.
     * True when the field has a value; the section method then returns after "Save and Continue".
     */
    private boolean alreadyFilled(String fieldName) {
        List<WebElement> f = driver.findElements(By.xpath("//input[@name=" + Xp.literal(fieldName) + "] | //textarea[@name=" + Xp.literal(fieldName) + "]"));
        if (f.isEmpty() || valueOf(f.get(0)).isBlank()) {
            return false;
        }
        List<WebElement> save = driver.findElements(saveContinue);
        if (save.isEmpty() || !save.get(0).isEnabled() || "true".equals(save.get(0).getAttribute("aria-disabled"))) {
            return false; // the saved data is not complete (the button stays off): the section is filled in
        }
        Log.info("The field \"" + fieldName + "\" already holds a saved value: the section is not filled in again, only saved and continued.");
        saveAndContinue();
        return true;
    }

    private void saveAndContinue() {
        closeToasts(); // the message "... saved." of the section before can cover the button
        By button = saveContinue;
        WebElement b = waitFor(timeout).until(d -> {
            List<WebElement> l = d.findElements(button);
            return l.isEmpty() || !l.get(0).isDisplayed() ? null : l.get(0);
        });
        utils.SmoothScroll.to(driver, b);
        try {
            b.click();
        } catch (org.openqa.selenium.WebDriverException covered) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click()", b); // something still lies over the button: the click goes to the button itself
        }
    }

    private void closeToasts() {
        for (WebElement x : driver.findElements(By.xpath("//button[@data-close-button='true' or @aria-label='Close toast']"))) {
            try {
                x.click();
            } catch (RuntimeException ignored) {
                // the message goes away on its own
            }
        }
    }

    private void scrollTo(By by) {
        utils.SmoothScroll.to(driver, visible(by));
    }

    private void field(String name, String value) {
        typeInto(By.xpath("//input[@name=" + Xp.literal(name) + "]"), value);
    }

    private void typeInto(By by, String value) {
        if (value == null) {
            return;
        }
        WebElement e = visible(by);
        utils.SmoothScroll.to(driver, e);
        e.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        e.sendKeys(value);
    }

    private void fillIn(String xpath, String value) {
        typeInto(By.xpath(xpath), value);
    }

    /** A label of the portal: its text may end with an asterisk for a required field ("Marital Status *"). */
    private static String lab(String label) {
        return "//label[normalize-space(translate(., '*', ''))=" + Xp.literal(label) + "]";
    }

    /** The fields of the page now: label and field name (written to the log when a section cannot be completed). */
    public String describeForm() {
        Object o = ((JavascriptExecutor) driver).executeScript(
                "return Array.from(document.querySelectorAll('label')).filter(l=>l.offsetParent!==null).map(l=>{const f=l.getAttribute('for');const c=(f&&document.getElementById(f))||l.parentElement.querySelector('input,button[role=combobox],select,textarea');"
                        + "return l.innerText.trim().replace(/\s+/g,' ')+' ['+(c?(c.getAttribute('name')||c.id||c.getAttribute('placeholder')||c.tagName):'?')+']';}).join(' | ')"
                        + "+' || sections: '+Array.from(document.querySelectorAll('h2,h3')).map(h=>h.innerText.trim()).join(' | ')+' || buttons: '+Array.from(document.querySelectorAll('button')).filter(b=>b.offsetParent!==null).map(b=>b.innerText.trim()).filter(t=>t).slice(0,25).join(' | ');");
        return String.valueOf(o);
    }

    private void fillByLabel(String label, String value) {
        typeInto(By.xpath("(" + lab(label) + "/following::input[1])[last()]"), value);
    }

    private void date(String name, String dmy) {
        dateIn("//input[@name=" + Xp.literal(name) + "]", dmy);
    }

    private void dateIn(String xpath, String dmy) {
        WebElement input = visible(By.xpath(xpath));
        DateInput.type(driver, input, LocalDate.parse(dmy, DMY));
    }

    private void fillDateByLabel(String label, String dmy) {
        dateIn("(" + lab(label) + "/following::input[1])[last()]", dmy);
    }

    /**
     * Gives the document to the upload box that belongs to the label. A document that is already attached (a box with a file name and a preview button instead
     * of the file input - seen when the same employee is filled in a second time) is left as it is.
     */
    private void attachByLabel(String label, String key) {
        By box = By.xpath(lab(label) + "/following-sibling::*[1]//input[@type='file'] | " + lab(label) + "/..//input[@type='file']");
        By attached = By.xpath(lab(label) + "/..//button[contains(@title,'Preview')]");
        waitFor(timeout).until(d -> !d.findElements(box).isEmpty() || !d.findElements(attached).isEmpty());
        List<WebElement> input = driver.findElements(box);
        if (!input.isEmpty()) {
            input.get(0).sendKeys(ConfigManager.document(key));
        }
    }

    /** Gives the document to the upload box of that field name; a document that is already attached (the box is then replaced by a preview) is left as it is. */
    private void upload(String name, String key) {
        List<WebElement> box = driver.findElements(By.xpath("//input[@name=" + Xp.literal(name) + "]"));
        if (box.isEmpty()) {
            Log.info("The upload box \"" + name + "\" is not on the page: the document is attached already.");
            return;
        }
        box.get(0).sendKeys(ConfigManager.document(key));
    }

    private void tickSameAsCurrent(boolean wanted) {
        By box = By.xpath("//button[@id='same-as-current']");
        WebElement b = visible(box);
        scrollTo(box);
        boolean ticked = "true".equalsIgnoreCase(b.getAttribute("aria-checked")) || "checked".equalsIgnoreCase(b.getAttribute("data-state"));
        if (ticked != wanted) {
            b.click();
        }
    }

    /** A drop-down chosen by the text of its label (a Radix list or a plain select). */
    private void choose(String label, String option) {
        chooseByLabel(label, option);
    }

    private void chooseByLabel(String label, String option) {
        chooseAt(label, option, 0);
    }

    /** {@code occurrence} 1, 2, ... = the first, second label of that text on the page (current and permanent address both have Country / State / City); 0 = the last one. */
    private void chooseAt(String label, String option, int occurrence) {
        if (option == null) {
            return;
        }
        String idx = occurrence <= 0 ? "last()" : String.valueOf(occurrence);
        By trigger = By.xpath("(" + lab(label) + "/following::*[self::button or self::select][1])[" + idx + "]");
        WebElement t = visible(trigger);
        utils.SmoothScroll.to(driver, t);
        waitFor(timeout).until(d -> t.isEnabled() && !"true".equals(t.getAttribute("aria-disabled"))); // State and City unlock after the Country is chosen
        if ("select".equalsIgnoreCase(t.getTagName())) {
            new Select(t).selectByVisibleText(option);
            return;
        }
        By opt = By.xpath("//*[@role='option' and normalize-space()=" + Xp.literal(option) + "] | //*[@cmdk-item and normalize-space()=" + Xp.literal(option) + "]");
        By search = By.xpath("//div[@data-radix-popper-content-wrapper]//input[contains(@placeholder,'Search')]");
        By any = By.xpath("(//*[@role='option' or @cmdk-item])[1]");
        // the entries of a list (State after the Country, City after the State) can take a while to appear: when the entry is not there, the drop-down is closed and opened again
        int attempts = ConfigManager.getInt("eor.dropdown.attempts", 5);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            t.click();
            if (isDisplayed(search, 2)) {
                WebElement box = driver.findElement(search);
                box.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
                box.sendKeys(option);
            }
            if (isDisplayed(opt, attempt == attempts ? 10 : 6)) {
                click(opt);
                return;
            }
            Log.info("The entry \"" + option + "\" of the list \"" + label + "\" did not appear (try " + attempt + " of " + attempts + "): the list is closed and opened again.");
            closeList(t);
        }
        if ("City".equals(label)) {
            t.click();
            if (isDisplayed(any, 5)) {
                Log.warn("The city \"" + option + "\" is not offered by the portal; the first city of the list is taken instead.");
                click(any);
                return;
            }
        }
        throw new AssertionError("The list \"" + label + "\" did not offer \"" + option + "\" after " + attempts + " tries (closing and opening the list again).");
    }

    /** Closes an open drop-down (Escape, and a click on the trigger when it is still open). */
    private void closeList(WebElement trigger) {
        try {
            trigger.sendKeys(Keys.ESCAPE);
            if ("true".equals(trigger.getAttribute("aria-expanded"))) {
                trigger.click();
            }
            Thread.sleep(1000); // documented: the list needs a moment to close before it is opened again
        } catch (RuntimeException | InterruptedException ignored) {
            // the next try opens it anyway
        }
    }

    /** What the page shows right now (for the report when something is missing). */
    public String pageText() {
        return bodyText().replaceAll("\\s+", " ").trim();
    }

    /** The labels of the form fields that are visible now (used to tell what a section asks for). */
    public List<String> labels() {
        List<String> out = new ArrayList<>();
        for (WebElement l : driver.findElements(By.xpath("//label"))) {
            String t = l.getText().trim();
            if (!t.isEmpty() && l.isDisplayed()) {
                out.add(t);
            }
        }
        Log.info("Fields of the page: " + out);
        return out;
    }
}
