package tests.employee.eor;

import java.util.ArrayList;
import java.util.List;

import org.testng.SkipException;
import org.testng.annotations.Test;

import base.BaseTest;
import flow.EorOnboardingFlow;
import model.ClientData;
import model.EorEmployee;
import model.EorRun;
import reporting.TestReporter;
import utils.ConfigManager;

/**
 * EMPLOYEE - EOR: ONBOARDING, HAPPY FLOW (positive path only; negative cases belong in a later suite). Cases in this order, each one needs the one before:
 *
 *   TC-EOR-01  EOR employee bulk upload: sign in as the administrator, Employees > EOR > Bulk Upload, a copy of the owner's workbook is filled with new employees and uploaded
 *   TC-EOR-02  Every employee accepts the Letter of Intent from the e-mail, signs in to the candidate portal and fills in the whole profile (an experienced employee also fills in the
 *              previous employment)
 *   TC-EOR-03  The administrator verifies every employee and sends the offer letter
 *   TC-EOR-04  Every employee accepts the offer in the candidate portal
 *   TC-EOR-05  The administrator marks every employee as joined; the employees are Active in Employees > EOR
 *   TC-EOR-06  The administrator opens the profile of every finished employee and compares every tab with what was entered (read-only: nothing is edited)
 *   TC-EOR-07  EDIT (user, 2026-11-19): the administrator opens the same employee (Employees > EOR > search > View), "Unmask all", changes every value of every tab and submits each tab, logs out
 *   TC-EOR-08  The administrator signs in again, unmasks and checks every tab against the changes; MSA & Contract > Contract > search > the contract is compared; logs out
 *   TC-EOR-09  The employee signs in with the login data of the mail "Welcome Aboard - Your Employee Login Details" and every detail of the own account is compared with the changed profile
 *
 * The workbook is the one of the QA owner ({@code employeeBulkUploadExcelPath} in config/documents.properties): only a COPY is filled. The administrator login is the EOR / admin login of
 * config/secrets.local.properties. The employees get yopmail addresses and cannot be deleted by automation: a run creates {@code -Deor.employees} (default 1, a fresher or an experienced employee chosen at random; at most 5) of them; their
 * e-mail addresses are kept in testdata/eor-used-emails.txt. An employee whose step failed is left out of the later steps; the other employees go on.
 *
 * Run:   mvn clean test -Dsuite=testng-eor-onboarding.xml
 */
public class EOROnboarding extends BaseTest {

    private static final String G_UPLOAD = "EOR bulk upload";
    private static final String G_PROFILE = "Candidate portal";
    private static final String G_VERIFY = "Verification and offer";
    private static final String G_OFFER = "Offer acceptance";
    private static final String G_JOIN = "Joining";
    private static final String G_PROFILE_CHECK = "Profile check";
    private static final String G_PROFILE_EDIT = "Profile edit";
    private static final String G_CONTRACT = "Check after the edit";
    private static final String G_EMPLOYEE = "Employee account";

    private EorOnboardingFlow flow;
    private EorRun run;
    private boolean uploaded;
    private final List<String> problems = new ArrayList<>();

    @Override
    protected void browserReady() {
        if (flow == null) {
            flow = new EorOnboardingFlow(driver);
            flow.setRestart(this::restartBrowser);
        } else {
            flow.useDriver(driver); // after a restart of the browser: the steps that are running keep their flow, only the browser is new
        }
        if (run != null) {
            flow.useRun(run);
        }
    }

    // TC-EOR-01 - Steps: sign in (EOR-01) -> Employees > EOR (EOR-02) -> Bulk Upload window (EOR-03) -> the workbook is filled with new employees and the file that was written is checked
    // (EOR-04) -> upload (EOR-05) -> every employee is found in the list of candidates (EOR-06). Expected: the application accepts the workbook and lists every employee.
    @Test(priority = 1)
    public void tc01_bulkUpload() throws Exception {
        String title = "EOR employee onboarding: new employees are uploaded with the onboarding workbook and appear in the list of candidates";
        if (!anyCaseSelected()) {
            skip("TC-EOR-01", G_UPLOAD, title, "-cases does not list this case");
        }
        String earlier = ConfigManager.get("eor.run.data");
        if (earlier != null) {
            // -Deor.run.data=<eor-run-data.json of an earlier run>: its employees have finished the onboarding (Active); the checks of the finished employees run on them
            run = new com.fasterxml.jackson.databind.ObjectMapper().readValue(java.nio.file.Files.newInputStream(java.nio.file.Paths.get(earlier)), EorRun.class);
            for (EorEmployee e : run.employees) {
                e.loiAccepted = e.profileFilled = e.verified = e.offerSent = e.offerAccepted = e.joined = true;
            }
            flow.useRun(run);
            uploaded = true;
            skip("TC-EOR-01", G_UPLOAD, title, "-Deor.run.data is set: the employees of the earlier run \"" + run.runId + "\" are used, nothing is uploaded");
        }
        String existing = ConfigManager.get("eor.existing");
        if (existing != null) {
            // -Deor.existing=<e-mail>|<password>: ONE employee that was uploaded and has accepted the Letter of Intent in an earlier run (no new employee is created; for tuning the candidate portal)
            String[] p = existing.split("\\|");
            run = utils.EorDataGenerator.newRun(1, 7);
            EorEmployee e = run.employees.get(0);
            e.email = p[0].trim().toLowerCase();
            e.password = p[1].trim();
            e.experience = p.length > 2 ? p[2].trim() : "Experienced";
            e.firstName = e.email.substring(0, 1).toUpperCase() + e.email.substring(1, e.email.indexOf('.'));
            e.lastName = "(existing)";
            e.loiAccepted = true;
            int first = firstCase();
            e.profileFilled = first >= 3; // a later case starts where the earlier run stopped
            e.verified = e.offerSent = first >= 4;
            e.offerAccepted = first >= 5;
            e.joined = first >= 6;
            run.client = "(existing employee)";
            flow.useRun(run);
            uploaded = true;
            skip("TC-EOR-01", G_UPLOAD, title, "-Deor.existing is set: the employee " + e.email + " of an earlier run is used, nothing is uploaded");
        }
        int n = Math.max(1, Math.min(5, ConfigManager.getInt("eor.employees", 1)));
        TestReporter rep = TestReporter.start("TC-EOR-01", G_UPLOAD, title,
                "Positive test: the administrator fills the supplied onboarding workbook with new EOR employees, uploads it and finds the employees in the list of candidates",
                "A copy of the supplied workbook with " + n + " new employees (random names, yopmail addresses; fresher or experienced chosen at random)",
                "The application accepts every employee; each employee is found in the list of candidates", driver, holder(), flow::headline);
        flow.setReporter(rep);
        try {
            rep.phase("tc_eor_01_sign_in", "Sign in as the administrator (EOR-01)", "The login succeeds and the start page with the menu is shown", flow::signInAdmin);
            rep.phase("tc_eor_01_open_employees", "Open Employees, then EOR (EOR-02)", "The list of employees opens", flow::openEmployees);
            rep.phase("tc_eor_01_open_bulk_upload", "Open Bulk Upload (EOR-03)", "The upload window opens and offers a box to choose the workbook", flow::openBulkUpload);
            rep.phaseChecks("tc_eor_01_fill_workbook", "Fill the onboarding workbook (EOR-04)",
                    "The workbook has one row per new employee with the values of the run; the supplied template is not changed", c -> flow.prepareWorkbook(c, n));
            run = flow.run();
            rep.phase("tc_eor_01_upload", "Upload the workbook (EOR-05)", "The application accepts the workbook and answers with the result of the upload", flow::uploadWorkbook);
            uploaded = true;
            rep.phaseChecks("tc_eor_01_listed", "Check that every employee is in the list of candidates (EOR-06)", "Every uploaded employee has a row in the list of candidates", flow::checkCandidatesListed);
        } finally {
            run = flow.run();
        }
        assertNoDefects(rep);
    }

    // TC-EOR-02 - per employee: the Letter of Intent is accepted in the mail and the password is read (EOR-07), then the employee signs in to the candidate portal and fills in the personal,
    // contact, bank, professional, educational and identification information (EOR-08). An experienced employee also fills in the previous employment.
    @Test(priority = 2)
    public void tc02_candidatePortal() throws Exception {
        String title = "EOR candidate portal: every employee accepts the Letter of Intent and completes the profile";
        if (!ready("TC-EOR-02", G_PROFILE, title, "TC-EOR-02")) {
            return;
        }
        TestReporter rep = TestReporter.start("TC-EOR-02", G_PROFILE, title,
                "Positive test: each uploaded employee opens the Letter of Intent mail, accepts it, signs in to the candidate portal with the password of the mail and fills in the whole profile",
                "The employees of the upload (" + run.employees.size() + "); data of the run", "Every employee reaches the completion window of the profile", driver, holder(), flow::headline);
        flow.setReporter(rep);
        int i = 0;
        for (EorEmployee e : run.employees) {
            i++;
            String who = "Employee " + i + " " + e.fullName() + " (" + e.experience + ")";
            try {
                if (e.password == null) rep.phase("tc_eor_02_loi_" + i, who + ": accept the Letter of Intent and read the login password (EOR-07)", "The mail arrives, \"Accept Offer\" says \"Congratulations\" and the mail with the password arrives",
                        () -> flow.acceptLetterOfIntent(e));
                rep.phaseChecks("tc_eor_02_profile_" + i, who + ": fill in the profile in the candidate portal (EOR-08)",
                        "All six sections are filled in and saved, and the window that says the profile is complete is shown", c -> flow.fillProfile(e, c));
            } catch (SkipException lost) {
                throw lost;
            } catch (Throwable t) {
                failed(e, "the candidate portal", t);
            }
        }
        finish(rep);
    }

    // TC-EOR-03 - per employee: the administrator verifies the employee in Employees > EOR (EOR-09) and sends the offer letter in Candidates (EOR-10).
    @Test(priority = 3)
    public void tc03_verifyAndOffer() throws Exception {
        String title = "EOR verification: the administrator verifies every employee and sends the offer letter";
        if (!ready("TC-EOR-03", G_VERIFY, title, "TC-EOR-03")) {
            return;
        }
        TestReporter rep = TestReporter.start("TC-EOR-03", G_VERIFY, title,
                "Positive test: the administrator opens the profile of each employee, verifies it, and sends the offer letter from the list of candidates",
                "The employees whose profile is complete", "Every employee is verified and has an offer letter with the status pending", driver, holder(), flow::headline);
        flow.setReporter(rep);
        rep.phase("tc_eor_03_sign_in", "Sign in as the administrator", "The login succeeds", flow::signInAdmin);
        int i = 0;
        for (EorEmployee e : run.employees) {
            i++;
            if (!e.profileFilled) {
                rep.note("Employee " + i + " " + e.fullName() + " is left out: " + e.failedAt);
                continue;
            }
            try {
                rep.phase("tc_eor_03_verify_" + i, "Employee " + i + " " + e.fullName() + ": verify the profile (EOR-09)", "The profile is verified", () -> flow.verifyEmployee(e));
                rep.phase("tc_eor_03_offer_" + i, "Employee " + i + " " + e.fullName() + ": send the offer letter (EOR-10)", "The offer letter is sent and its status is pending", () -> flow.sendOfferLetter(e));
            } catch (SkipException lost) {
                throw lost;
            } catch (Throwable t) {
                failed(e, "the verification or the offer letter", t);
            }
        }
        finish(rep);
    }

    // TC-EOR-04 - per employee: the employee signs in again and accepts the offer (EOR-11); the offer arrives a little after it was sent.
    @Test(priority = 4)
    public void tc04_acceptOffer() throws Exception {
        String title = "EOR offer: every employee accepts the offer letter in the candidate portal";
        if (!ready("TC-EOR-04", G_OFFER, title, "TC-EOR-04")) {
            return;
        }
        TestReporter rep = TestReporter.start("TC-EOR-04", G_OFFER, title, "Positive test: each employee signs in to the candidate portal and accepts the offer letter",
                "The employees that got an offer letter", "The button \"Accept Offer\" appears and the offer is accepted", driver, holder(), flow::headline);
        flow.setReporter(rep);
        int i = 0;
        for (EorEmployee e : run.employees) {
            i++;
            if (!e.offerSent) {
                rep.note("Employee " + i + " " + e.fullName() + " is left out: " + (e.failedAt.isEmpty() ? "no offer letter was sent" : e.failedAt));
                continue;
            }
            try {
                rep.phase("tc_eor_04_accept_" + i, "Employee " + i + " " + e.fullName() + ": accept the offer (EOR-11)", "The offer is accepted", () -> flow.acceptOffer(e));
            } catch (SkipException lost) {
                throw lost;
            } catch (Throwable t) {
                failed(e, "accepting the offer", t);
            }
        }
        finish(rep);
    }

    // TC-EOR-05 - per employee: the administrator marks the employee as joined in Candidates (EOR-12) and the employee is Active in Employees > EOR (EOR-13).
    @Test(priority = 5)
    public void tc05_join() throws Exception {
        String title = "EOR joining: the administrator marks every employee as joined and the employees are Active";
        if (!ready("TC-EOR-05", G_JOIN, title, "TC-EOR-05")) {
            return;
        }
        TestReporter rep = TestReporter.start("TC-EOR-05", G_JOIN, title, "Positive test: the administrator marks each employee that accepted the offer as joined and checks the status in the list of employees",
                "The employees that accepted the offer", "Every employee is marked as joined and is Active in Employees > EOR", driver, holder(), flow::headline);
        flow.setReporter(rep);
        rep.phase("tc_eor_05_sign_in", "Sign in as the administrator", "The login succeeds", flow::signInAdmin);
        int i = 0;
        for (EorEmployee e : run.employees) {
            i++;
            if (!e.offerAccepted) {
                rep.note("Employee " + i + " " + e.fullName() + " is left out: " + (e.failedAt.isEmpty() ? "the offer was not accepted" : e.failedAt));
                continue;
            }
            try {
                rep.phase("tc_eor_05_join_" + i, "Employee " + i + " " + e.fullName() + ": mark as joined (EOR-12)", "The employee is marked as joined", () -> flow.markJoined(e));
                rep.phase("tc_eor_05_active_" + i, "Employee " + i + " " + e.fullName() + ": the employee is Active (EOR-13)", "The status in Employees > EOR is Active", () -> flow.verifyActive(e));
            } catch (SkipException lost) {
                throw lost;
            } catch (Throwable t) {
                failed(e, "joining", t);
            }
        }
        finish(rep);
    }

    // TC-EOR-06 - per employee: Employees > EOR > eye button; every tab of the profile (Personal & Contact, Professional, Statutory Details, Documents, Employment Documents) after "Unmask all"
    // shows exactly what was entered (EOR-14).
    @Test(priority = 6)
    public void tc06_verifyProfiles() throws Exception {
        String title = "EOR profile: every tab of the finished employee shows exactly what was entered";
        if (!ready("TC-EOR-06", G_PROFILE_CHECK, title, "TC-EOR-06")) {
            return;
        }
        TestReporter rep = TestReporter.start("TC-EOR-06", G_PROFILE_CHECK, title,
                "Positive test: for each employee that joined, the administrator opens Employees > EOR > View, unmasks all values and compares every tab with what was entered",
                "The employees that joined; the values of the workbook row and of the candidate portal", "Every value of every tab equals what was entered", driver, holder(), flow::headline);
        flow.setReporter(rep);
        rep.phase("tc_eor_06_sign_in", "Sign in as the administrator", "The login succeeds", flow::signInAdmin);
        int i = 0;
        for (EorEmployee e : run.employees) {
            i++;
            if (!e.joined) {
                rep.note("Employee " + i + " " + e.fullName() + " is left out: the employee did not join.");
                continue;
            }
            try {
                rep.phaseChecks("tc_eor_06_profile_" + i, "Employee " + i + " " + e.fullName() + ": check every tab of the profile (EOR-14)",
                        "Personal & Contact, Professional, Statutory Details, Documents and Employment Documents show what was entered", c -> flow.verifyProfile(e, c));
            } catch (SkipException lost) {
                throw lost;
            } catch (Throwable t) {
                failed(e, "the check of the profile", t);
            }
        }
        finish(rep);
    }

    private static final String[] PROFILE_TABS = {"Personal & Contact", "Professional", "Statutory Details", "Documents", "Employment Documents"};

    // TC-EOR-07 - per employee, after the onboarding: Employees > EOR > search > View, "Unmask all", then every tab in turn: every block gets its Edit button, every value that can be changed is
    // changed and submitted (Personal & Contact, Professional, Statutory Details, Documents; Employment Documents only lists documents and has no Edit button). Then the administrator logs out.
    @Test(priority = 7)
    public void tc07_editProfiles() throws Exception {
        String title = "EOR profile edit: every value of every tab can be changed and the profile shows the new values";
        if (!ready("TC-EOR-07", G_PROFILE_EDIT, title, "TC-EOR-07")) {
            return;
        }
        TestReporter rep = TestReporter.start("TC-EOR-07", G_PROFILE_EDIT, title,
                "Positive test: for each employee that joined the administrator opens Employees > EOR > View, unmasks all values, changes every value of every tab, submits each tab and logs out",
                "The employees that joined; changed values (a digit counted up, a suffix added, another entry chosen from a list)", "Every changed value is shown with its new content", driver, holder(), flow::headline);
        flow.setReporter(rep);
        rep.phase("tc_eor_07_sign_in", "Sign in as the administrator", "The login succeeds", flow::signInAdmin);
        int i = 0;
        for (EorEmployee e : run.employees) {
            i++;
            if (!e.joined) {
                rep.note("Employee " + i + " " + e.fullName() + " is left out: the employee did not join.");
                continue;
            }
            String who = "Employee " + i + " " + e.fullName();
            try {
                rep.phase("tc_eor_07_open_" + i, who + ": Employees > EOR, search the employee, View, \"Unmask all\"", "The profile opens and every value is shown", () -> flow.openProfileForEdit(e));
                int t = 0;
                for (String tab : PROFILE_TABS) {
                    t++;
                    rep.phaseChecks("tc_eor_07_" + i + "_tab" + t, who + ": change every value of \"" + tab + "\" and submit",
                            "Every block accepts the change and the tab shows the new values", c -> flow.editTab(e, tab, c));
                }
            } catch (SkipException lost) {
                throw lost;
            } catch (Throwable t) {
                failed(e, "editing the profile", t);
            }
        }
        flow.save();
        rep.phase("tc_eor_07_sign_out", "Log out of the administrator console (profile menu, Log out)", "The login page is shown", flow::signOutAdmin);
        finish(rep);
    }

    // TC-EOR-08 - the administrator signs in again; per employee: Employees > EOR > search > View, "Unmask all", every tab is compared with the values of TC-EOR-07; then MSA & Contract > Contract >
    // search > click the row: the list row and the contract page are compared (name, designation, joining date as changed). Then the administrator logs out.
    @Test(priority = 8)
    public void tc08_checkAfterEdit() throws Exception {
        String title = "EOR after the edit: signing in again shows every changed value in the profile and in the contract";
        if (!ready("TC-EOR-08", G_CONTRACT, title, "TC-EOR-08")) {
            return;
        }
        TestReporter rep = TestReporter.start("TC-EOR-08", G_CONTRACT, title,
                "Positive test: after signing in again the administrator checks every tab of the profile and then the contract of each employee against the changes of TC-EOR-07",
                "The employees that joined; the values changed in TC-EOR-07", "Every value equals what was typed in the edit case; the contract shows the changed name, designation and joining date", driver, holder(), flow::headline);
        flow.setReporter(rep);
        rep.phase("tc_eor_08_sign_in", "Sign in as the administrator again", "The login succeeds", flow::signInAdmin);
        int i = 0;
        for (EorEmployee e : run.employees) {
            i++;
            if (!e.joined) {
                rep.note("Employee " + i + " " + e.fullName() + " is left out: the employee did not join.");
                continue;
            }
            String who = "Employee " + i + " " + e.fullName();
            try {
                rep.phaseChecks("tc_eor_08_profile_" + i, who + ": Employees > EOR > View, \"Unmask all\", check every tab",
                        "Personal & Contact, Professional, Statutory Details, Documents and Employment Documents show the changed values", c -> flow.verifyEditedProfile(e, c));
                rep.phaseChecks("tc_eor_08_contract_" + i, who + ": MSA & Contract > Contract, search the employee, open the contract and compare",
                        "The list row, the contract page and the unmasked salary breakup show the right values", c -> flow.verifyContract(e, c));
            } catch (SkipException lost) {
                throw lost;
            } catch (Throwable t) {
                failed(e, "the check after the edit", t);
            }
        }
        rep.phase("tc_eor_08_sign_out", "Log out of the administrator console (profile menu, Log out)", "The login page is shown", flow::signOutAdmin);
        finish(rep);
    }

    // TC-EOR-09 - per employee: the mail "Welcome Aboard - Your Employee Login Details" is opened (the employee name is checked), the employee signs in with the e-mail and the temporary password of the
    // mail, and every value of the employee's own account is compared with the changed profile. Then the employee logs out.
    @Test(priority = 9)
    public void tc09_employeeAccount() throws Exception {
        String title = "EOR employee account: the employee signs in with the data of the Welcome Aboard mail and sees the changed profile";
        if (!ready("TC-EOR-09", G_EMPLOYEE, title, "TC-EOR-09")) {
            return;
        }
        TestReporter rep = TestReporter.start("TC-EOR-09", G_EMPLOYEE, title,
                "Positive test: each employee opens the mail \"Welcome Aboard\", signs in with the login data of the mail and checks the details of the own account",
                "The employees that joined; the mail \"Welcome Aboard - Your Employee Login Details\"; the values changed in TC-EOR-07", "The account opens and every tab shows the same values as the administrator's profile", driver, holder(), flow::headline);
        flow.setReporter(rep);
        int i = 0;
        for (EorEmployee e : run.employees) {
            i++;
            if (!e.joined) {
                rep.note("Employee " + i + " " + e.fullName() + " is left out: the employee did not join.");
                continue;
            }
            String who = "Employee " + i + " " + e.fullName();
            try {
                rep.phase("tc_eor_09_mail_" + i, who + ": open the mail \"Welcome Aboard - Your Employee Login Details\"", "The mail is for this employee and gives the login data", () -> flow.readWelcomeMail(e));
                rep.phase("tc_eor_09_sign_in_" + i, who + ": sign in with the e-mail and the password of the mail", "The employee's own account opens", () -> flow.signInEmployee(e));
                rep.phaseChecks("tc_eor_09_account_" + i, who + ": compare every detail of the employee's account",
                        "The welcome line, the header and every tab show the same values as the administrator's profile", c -> flow.verifyEmployeeAccount(e, c));
                rep.phase("tc_eor_09_sign_out_" + i, who + ": log out of the employee's account", "The login page is shown", flow::signOutEmployee);
            } catch (SkipException lost) {
                throw lost;
            } catch (Throwable t) {
                failed(e, "the check of the employee's account", t);
            }
        }
        finish(rep);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean anyCaseSelected() {
        for (int n = 1; n <= 10; n++) {
            if (isSelected(String.format("TC-EOR-%02d", n))) {
                return true;
            }
        }
        return false;
    }

    /** The lowest case number listed in -Dcases (1 when all cases run). */
    private static int firstCase() {
        String f = ConfigManager.get("cases");
        if (f == null) {
            return 1;
        }
        int lowest = 10;
        for (String id : f.split(",")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)TC-EOR-0([0-9])").matcher(id.trim());
            if (m.find()) {
                lowest = Math.min(lowest, Integer.parseInt(m.group(1)));
            }
        }
        return lowest == 10 ? 1 : lowest;
    }

    /** True when the case can run; otherwise its row is written as "not run" (the same SkipException ends the method). */
    private boolean ready(String id, String group, String title, String... selectedBy) throws Exception {
        boolean wanted = false;
        for (String s : selectedBy) {
            wanted |= isSelected(s);
        }
        if (!wanted) {
            skip(id, group, title, "-cases does not list " + id);
        }
        if (flow != null && flow.stoppedOnPurpose()) {
            skip(id, group, title, "the run was stopped on purpose after the candidate signed in (-Deor.stop.after=candidate-login); the browser is left as it is");
        }
        if (!uploaded || run == null) {
            skip(id, group, title, "the employees were not uploaded (TC-EOR-01 did not get that far), so there is nobody to continue with");
        }
        return true;
    }

    private void skip(String id, String group, String title, String reason) {
        TestReporter skipped = TestReporter.start(id, group, title, "Positive test of the EOR employee onboarding", "-", "See the case when it is run", driver, holder(), flow::headline);
        SkipException skip = new SkipException("Not run in this run: " + reason + ".");
        skipped.finish("SKIP", skip);
        throw skip;
    }

    private void failed(EorEmployee e, String where, Throwable t) {
        e.failedAt = "a step of " + where + " failed earlier";
        problems.add(e.fullName() + ": " + where + " - " + String.valueOf(t.getMessage()).split("\\R")[0]);
        flow.save();
    }

    private void finish(TestReporter rep) {
        run = flow.run();
        flow.save();
        if (!problems.isEmpty()) {
            List<String> copy = new ArrayList<>(problems);
            problems.clear();
            org.testng.Assert.fail(copy.size() + " step(s) could not be completed:\n - " + String.join("\n - ", copy));
        }
        assertNoDefects(rep);
    }

    private ClientData holder() {
        ClientData holder = new ClientData(); // the report header needs a client record; the real header line is flow.headline()
        holder.legalName = run == null || run.client.isBlank() ? "(from the workbook)" : run.client;
        holder.clientType = "EOR";
        holder.email = ConfigManager.eorEmail();
        return holder;
    }
}
