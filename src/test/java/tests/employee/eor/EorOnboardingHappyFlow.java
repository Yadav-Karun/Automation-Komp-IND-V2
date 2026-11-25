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
 *   TC-EOR-HF-03  edit flow of employee 1 or 2 (random): not written yet, reported as "not run"
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
    private EorHappyFlowSteps admin;
    private final List<WebDriver> candidateBrowsers = Collections.synchronizedList(new java.util.ArrayList<>());

    @Override
    protected void browserReady() {
        admin = new EorHappyFlowSteps(driver);
    }

    // TC-EOR-HF-00 - the environment, sign in as the EOR administrator, Employees > EOR > Bulk Upload: the workbook of the two employees is uploaded.
    @Test(priority = 1)
    public void tc00_bulkUpload() {
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

    // TC-EOR-HF-03 - the edit flow of employee 1 or 2 (random): not implemented yet.
    @Test(priority = 3, dependsOnMethods = {"tc01_experiencedMale", "tc02_fresherFemale"}, alwaysRun = true)
    public void tc03_editFlow() {
        int target = new Random().nextBoolean() ? 1 : 2; // 50% each: the edit case belongs to the first or the second employee of this run
        TestReporter rep = TestReporter.start("TC-EOR-HF-03", G_EDIT, "EOR happy flow: edit of one of the two employees (not written yet)",
                "The edit case depends on the first or the second employee (chosen at random, 50% each); its steps will be agreed after the first two cases work", "-",
                "See the case when it is written", driver, holder("(edit flow)"), () -> "EOR happy flow | edit flow of the employee of TC-EOR-HF-0" + target);
        SkipException skip = new SkipException("Not run: the edit flow is not written yet (the owner will describe its steps after the first two cases). This run would have edited the employee of TC-EOR-HF-0" + target + ".");
        rep.finish("SKIP", skip);
        throw skip;
    }

    // ------------------------------------------------------------------ one employee, one browser

    private void journey(int index, String id, String title) {
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
