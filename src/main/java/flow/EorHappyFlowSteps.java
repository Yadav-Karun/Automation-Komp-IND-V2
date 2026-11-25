package flow;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.Assert;

import model.EorEmployee;
import model.EorRun;
import pages.AdminHomePage;
import pages.AorDialog;
import pages.EorCandidatePortalPage;
import pages.EorCandidatesPage;
import pages.EorContractsPage;
import pages.EorEmployeesPage;
import pages.ImapMailbox;
import pages.LoginPage;
import reporting.ExecutionInfo;
import reporting.TestReporter;
import reporting.ValueChecks;
import utils.ConfigManager;
import utils.EorDataGenerator;
import utils.EorHappyFlowWorkbook;
import utils.HappyFlowData;
import utils.PlainText;
import utils.SecretMasker;
import utils.TableReader;

/**
 * The steps of the EOR happy flow. One object per browser: the two employees run in parallel, each with its own browser and its own EorHappyFlowSteps,
 * so nothing is shared between them. Every step waits for the page before it goes on; the report picture is taken after the page has loaded.
 */
public class EorHappyFlowSteps {

    private final WebDriver driver;
    private final LoginPage login;
    private final EorEmployeesPage employees;
    private final EorCandidatesPage candidates;
    private final EorContractsPage contracts;
    private final EorCandidatePortalPage portal;
    private final ImapMailbox mailbox;
    private TestReporter rep;
    private EorRun run;
    private String portalLink = "";

    public EorHappyFlowSteps(WebDriver driver) {
        this.driver = driver;
        login = new LoginPage(driver);
        employees = new EorEmployeesPage(driver);
        candidates = new EorCandidatesPage(driver);
        contracts = new EorContractsPage(driver);
        portal = new EorCandidatePortalPage(driver);
        mailbox = new ImapMailbox(driver);
    }

    public void setReporter(TestReporter reporter) {
        rep = reporter;
    }

    public void useRun(EorRun run) {
        this.run = run;
    }

    public EorRun run() {
        return run;
    }

    private void picture(String label, String caption) {
        rep.evidence(label, caption);
    }

    private static boolean has(String text, String word) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(word.toLowerCase(Locale.ROOT));
    }

    private static int seconds(String key, int normal) {
        return ConfigManager.getInt(key, normal);
    }

    // the Letter of Intent mail is sometimes sent by the application much later (seen: 1 to 1.5 hours): the wait can be changed with -Deor.mail.wait.seconds
    private static int mailWait() {
        return seconds("eor.mail.wait.seconds", 7200);
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms); // the lists are filled a moment after the step before
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ STEP 1: URL and login

    public String openApplication() {
        driver.get(ConfigManager.baseUrl() + "/login");
        Assert.assertTrue(login.isDisplayed(), "The login page of " + ConfigManager.baseUrl() + " did not show the sign-in form.");
        return "The login page of \"" + ConfigManager.env() + "\" (" + ConfigManager.baseUrl() + ") is open.";
    }

    public String signInAdmin() {
        login.freshLogin(ConfigManager.baseUrl());
        login.login(ConfigManager.eorEmail(), ConfigManager.eorPassword());
        Assert.assertTrue(employees.sidebarVisible(), "After the sign-in the menu \"Employees\" is not shown.");
        return "Signed in as " + SecretMasker.maskEmail(ConfigManager.eorEmail()) + ".";
    }

    public String signOutAdmin() {
        Assert.assertTrue(new AdminHomePage(driver).signOut(), "After \"Log out\" the login page did not appear.");
        return "Logged out: the login page is shown.";
    }

    // ------------------------------------------------------------------ STEP 2: bulk upload

    public String openEmployees() {
        employees.open();
        return "Employees > EOR is open.";
    }

    public String openBulkUpload() {
        employees.openBulkUpload();
        return "The window \"Bulk Upload Employees\" is open.";
    }

    // two fake employees (1: Experienced + Male, 2: Fresher + Female) and the workbook with them
    public void prepareWorkbook(ValueChecks checks) throws Exception {
        run = HappyFlowData.newRun();
        Path file = ExecutionInfo.root().resolve("uploads").resolve(run.runId + "_EOR_Onboarding.xlsx");
        EorHappyFlowWorkbook.write(run, file);
        run.workbook = file.getFileName().toString();

        List<Map<String, String>> rows = utils.EorWorkbooks.readRows(file);
        checks.check("Workbook", "Number of employees (row 2 and row 3)", rows.size() == 2, "2", String.valueOf(rows.size()));
        for (int i = 0; i < rows.size(); i++) {
            EorEmployee e = run.employees.get(i);
            Map<String, String> row = rows.get(i);
            String who = "Row " + (i + 2) + " (" + e.fullName() + ")";
            checks.eq(who, "Work experience", e.experience, row.get("work experience"));
            checks.eq(who, "Gender", e.gender, row.get("gender"));
            checks.eq(who, "E-mail", e.email, row.get("email"));
            checks.eq(who, "Middle name (no field stays empty)", e.middleName, row.get("middle name"));
            checks.eq(who, "Working location", e.workingLocation, row.get("working location"));
            checks.eq(who, "Monthly CTC", "20000", row.get("monthly ctc"));
            checks.eq(who, "Annual CTC", "240000", row.get("annual ctc"));
            checks.check(who, "Department and joining date are filled", !row.get("department").isBlank() && !row.get("joining date").isBlank(), "both filled",
                    row.get("department") + " / " + row.get("joining date"));
        }
        EorDataGenerator.book(run); // the addresses are written to testdata/eor-used-emails.txt: the next run goes on with the next number
    }

    public String uploadWorkbook() {
        employees.attach(ExecutionInfo.root().resolve("uploads").resolve(run.workbook));
        AorDialog.Answer answer = employees.clickUploadAndWait(seconds("eor.upload.wait.seconds", 120));
        Assert.assertFalse(AorDialog.tellsOfProblem(answer.everything()), "The upload was not accepted. The application answered: \"" + PlainText.oneLine(answer.everything(), 400) + "\"");
        Assert.assertTrue(has(answer.everything(), "Created 2"), "The application did not say \"Created 2\". It answered: \"" + PlainText.oneLine(answer.everything(), 400) + "\"");
        return "The application answered: \"" + PlainText.oneLine(answer.everything(), 400) + "\"";
    }

    public String closeUploadWindow() {
        employees.dialog().dismiss();
        return "The upload window is closed.";
    }

    // ------------------------------------------------------------------ STEP 3 and 4: Candidates and Contract

    // Candidates > search; the list is filled a moment after the upload, so the page is reloaded until the row is there
    private List<Map<String, String>> findCandidate(EorEmployee e) {
        candidates.open();
        List<Map<String, String>> rows = candidates.filterBy(e.email);
        long end = System.currentTimeMillis() + seconds("eor.status.wait.seconds", 120) * 1000L;
        while (rows.isEmpty() && System.currentTimeMillis() < end) {
            sleep(3000);
            driver.navigate().refresh();
            candidates.open();
            rows = candidates.filterBy(e.email);
        }
        return rows;
    }

    // wait until a column of the candidate's row shows the word (the page is reloaded now and then)
    private boolean columnBecomes(EorEmployee e, String column, String word) {
        long end = System.currentTimeMillis() + seconds("eor.status.wait.seconds", 120) * 1000L;
        while (!candidates.columnShows(e.email, column, word)) {
            if (System.currentTimeMillis() > end) {
                return false;
            }
            sleep(3000);
            driver.navigate().refresh();
            candidates.open();
            candidates.filterBy(e.email);
        }
        return true;
    }

    public void candidateIsPending(EorEmployee e, ValueChecks c) {
        List<Map<String, String>> rows = findCandidate(e);
        c.check(e.fullName(), "Found in the list of candidates", !rows.isEmpty(), "one row", rows.size() + " row(s)");
        if (rows.isEmpty()) {
            return;
        }
        String loi = candidates.cell(e.email, "LOI STATUS");
        String profile = candidates.cell(e.email, "PROFILE STATUS");
        String offer = candidates.cell(e.email, "OFFER LETTER");
        c.check(e.fullName(), "LOI Status", has(loi, "pending"), "Pending", "\"" + loi + "\"");
        c.check(e.fullName(), "Profile Status", has(profile, "pending"), "Pending", "\"" + profile + "\"");
        c.check(e.fullName(), "Offer Letter", offer.isBlank() || offer.trim().matches("[-\\u2013\\u2014]+"), "-", "\"" + offer + "\"");
    }

    // MSA & Contract > Contract > search; the status of the contract must show the word
    public void contractStatus(EorEmployee e, String word, ValueChecks c) {
        contracts.open();
        List<Map<String, String>> rows = contracts.search(e.email);
        long end = System.currentTimeMillis() + seconds("eor.status.wait.seconds", 120) * 1000L;
        while (rows.isEmpty() && System.currentTimeMillis() < end) {
            sleep(3000);
            driver.navigate().refresh();
            contracts.open();
            rows = contracts.search(e.email);
        }
        c.check(e.fullName(), "Found in MSA & Contract > Contract", !rows.isEmpty(), "one row", rows.size() + " row(s)");
        if (!rows.isEmpty()) {
            String status = TableReader.column(rows.get(0), "STATUS");
            c.check(e.fullName(), "Status of the contract", has(status, word), word, "\"" + status + "\"");
        }
    }

    // ------------------------------------------------------------------ STEP 5: letter of intent, candidate login, candidate portal

    // Gmail (IMAP) > Letter of intent > the link "Accept Offer" is opened in a new tab, shown after it has loaded, and the tab is closed
    public String acceptLetterOfIntent(EorEmployee e) {
        mailbox.open(e.email);
        mailbox.readMail("Letter of intent", mailWait());
        String link = "";
        for (String l : mailbox.links()) {
            if (has(l, "accept")) {
                link = l.substring(l.indexOf("->") + 2).trim();
            }
        }
        Assert.assertFalse(link.isEmpty(), "The mail \"Letter of intent\" has no link \"Accept Offer\". Links: " + mailbox.links());

        String firstTab = driver.getWindowHandle();
        driver.switchTo().newWindow(WindowType.TAB);
        String newTab = driver.getWindowHandle();
        String text;
        try {
            driver.get(link);
            new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> !d.findElements(By.xpath("//*[contains(normalize-space(.),'Congratulations')]")).isEmpty());
            text = driver.findElement(By.tagName("body")).getText();
            picture("Accept Offer page", "The page of the link \"Accept Offer\", loaded completely");
        } finally {
            driver.switchTo().window(newTab);
            driver.close();
            driver.switchTo().window(firstTab);
        }
        return "The Letter of Intent was accepted. The page says: \"" + PlainText.oneLine(text, 200) + "\"";
    }

    // Candidates (hard refresh: the cache is cleared first) > search: LOI Status = Accepted
    public void loiIsAccepted(EorEmployee e, ValueChecks c) {
        ((ChromiumDriver) driver).executeCdpCommand("Network.clearBrowserCache", Map.of());
        driver.navigate().refresh();
        candidates.open();
        candidates.filterBy(e.email);
        boolean accepted = columnBecomes(e, "LOI STATUS", "accepted");
        c.check(e.fullName(), "LOI Status", accepted, "Accepted", "\"" + candidates.cell(e.email, "LOI STATUS") + "\"");
    }

    // Gmail (IMAP) > Candidate Login: the password and the portal link are stored
    public String readCandidateLogin(EorEmployee e) {
        mailbox.open(e.email);
        e.password = mailbox.readLoginPassword(mailWait());
        portalLink = mailbox.portalLink();
        return "The mail \"Candidate Login\" arrived. Password: " + SecretMasker.reveal(e.password) + ". Portal link: " + portalLink;
    }

    // a new tab, the stored link is opened, the candidate signs in; the tab of the administrator is closed
    public String candidateSignIn(EorEmployee e) {
        String adminTab = driver.getWindowHandle();
        driver.switchTo().newWindow(WindowType.TAB);
        String candidateTab = driver.getWindowHandle();
        driver.switchTo().window(adminTab);
        driver.close();
        driver.switchTo().window(candidateTab);
        if (!portalLink.isEmpty()) {
            driver.get(portalLink);
        }
        portal.signIn(e.email, e.password);
        return "The candidate " + e.fullName() + " signed in (the browser is at " + driver.getCurrentUrl() + ").";
    }

    public String startNow() {
        Assert.assertTrue(portal.startNowOffered(30), "The welcome page with the button \"Start Now\" did not appear.");
        picture("Welcome onboard", "The welcome page of the candidate portal");
        portal.startNow();
        return "\"Start Now\" was clicked.";
    }

    // a picture of every section after it is filled in and before "Save & Continue" is clicked
    public void pictureBeforeSave() {
        portal.beforeSave(section -> picture(section + " - filled", "\"" + section + "\" is filled in; \"Save & Continue\" is not clicked yet"));
    }

    // after "Save & Continue" no message of the page may say that something is wrong (a refused field keeps the page on the same section)
    private void noErrorOnPage(String section) {
        sleep(1500);
        List<String> errors = new java.util.ArrayList<>();
        for (org.openqa.selenium.WebElement m : driver.findElements(By.xpath("//p[contains(@class,'text-destructive')]"))) {
            if (m.isDisplayed() && !m.getText().isBlank()) {
                errors.add(m.getText().trim());
            }
        }
        Assert.assertTrue(errors.isEmpty(), "\"" + section + "\" was not saved: the page says " + errors);
    }

    public String personal(EorEmployee e) {
        portal.personal(e);
        noErrorOnPage("Personal Information");
        return "Personal Information is filled in and saved.";
    }

    public String contact(EorEmployee e) {
        portal.contact(e);
        noErrorOnPage("Contact Information");
        return "Contact Information is filled in and saved.";
    }

    public String bank(EorEmployee e) {
        portal.bank(e);
        noErrorOnPage("Banking Details");
        return "Banking Details are filled in and saved.";
    }

    public String professional(EorEmployee e) {
        portal.professional(e);
        noErrorOnPage("Professional Information");
        return "Professional Information is filled in and saved" + (e.experienced() ? " (with the work experience)." : " (a fresher has no work experience).");
    }

    public String education(EorEmployee e) {
        portal.education(e);
        noErrorOnPage("Educational Background");
        return "Educational Background is filled in and saved.";
    }

    public String identification(EorEmployee e) {
        portal.identification(e);
        noErrorOnPage("Identification Details");
        return "Identification Details are filled in, the documents are uploaded, the three policies are acknowledged and the section is saved.";
    }

    public String finishProfile(EorEmployee e) {
        Assert.assertTrue(portal.doneOffered(60), "The window that says the profile is complete (button \"Done\") did not appear.");
        picture("Profile complete", "The window that says the profile is complete");
        portal.finish();
        portal.logoutIfOffered(5);
        return "The profile is complete; the candidate is logged out.";
    }

    // ------------------------------------------------------------------ STEP 6: verify and offer letter

    // Candidates > search: Profile Status = In Progress > the three dots > View
    public void openCandidateProfile(EorEmployee e, ValueChecks c) {
        List<Map<String, String>> rows = findCandidate(e);
        c.check(e.fullName(), "Found in the list of candidates", !rows.isEmpty(), "one row", rows.size() + " row(s)");
        c.check(e.fullName(), "Profile Status", columnBecomes(e, "PROFILE STATUS", "progress"), "In Progress", "\"" + candidates.cell(e.email, "PROFILE STATUS") + "\"");
        picture("Candidate row", "The row of " + e.fullName() + " in the list of candidates");
        candidates.openRowView(e.email);
        c.check(e.fullName(), "The profile opens with the button \"Verify\"", employees.verifyOffered(), "\"Verify\" is shown", "checked");
        picture("Candidate profile", "The profile of " + e.fullName());
    }

    public String verify(EorEmployee e) {
        employees.clickVerify();
        return "\"Verify\" was clicked; the application is back on the list.";
    }

    // Candidates > search: Profile Status = Verified > tick the employee > Send Offer Letter > Offer Letter > Send > the offer letter is pending
    public void sendOfferLetter(EorEmployee e, ValueChecks c) {
        findCandidate(e);
        c.check(e.fullName(), "Profile Status", columnBecomes(e, "PROFILE STATUS", "verified"), "Verified", "\"" + candidates.cell(e.email, "PROFILE STATUS") + "\"");
        candidates.selectRow(e.email);
        picture("Employee ticked", "The box of " + e.fullName() + " is ticked");
        String template = candidates.sendOfferLetter(() -> picture("Choose offer letter", "The offer letter is chosen; \"Send\" is not clicked yet"));
        c.check(e.fullName(), "Offer Letter after \"Send\" (" + template + ")", columnBecomes(e, "OFFER LETTER", "pending"), "Pending", "\"" + candidates.cell(e.email, "OFFER LETTER") + "\"");
    }

    // ------------------------------------------------------------------ STEP 7: the candidate accepts the offer

    public String acceptOffer(EorEmployee e) {
        int attempts = seconds("eor.offer.attempts", 40);
        for (int i = 1; i <= attempts; i++) {
            portal.signIn(e.email, e.password);
            if (i == 1) {
                picture("Candidate signed in", "The candidate portal after the second sign-in");
            }
            if (portal.acceptOfferIfAvailable(5, () -> picture("Offer page", "The offer with the button \"Accept Offer\""))) {
                return "The offer was accepted (sign-in number " + i + ").";
            }
            if (portal.doneOffered(2)) {
                portal.finish();
            }
        }
        throw new AssertionError("The button \"Accept Offer\" did not appear for " + e.fullName() + " after " + attempts + " sign-ins.");
    }

    // ------------------------------------------------------------------ STEP 8: joined

    public String markJoined(EorEmployee e) {
        findCandidate(e);
        Assert.assertTrue(columnBecomes(e, "OFFER LETTER", "accepted"), "The list of candidates did not show the offer letter as accepted for " + e.fullName() + ".");
        candidates.selectRow(e.email);
        picture("Employee ticked", "The box of " + e.fullName() + " is ticked and \"Mark as Joined\" is enabled");
        candidates.markAsJoined();
        return e.fullName() + " was marked as joined.";
    }

    public void notACandidateAnyMore(EorEmployee e, ValueChecks c) {
        driver.navigate().refresh();
        candidates.open();
        List<Map<String, String>> rows = candidates.filterBy(e.email);
        long end = System.currentTimeMillis() + seconds("eor.status.wait.seconds", 120) * 1000L;
        while (!rows.isEmpty() && System.currentTimeMillis() < end) {
            sleep(3000);
            driver.navigate().refresh();
            candidates.open();
            rows = candidates.filterBy(e.email);
        }
        c.check(e.fullName(), "Not in the list of candidates any more", rows.isEmpty(), "no row", rows.size() + " row(s)");
    }

    public void employeeIsActive(EorEmployee e, ValueChecks c) {
        employees.open();
        List<Map<String, String>> rows = employees.search(e.email);
        long end = System.currentTimeMillis() + seconds("eor.status.wait.seconds", 120) * 1000L;
        while (rows.isEmpty() && System.currentTimeMillis() < end) {
            sleep(3000);
            driver.navigate().refresh();
            employees.open();
            rows = employees.search(e.email);
        }
        c.check(e.fullName(), "Found in Employees > EOR", !rows.isEmpty(), "one row", rows.size() + " row(s)");
        if (!rows.isEmpty()) {
            String status = employees.cell(e.email, "STATUS");
            c.check(e.fullName(), "Status in Employees > EOR", has(status, "active"), "Active", "\"" + status + "\"");
        }
    }
}
