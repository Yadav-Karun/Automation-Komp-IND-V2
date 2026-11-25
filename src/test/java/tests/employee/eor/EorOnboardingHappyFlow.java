package tests.employee.eor;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.openqa.selenium.WebDriver;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import base.BaseTest;
import base.DriverFactory;
import flow.EorHappyFlowSteps;
import flow.EorOnboardingFlow;
import model.ClientData;
import model.EorEmployee;
import model.EorRun;
import reporting.TestReporter;
import utils.ConfigManager;
import utils.SecretMasker;

/**
 * EOR onboarding happy flow ("EOROnboarding - HappyFlow"; a class name cannot have spaces or a hyphen). The older class EOROnboarding is not touched.
 *
 *   TC-EOR-HF-00  login, Employees > EOR > Bulk Upload with ONE workbook of two employees (row 2: Experienced + Male, row 3: Fresher + Female)
 *   TC-EOR-HF-01  the experienced man: from "Candidates: pending" to Active
 *   TC-EOR-HF-02  the fresher woman: the same journey (runs IN PARALLEL with TC-EOR-HF-01, each in its own browser)
 *   TC-EOR-HF-03  edit flow of employee 1 or 2 (random, 50% each): search in Employees > EOR, check every tab, edit every value of every tab, check the listing and the contract,
 *                 "Edit Contract", then the employee signs in with the mail "Welcome Aboard" and every tab is checked again
 *   Run only the edit case on the employees of an earlier run: -Deor.hf.run.data=<reports/EorOnboardingHappyFlow/<run>/eor-run-data.json> (TC-EOR-HF-00..02 are then written as "not run")
 *
 * Data is fake and new on every run (HappyFlowData). Mails come from the QA Gmail mailbox (IMAP); the +N numbers used are in testdata/eor-used-emails.txt.
 * Environment: -Denv=sandbox (default) | preprod | production. Run: mvn clean test -Dsuite=testng-eor-happy-flow.xml
 */
public class EorOnboardingHappyFlow extends BaseTest {

    private static final String G_SETUP = "EOR happy flow - upload";
    private static final String G_JOURNEY = "EOR happy flow - employee journey";
    private static final String G_EDIT = "EOR happy flow - edit";

    /** The two employees of the upload (set by TC-EOR-HF-00, read by the two parallel cases). */
    private volatile EorRun run;
    private volatile boolean earlierRun;
    private EorHappyFlowSteps admin;
    private final List<WebDriver> candidateBrowsers = Collections.synchronizedList(new java.util.ArrayList<>());

    @Override
    protected void browserReady() {
        admin = new EorHappyFlowSteps(driver);
    }

    // TC-EOR-HF-00 - the environment, sign in as the EOR administrator, Employees > EOR > Bulk Upload: the workbook of the two employees is uploaded.
    @Test(priority = 1)
    public void tc00_bulkUpload() {
        if (loadEarlierRun()) {
            notRun("TC-EOR-HF-00", G_SETUP, "EOR happy flow: both employees are uploaded with the onboarding workbook", "the employees of the earlier run \"" + run.runId + "\" are used, nothing is uploaded");
            return;
        }
        TestReporter rep = TestReporter.start("TC-EOR-HF-00", G_SETUP, "EOR happy flow: both employees are uploaded with the onboarding workbook",
                "Positive test: the administrator uploads one workbook with an experienced man and a fresher woman; every value that is not fixed is random",
                "A copy of the supplied workbook with 2 new employees (random names, e-mail addresses = the next variants of the QA mailbox)",
                "The application accepts both employees", driver, holder("(both employees)"),
                () -> "EOR happy flow | environment " + ConfigManager.env() + " | signed in as " + SecretMasker.maskEmail(ConfigManager.eorEmail()));
        admin.setReporter(rep);
        rep.phase("hf00_open_application", "Open the application of the chosen environment", "The login page of the environment opens", admin::openApplication);
        rep.phase("hf00_sign_in", "Sign in as the EOR administrator", "The login succeeds and the start page with the menu is shown", admin::signInAdmin);
        rep.phase("hf00_open_employees", "Open Employees, then EOR", "The list of employees opens", admin::openEmployees);
        rep.phase("hf00_open_bulk_upload", "Open Bulk Upload", "The window \"Bulk Upload Employees\" opens", admin::openBulkUpload);
        rep.phaseChecks("hf00_fill_workbook", "Fill the onboarding workbook with the two employees", "Row 2 = Experienced + Male, row 3 = Fresher + Female, all values filled; the template is not changed",
                admin::prepareWorkbook);
        run = admin.run();
        rep.phase("hf00_upload", "Upload the workbook", "The application accepts the workbook and says that both employees were created", admin::uploadWorkbook);
        rep.phase("hf00_close", "Close the upload window", "The window is closed", admin::closeUploadWindow);
        admin.saveRunData();
        assertNoDefects(rep);
    }

    // TC-EOR-HF-01 - employee 1: EXPERIENCED + MALE. Runs in parallel with TC-EOR-HF-02, in its own browser.
    @Test(priority = 2, dependsOnMethods = "tc00_bulkUpload")
    public void tc01_experiencedMale() {
        journey(0, "TC-EOR-HF-01", "EOR happy flow, experienced man: from the letter of intent to an Active employee");
    }

    // TC-EOR-HF-02 - employee 2: FRESHER + FEMALE. Runs in parallel with TC-EOR-HF-01, in its own browser.
    @Test(priority = 2, dependsOnMethods = "tc00_bulkUpload")
    public void tc02_fresherFemale() {
        journey(1, "TC-EOR-HF-02", "EOR happy flow, fresher woman: from the letter of intent to an Active employee");
    }

    // TC-EOR-HF-03 - the edit flow of employee 1 or 2 (random, 50% each), on the browser of the administrator:
    // search in Employees > EOR > View > every tab checked > every value of every tab edited (each block checked within 60 seconds) > the list > the contract > Edit Contract >
    // the mail "Welcome Aboard" > the employee signs in > every tab checked again with the changed values.
    @Test(priority = 3, dependsOnMethods = {"tc01_experiencedMale", "tc02_fresherFemale"}, alwaysRun = true)
    public void tc03_editFlow() throws Exception {
        String title = "EOR happy flow: edit one employee, check the profile, the list and the contract, then the employee's own account";
        EorEmployee e = chooseEmployee();
        if (e == null) {
            notRun("TC-EOR-HF-03", G_EDIT, title, run == null ? "the upload did not happen, so there is no employee to edit" : "neither employee of this run reached the status Active");
            return;
        }
        int number = run.employees.indexOf(e) + 1;
        closeCandidateBrowsers(); // the two candidate windows are finished; the administrator's window was behind them and the page was not drawn (the login form stayed invisible)
        driver.switchTo().window(driver.getWindowHandle());
        driver.manage().window().maximize();
        try {
            ((org.openqa.selenium.chromium.ChromiumDriver) driver).executeCdpCommand("Page.bringToFront", java.util.Map.of());
        } catch (RuntimeException ignored) {
            // the window is in front anyway
        }
        EorOnboardingFlow edit = new EorOnboardingFlow(driver); // the pages of the old flow (profile, contract) already know the edit blocks
        edit.useRun(run);
        edit.useImapMailbox();
        edit.setVerifySeconds(60);
        edit.setFieldAwareEdits(true);
        run.sheet = e.sheet; // the salary and the other values of the workbook row of THIS employee
        TestReporter rep = TestReporter.start("TC-EOR-HF-03", G_EDIT, title,
                "Positive test: the administrator opens the profile of employee " + number + " (" + e.fullName() + ", chosen at random), checks every tab, edits every value, then checks the list, the contract and the employee's own account",
                "The employee " + number + " of the upload: " + e.experience + ", " + e.gender + ", " + e.email,
                "Every changed value is shown in the profile within 60 seconds, in the list, in the contract and in the employee's own account", driver, holder(e.fullName()),
                () -> "EOR happy flow | edit flow of employee " + number + " | " + e.fullName() + " | " + SecretMasker.maskEmail(e.email) + " | environment " + ConfigManager.env());
        edit.setReporter(rep);

        rep.phase("hf03_sign_in", "Sign in as the EOR administrator", "The login succeeds", edit::signInAdmin);
        rep.phase("hf03_search", "Employees > EOR: search the employee", "The list shows the row of the employee", () -> edit.searchEmployee(e));
        rep.phase("hf03_open_profile", "Action > View: open the profile", "The profile of the employee opens", () -> edit.openProfileFromList(e));
        rep.phaseChecks("hf03_check_tabs", "Every tab (Personal & Contact, Professional, Statutory Details, Documents, Employment Documents): \"Unmask all\", then compare with what was entered",
                "Every tab shows what was entered; a full-page picture of every tab", c -> edit.verifyOpenProfile(e, c));

        String[] tabs = {"Personal & Contact", "Professional", "Statutory Details", "Documents"}; // Employment Documents has no Edit button
        for (int t = 0; t < tabs.length; t++) {
            String tab = tabs[t];
            rep.phaseChecks("hf03_edit_" + (t + 1), "Edit every value of \"" + tab + "\" (a name for a name, a number for a number) and submit each block",
                    "Every block is saved and the tab shows the new values within 60 seconds", c -> edit.editTab(e, tab, c));
        }

        rep.phaseChecks("hf03_list", "Employees > EOR: search the employee and compare the row of the list", "The row shows the changed name and designation, the code, client, project, manager and the status Active",
                c -> edit.verifyEmployeeListing(e, c));
        rep.phaseChecks("hf03_contract", "MSA & Contract > Contract: search the employee, open the contract, unmask the salary breakup and compare it with the workbook",
                "The list and the contract show the changed values; the salary breakup equals the workbook row", c -> edit.verifyContract(e, c));
        rep.phaseChecks("hf03_edit_contract", "Edit Contract: look at step 1, \"Save & Continue\", look at step 2 (Salary Breakup)", "Every visible detail of both steps is correct",
                c -> edit.verifyContractEdit(e, c));
        rep.phase("hf03_sign_out", "Log out of the EOR console", "The login page is shown", edit::signOutAdmin);

        rep.phase("hf03_welcome_mail", "Mail \"Welcome Aboard - Your Employee Login Details\": the login link, the e-mail and the password", "The mail is for this employee and gives the login data",
                () -> edit.readWelcomeMail(e));
        rep.phase("hf03_employee_sign_in", "The login link of the mail is opened in the first tab, the e-mail and the password are typed, Sign in", "The employee's own account opens",
                () -> edit.signInEmployee(e));
        rep.phaseChecks("hf03_employee_check", "The employee's account, every tab (\"Unmask all\" first): compare with the changed values",
                "Personal & Contact, Professional, Statutory Details, Documents and Employment Documents show the changed values", c -> edit.verifyEmployeeAccount(e, c));
        rep.phase("hf03_employee_sign_out", "Log out of the employee's account", "The login page is shown", edit::signOutEmployee);
        assertNoDefects(rep);
    }

    // 50% employee 1, 50% employee 2; when the chosen one did not join, the other one is used
    private EorEmployee chooseEmployee() {
        if (run == null || run.employees.size() < 2) {
            return null;
        }
        int first = new Random().nextBoolean() ? 0 : 1;
        EorEmployee e = run.employees.get(first);
        if (!e.joined) {
            e = run.employees.get(1 - first);
        }
        return e.joined ? e : null;
    }

    // -Deor.hf.run.data=<eor-run-data.json of an earlier run>: its employees are Active; nothing is uploaded and no journey is run
    private boolean loadEarlierRun() {
        String file = ConfigManager.get("eor.hf.run.data");
        if (file == null) {
            return false;
        }
        try {
            run = new com.fasterxml.jackson.databind.ObjectMapper().readValue(java.nio.file.Files.newInputStream(java.nio.file.Paths.get(file)), EorRun.class);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("-Deor.hf.run.data: " + file + " cannot be read: " + ex.getMessage(), ex);
        }
        for (EorEmployee e : run.employees) {
            e.joined = true;
        }
        earlierRun = true;
        return true;
    }

    // a case that is not run this time is still written to the report (as "not run" with the reason), so the numbering stays
    private void notRun(String id, String group, String title, String why) {
        TestReporter rep = TestReporter.start(id, group, title, "Not run this time", "-", "-", driver, holder("(not run)"), () -> "EOR happy flow | not run | environment " + ConfigManager.env());
        rep.finish("SKIP", new SkipException("Not run this time: " + why + "."));
    }

    // ------------------------------------------------------------------ one employee, one browser

    private void journey(int index, String id, String title) {
        if (earlierRun) {
            notRun(id, G_JOURNEY, title, "the employee of the earlier run is used: " + run.employees.get(index).fullName() + " (" + run.employees.get(index).email + ")");
            return;
        }
        EorEmployee e = run.employees.get(index);
        WebDriver d = DriverFactory.create(true); // its own browser, its own temporary profile
        candidateBrowsers.add(d);
        EorHappyFlowSteps s = new EorHappyFlowSteps(d);
        s.useRun(run);
        String n = id.toLowerCase().replace("tc-eor-", "").replace("-", "_"); // hf_01: picture and step names are unique per case (both cases write into one folder)
        TestReporter rep = TestReporter.start(id, G_JOURNEY, title,
                "Positive test: " + e.experience + " + " + e.gender + " employee " + e.fullName() + ": candidates, letter of intent, six sections of the candidate portal, verification, offer letter, offer, joining",
                "The employee of the upload: " + e.experience + ", " + e.gender + ", " + e.email, "The employee ends up Active in Employees > EOR and in MSA & Contract > Contract", d, holder(e.fullName()),
                () -> "EOR happy flow | " + e.experience + " + " + e.gender + " | " + e.fullName() + " | " + SecretMasker.maskEmail(e.email)
                        + (e.password == null ? "" : " | candidate password " + SecretMasker.reveal(e.password)) + " | environment " + ConfigManager.env());
        s.setReporter(rep);
        s.pictureBeforeSave();

        // step 1 and 3, 4 - the administrator, before the letter of intent
        rep.phase(n + "_open_application", "Open the application of the chosen environment", "The login page opens", s::openApplication);
        rep.phase(n + "_admin_sign_in", "Sign in as the EOR administrator", "The login succeeds", s::signInAdmin);
        rep.phaseChecks(n + "_candidate_pending", "Candidates: the employee is listed with LOI Status Pending, Profile Status Pending and Offer Letter \"-\"",
                "LOI Status = Pending, Profile Status = Pending, Offer Letter = -", c -> s.candidateIsPending(e, c));
        rep.phaseChecks(n + "_contract_pending", "MSA & Contract > Contract: the contract of the employee is Pending", "The status of the contract is Pending", c -> s.contractStatus(e, "pending", c));

        // step 5 - the letter of intent
        rep.phase(n + "_accept_loi", "Mail \"Letter of intent\": the link \"Accept Offer\" is opened in a new tab", "The page loads completely and says \"Congratulations\"; the tab is closed",
                () -> s.acceptLetterOfIntent(e));
        rep.phaseChecks(n + "_loi_accepted", "Candidates (hard refresh): LOI Status is Accepted", "LOI Status = Accepted", c -> s.loiIsAccepted(e, c));
        rep.phase(n + "_admin_sign_out", "Log out of the EOR console", "The login page is shown", s::signOutAdmin);
        rep.phase(n + "_candidate_login_mail", "Mail \"Candidate Login\": the password and the portal link are stored", "The mail arrives with a password", () -> s.readCandidateLogin(e));
        rep.phase(n + "_candidate_sign_in", "The candidate opens the stored link in a new tab and signs in", "The candidate portal opens with the welcome page", () -> s.candidateSignIn(e));
        rep.phase(n + "_start_now", "Welcome onboard: Start Now", "The first section opens", s::startNow);

        // the six sections (a picture of the filled form is taken before Save & Continue, one after the save)
        rep.phase(n + "_personal", "Personal Information", "Every field is filled; Save & Continue opens the next section", () -> s.personal(e));
        rep.phase(n + "_contact", "Contact Information", "Every field is filled; Save & Continue opens the next section", () -> s.contact(e));
        rep.phase(n + "_bank", "Banking Details", "Every field is filled; Save & Continue opens the next section", () -> s.bank(e));
        rep.phase(n + "_professional", "Professional Information", e.experienced() ? "Every field and the work experience are filled" : "Every field is filled (a fresher has no work experience)",
                () -> s.professional(e));
        rep.phase(n + "_education", "Educational Background (education and certificate)", "Both are added and filled; Save & Continue opens the next section", () -> s.education(e));
        rep.phase(n + "_identification", "Identification Details (documents and three acknowledgements)", "Every field is filled, the documents are uploaded and the three policies are acknowledged",
                () -> s.identification(e));
        rep.phase(n + "_finish_profile", "Profile complete: Done and log out", "The window that says the profile is complete is shown; the candidate is logged out", () -> s.finishProfile(e));

        // step 6 - verification and the offer letter
        rep.phase(n + "_admin_sign_in_2", "Sign in as the EOR administrator again", "The login succeeds", s::signInAdmin);
        rep.phaseChecks(n + "_contract_sent", "MSA & Contract > Contract: the contract of the employee is Sent", "The status of the contract is Sent", c -> s.contractStatus(e, "sent", c));
        rep.phaseChecks(n + "_candidate_profile", "Candidates: Profile Status In Progress; the three dots > View opens the profile", "The profile opens with the button \"Verify\"",
                c -> s.openCandidateProfile(e, c));
        rep.phase(n + "_verify", "Verify the profile", "The application returns to the list of candidates", () -> s.verify(e));
        rep.phaseChecks(n + "_send_offer", "Candidates: Profile Status Verified; tick the employee; Send Offer Letter > Offer Letter > Send", "The offer letter shows Pending", c -> s.sendOfferLetter(e, c));
        rep.phase(n + "_admin_sign_out_2", "Log out of the EOR console", "The login page is shown", s::signOutAdmin);

        // step 7 - the candidate accepts the offer
        rep.phase(n + "_accept_offer", "The candidate signs in again and accepts the offer", "The page says \"You have accepted this offer\"", () -> s.acceptOffer(e));

        // step 8 - joined
        rep.phase(n + "_admin_sign_in_3", "Sign in as the EOR administrator again", "The login succeeds", s::signInAdmin);
        rep.phase(n + "_mark_joined", "Candidates: tick the employee and click Mark as Joined", "The employee is marked as joined", () -> s.markJoined(e));
        rep.phaseChecks(n + "_not_candidate", "Candidates: the employee is not a candidate any more", "The search finds nobody", c -> s.notACandidateAnyMore(e, c));
        rep.phaseChecks(n + "_employee_active", "Employees > EOR: the employee is Active", "The status is Active", c -> s.employeeIsActive(e, c));
        rep.phaseChecks(n + "_contract_active", "MSA & Contract > Contract: the contract of the employee is Active", "The status of the contract is Active", c -> s.contractStatus(e, "active", c));
        s.saveRunData();
        assertNoDefects(rep);
    }

    /** The browsers of the two parallel cases are closed when the class ends (the report of a case still needs its browser until the case is written). */
    @AfterClass(alwaysRun = true)
    public void closeCandidateBrowsers() {
        synchronized (candidateBrowsers) {
            for (WebDriver d : candidateBrowsers) {
                DriverFactory.quit(d);
            }
            candidateBrowsers.clear();
        }
    }

    private ClientData holder(String who) {
        ClientData holder = new ClientData(); // the report header needs a client record; the real header line is given to TestReporter.start
        holder.legalName = who;
        holder.clientType = "EOR";
        holder.email = ConfigManager.eorEmail();
        return holder;
    }
}
