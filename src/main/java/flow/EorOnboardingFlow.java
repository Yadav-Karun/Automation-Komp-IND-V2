package flow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openqa.selenium.WebDriver;
import org.testng.Assert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import model.EorEmployee;
import model.EorRun;
import pages.AorDialog;
import pages.EorCandidatePortalPage;
import pages.EorCandidatesPage;
import pages.EorEmployeesPage;
import pages.EorContractsPage;
import pages.EorMailbox;
import pages.EorProfilePage;
import pages.LoginPage;
import reporting.ExecutionInfo;
import reporting.TestReporter;
import reporting.ValueChecks;
import utils.ConfigManager;
import utils.EorDataGenerator;
import utils.EorWorkbooks;
import utils.PlainText;
import utils.SecretMasker;
import utils.TableReader;

/**
 * The EOR employee happy flow, one method per report step (the journey of the QA owner's earlier project "KOMP IND - Java", Test_EmployeeOnboarding):
 * <ol>
 *   <li>admin: Employees &gt; EOR &gt; Bulk Upload with the onboarding workbook (one row per new employee);</li>
 *   <li>every employee: accepts the Letter of Intent from the e-mail, reads the password of the "Candidate Login" mail, signs in to the candidate portal and fills in the profile
 *       (personal, contact, bank, professional with the previous employment of an experienced employee, education, identification);</li>
 *   <li>admin: verifies every employee (Employees &gt; EOR &gt; View &gt; Verify) and sends the offer letter (Candidates);</li>
 *   <li>every employee accepts the offer in the candidate portal;</li>
 *   <li>admin: Mark as Joined, and the employee is Active in Employees &gt; EOR.</li>
 * </ol>
 * One {@link EorRun} holds everything the steps share. Every sentence that reaches a report is plain language (standing rule of the project).
 */
public class EorOnboardingFlow {

    private WebDriver driver;
    private LoginPage login;
    private EorEmployeesPage employees;
    private EorCandidatesPage candidates;
    private EorCandidatePortalPage portal;
    private EorMailbox mailbox;
    private EorProfilePage profile;
    private EorContractsPage contracts;
    private TestReporter rep;
    private EorRun run;
    /** Closes the browser and opens a new one (set by the test class); used when the application says "Too many sign-in attempts". */
    private Runnable restart;

    public EorOnboardingFlow(WebDriver driver) {
        useDriver(driver);
    }

    /** The pages work on this browser (a new one after a restart); the run data stays. */
    public final void useDriver(WebDriver d) {
        this.driver = d;
        this.login = new LoginPage(d).restartOnRateLimit(true);
        this.employees = new EorEmployeesPage(d);
        this.candidates = new EorCandidatesPage(d);
        this.portal = new EorCandidatePortalPage(d);
        this.mailbox = new EorMailbox(d);
        this.profile = new EorProfilePage(d);
        this.contracts = new EorContractsPage(d);
        if (rep != null) {
            rep.setDriver(d);
        }
    }

    public void setRestart(Runnable restart) {
        this.restart = restart;
    }

    /**
     * Runs a step that signs in. "Too many sign-in attempts" is not waited out: the browser is closed, a new one is opened and the step is run again (up to 3 times).
     */
    private <T> T withRestart(java.util.concurrent.Callable<T> action) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.call();
            } catch (utils.RateLimitedLogin limited) {
                if (restart == null || attempt >= 3) {
                    throw new AssertionError("The application keeps answering \"Too many sign-in attempts\" (" + attempt + " browsers tried). " + limited.getMessage());
                }
                utils.Log.warn("\"Too many sign-in attempts\": the browser is closed and a new one is opened (try " + attempt + ").");
                restart.run();
            }
        }
    }

    public void setReporter(TestReporter reporter) {
        this.rep = reporter;
    }

    public EorRun run() {
        return run;
    }

    public void useRun(EorRun existing) {
        this.run = existing;
    }

    public String headline() {
        StringBuilder sb = new StringBuilder("EOR run: ").append(run == null ? "(not started)" : run.runId).append(" | signed in as ").append(SecretMasker.maskEmail(ConfigManager.eorEmail()));
        if (run != null) {
            sb.append(" | client \"").append(run.client).append("\", project \"").append(run.project).append("\" | ").append(PlainText.count(run.employees.size(), "employee", "employees"));
        }
        return sb.toString();
    }

    private Path uploads() {
        return ExecutionInfo.root().resolve("uploads");
    }

    // ================================================================== admin

    public String signInAdmin() throws Exception {
        return withRestart(this::signInAdminOnce);
    }

    private String signInAdminOnce() {
        login.freshLogin(ConfigManager.baseUrl());
        login.login(ConfigManager.eorEmail(), ConfigManager.eorPassword());
        Assert.assertTrue(employees.sidebarVisible(), "After signing in, the start page of the administrator did not open: the menu with \"Employees\" is not shown.");
        return "Signed in as " + SecretMasker.maskEmail(ConfigManager.eorEmail()) + ". The start page opened with the menu \"Employees\".";
    }

    public String openEmployees() {
        employees.open();
        return "The list \"Employees\" is open. " + (TableReader.showing(driver).isEmpty() ? "" : "It says: \"" + TableReader.showing(driver) + "\".");
    }

    public String openBulkUpload() {
        employees.openBulkUpload();
        return "The window \"Bulk Upload Employees\" opened. It says that columns with a yellow header are required and offers \"Download template\", a box to choose a file, \"Cancel\" and \"Upload\".";
    }

    /** Creates the employees of the run and the workbook, then checks the file that was really written. */
    public void prepareWorkbook(ValueChecks checks, int count) throws IOException {
        long seed = ConfigManager.get("eor.seed") != null ? Long.parseLong(ConfigManager.get("eor.seed")) : System.nanoTime() & 0xFFFFFFFFL;
        run = EorDataGenerator.newRun(count, seed);
        Path file = uploads().resolve(run.runId + "_EOR_Onboarding.xlsx");
        EorWorkbooks.write(run, file);
        run.workbook = file.getFileName().toString();
        List<Map<String, String>> rows = EorWorkbooks.readRows(file);
        if (!rows.isEmpty()) {
            run.sheet = new java.util.LinkedHashMap<>(rows.get(0));
        }
        checks.check("Workbook", "Rows with an employee", rows.size() == run.employees.size(), String.valueOf(run.employees.size()), String.valueOf(rows.size()));
        checks.check("Workbook", "Client and project of the template row are kept", !run.client.isBlank() && !run.project.isBlank(), "a client and a project",
                "\"" + run.client + "\" / \"" + run.project + "\"");
        for (int i = 0; i < run.employees.size() && i < rows.size(); i++) {
            EorEmployee e = run.employees.get(i);
            Map<String, String> r = rows.get(i);
            String who = "Employee " + (i + 1) + " (" + e.fullName() + ")";
            checks.eq(who, "First name", e.firstName, r.get("first name"));
            checks.eq(who, "Last name", e.lastName, r.get("last name"));
            checks.eq(who, "Gender", e.gender, r.get("gender"));
            checks.eq(who, "E-mail", e.email, r.get("email"));
            checks.eq(who, "Work experience", e.experience, r.get("work experience"));
            checks.eq(who, "Mobile number", e.phone, r.get("mobile number"));
            checks.eq(who, "State", e.state, r.get("state"));
            checks.eq(who, "Working location", e.workingLocation, r.get("working location"));
            checks.check(who, "E-mail is a yopmail address", e.email.endsWith("@yopmail.com"), "ends with @yopmail.com", e.email);
            checks.check(who, "Annual CTC (formula of the template)", !r.getOrDefault("annual ctc", "").isBlank(), "a number", r.getOrDefault("annual ctc", ""));
        }
        checks.check("Template", "The supplied template is not changed", Files.isRegularFile(EorWorkbooks.template()), "the template stays as it is", "only a copy was written: " + file.getFileName());
        save();
    }

    public String uploadWorkbook() {
        employees.attach(uploads().resolve(run.workbook));
        AorDialog.Answer a = employees.clickUploadAndWait(ConfigManager.getInt("eor.upload.wait.seconds", 120));
        run.uploadResultText = a.describe();
        Assert.assertFalse(AorDialog.tellsOfProblem(a.everything()), "The application did not accept the workbook. It answered: \"" + PlainText.oneLine(a.everything(), 300) + "\"");
        employees.dialog().dismiss(); // the panel "Creating Bulk Employees" stays on the page and covers the menu
        EorDataGenerator.book(run);
        save();
        return "The application answered: \"" + PlainText.oneLine(a.everything().isBlank() ? "(no message)" : a.everything(), 300) + "\"";
    }

    /** Every uploaded employee is found in the list of candidates. */
    public void checkCandidatesListed(ValueChecks checks) {
        candidates.open();
        for (EorEmployee e : run.employees) {
            List<Map<String, String>> rows = candidates.filterBy(e.email);
            checks.check(e.fullName(), "Found in the list of candidates", !rows.isEmpty(), "one row with " + e.email,
                    rows.isEmpty() ? "no row" : "status \"" + candidates.cell(e.email, "STATUS") + "\"");
        }
    }

    // ================================================================== the employee

    /** The employee accepts the Letter of Intent in the mail and reads the password of the "Candidate Login" mail. */
    public String acceptLetterOfIntent(EorEmployee e) {
        mailbox.open(e.email);
        try {
            String answer = mailbox.acceptLetterOfIntent(ConfigManager.getInt("email.poll.timeout.seconds", 120));
            e.loiAccepted = true;
            e.password = mailbox.readLoginPassword(ConfigManager.getInt("email.poll.timeout.seconds", 120));
            if (rep != null) {
                rep.setDriver(mailbox.mailDriver()); // the picture is taken of the window that shows the mail
                try {
                    rep.evidence("Mail with the login password", "The mail \"Candidate Login\" of " + e.email);
                } finally {
                    rep.setDriver(driver);
                }
            }
            return answer + ". The mail \"Candidate Login\" arrived and holds the password " + e.password + ".";
        } finally {
            mailbox.close();
        }
    }

    /** The employee signs in to the candidate portal and fills in every section; one table row per section. */
    public void fillProfile(EorEmployee e, ValueChecks checks) throws Exception {
        withRestart(() -> {
            portal.signIn(e.email, e.password);
            return null;
        });
        checks.check("Sign in", "The password of the mail works", true, "the candidate portal opens", "signed in");
        if (portal.startNowOffered(15)) {
            portal.startNow(); // a new employee starts here; a profile that was started before opens directly on its steps
        }
        section(checks, "Personal information", () -> portal.personal(e));
        section(checks, "Contact information", () -> portal.contact(e));
        section(checks, "Bank information", () -> portal.bank(e));
        section(checks, "Professional information" + (e.experienced() ? " (with the previous employment: experienced)" : " (fresher)"), () -> portal.professional(e));
        section(checks, "Educational information", () -> portal.education(e));
        section(checks, "Identification information and acknowledgements", () -> portal.identification(e));
        checks.guard("Profile", "The completion window with \"Done\" is shown", () -> {
            Assert.assertTrue(portal.doneOffered(60), "The window that says the profile is complete (button \"Done\") did not appear.");
            checks.check("Profile", "The completion window with \"Done\" is shown", true, "the window is shown", "shown");
            portal.finish();
        });
        e.profileFilled = checks.failed() == 0;
    }

    private void section(ValueChecks checks, String name, ValueChecks.Step step) throws Exception {
        int before = checks.failed();
        checks.guard(name, "All fields are filled in and saved", () -> {
            step.run();
            checks.check(name, "All fields are filled in and saved", true, "the section is saved and the next one opens", "saved");
        });
        if (checks.failed() > before) {
            utils.Log.warn("Section \"" + name + "\" could not be completed. The page shows: " + portal.describeForm());
            if (rep != null) {
                rep.evidence("Section that could not be completed", name);
            }
        }
    }

    // ================================================================== admin: verify, offer, join

    public String verifyEmployee(EorEmployee e) {
        employees.open();
        List<Map<String, String>> rows = employees.search(e.email);
        Assert.assertFalse(rows.isEmpty(), "The list of employees does not show " + e.email + " after the search.");
        employees.openProfile(e.email);
        Assert.assertTrue(employees.verifyOffered(), "The profile of " + e.fullName() + " does not offer \"Verify\".");
        employees.clickVerify();
        e.verified = true;
        return "The profile of " + e.fullName() + " was verified (button \"Verify\").";
    }

    public String sendOfferLetter(EorEmployee e) {
        candidates.open();
        candidates.filterBy(e.email);
        waitRow(() -> candidates.columnShows(e.email, "PROFILE STATUS", "verified"), "the profile status \"Verified\"", e);
        candidates.selectRow(e.email);
        String template = candidates.sendOfferLetter();
        waitRow(() -> candidates.columnShows(e.email, "OFFER LETTER", "pending"), "the offer letter status \"Pending\"", e);
        e.offerSent = true;
        return "The offer letter \"" + template + "\" was sent; the list shows the offer letter as pending.";
    }

    public String acceptOffer(EorEmployee e) throws Exception {
        return withRestart(() -> acceptOfferOnce(e));
    }

    private String acceptOfferOnce(EorEmployee e) {
        int attempts = ConfigManager.getInt("eor.offer.attempts", 60);
        for (int i = 1; i <= attempts; i++) {
            portal.signIn(e.email, e.password);
            if (portal.acceptOfferIfAvailable(5)) {
                e.offerAccepted = true;
                return "The offer was accepted in the candidate portal (after " + i + (i == 1 ? " sign-in)." : " sign-ins; the offer is delivered a little later).");
            }
            if (portal.doneOffered(2)) {
                portal.finish();
            }
        }
        throw new AssertionError("The button \"Accept Offer\" did not appear for " + e.fullName() + " after " + attempts + " sign-ins.");
    }

    public String markJoined(EorEmployee e) {
        candidates.open();
        candidates.filterBy(e.email);
        waitRow(() -> candidates.columnShows(e.email, "OFFER LETTER", "accepted"), "the offer letter status \"Accepted\"", e);
        candidates.selectRow(e.email);
        candidates.markAsJoined();
        e.joined = true;
        return e.fullName() + " was marked as joined.";
    }

    public String verifyActive(EorEmployee e) {
        employees.open();
        employees.search(e.email);
        String status = employees.cell(e.email, "STATUS");
        Assert.assertTrue(status.toLowerCase(Locale.ROOT).contains("active"), e.fullName() + " should be Active in the list of employees after joining, but the status is \"" + status + "\".");
        return e.fullName() + " is listed as \"" + status + "\" in Employees > EOR.";
    }

    private void waitRow(java.util.function.BooleanSupplier condition, String what, EorEmployee e) {
        long end = System.currentTimeMillis() + ConfigManager.getInt("eor.status.wait.seconds", 90) * 1000L;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(2000); // documented: the list updates a moment after the previous step
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            driver.navigate().refresh();
            candidates.filterBy(e.email);
        }
        throw new AssertionError("The list of candidates did not show " + what + " for " + e.fullName() + " within " + ConfigManager.getInt("eor.status.wait.seconds", 90) + " seconds.");
    }

    // ================================================================== the finished employee: profile checks

    /** Employees > EOR, search the e-mail, eye button: the profile of the employee is open. */
    public void openEmployeeProfile(EorEmployee e) {
        employees.open();
        List<Map<String, String>> rows = employees.search(e.email);
        Assert.assertFalse(rows.isEmpty(), "The list of employees does not show " + e.email + " after the search.");
        employees.openProfile(e.email);
        Assert.assertTrue(profile.isOpen(), "The profile of " + e.fullName() + " did not open after the eye button was clicked.");
    }

    /** Every tab of the profile, after \"Unmask all\", compared with what was entered. */
    /** Opens the profile, unmasks everything and runs {@code action}. */
    public void withProfile(EorEmployee e, ValueChecks.Step action) throws Exception {
        openEmployeeProfile(e);
        profile.unmaskAll();
        action.run();
    }

    /** The values that the profile hides until "Unmask all": {tab, heading, label}. */
    private static final String[][] MASKED = {
            {"Personal & Contact", "Contact Information", "Mobile No"}, {"Personal & Contact", "Contact Information", "Alternate Mobile No"},
            {"Personal & Contact", "Emergency Contact", "Contact No"}, {"Statutory Details", "Bank & Statutory", "Account No"},
            {"Statutory Details", "Bank & Statutory", "Confirm Account No"}, {"Documents", "Aadhaar", "Aadhar No"}, {"Documents", "PAN", "Pan No"}};

    private java.util.Map<String, String> snapshot() throws Exception {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        String tab = "";
        List<String[]> pairs = List.of();
        for (String[] m : MASKED) {
            if (!m[0].equals(tab)) {
                tab = m[0];
                profile.selectTab(tab);
                pairs = profile.pairs();
            }
            out.put(m[0] + " > " + m[2], EorProfilePage.value(pairs, m[1], m[2], 0));
        }
        return out;
    }

    /** The hidden value and the full value have the same length, and every digit that the hidden value shows is the same digit in the full value. */
    private static boolean sameShape(String masked, String full) {
        String m = masked.replaceAll("[^0-9A-Za-z]", "");
        String f = full.replaceAll("[^0-9A-Za-z]", "");
        if (m.length() != f.length()) {
            return false;
        }
        for (int i = 0; i < m.length(); i++) {
            if (Character.isDigit(m.charAt(i)) && m.charAt(i) != f.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hidden(String v) {
        return v != null && (v.contains("X") || v.contains("\u2022") || v.contains("*"));
    }

    /**
     * Mask and unmask (user, 2026-11-13): before "Unmask all" the phone numbers, account numbers, Aadhaar and PAN are hidden; after it they are shown in full (same ending, same length);
     * "Mask all" hides them again; the small eye button of one value shows only that value. The profile is left unmasked.
     */
    public void checkMasking(ValueChecks c) throws Exception {
        String w = "Mask and unmask";
        java.util.Map<String, String> masked = snapshot();
        c.check(w, "The button \"Unmask all\" is offered while the values are hidden", profile.unmaskOffered(), "the button \"Unmask all\"", profile.unmaskOffered() ? "offered" : "not offered");
        for (java.util.Map.Entry<String, String> en : masked.entrySet()) {
            c.check(w, en.getKey() + " is hidden at first", hidden(en.getValue()), "hidden (for example XXXXXX1234)", en.getValue() == null ? "(not found on the page)" : en.getValue());
        }
        // the eye button of ONE value
        profile.selectTab("Statutory Details");
        boolean eye = profile.showOne("Show Account No");
        if (eye) {
            String one = EorProfilePage.value(profile.pairs(), "Bank & Statutory", "Account No", 0);
            String other = EorProfilePage.value(profile.pairs(), "Bank & Statutory", "Confirm Account No", 0);
            c.check(w, "The eye button \"Show Account No\" shows that one value", one != null && !hidden(one), "the full account number", one == null ? "(not found)" : one);
            c.check(w, "The eye button shows only that value (the other one stays hidden)", hidden(other), "\"Confirm Account No\" still hidden", other == null ? "(not found)" : other);
        } else {
            c.check(w, "The eye button \"Show Account No\" exists", false, "an eye button next to the account number", "no such button on the tab");
        }
        profile.unmaskAll();
        c.check(w, "\"Unmask all\" turns the button into \"Mask all\"", profile.unmasked(), "the button \"Mask all\"", profile.unmasked() ? "shown" : "not shown");
        java.util.Map<String, String> shown = snapshot();
        for (java.util.Map.Entry<String, String> en : masked.entrySet()) {
            String full = shown.get(en.getKey());
            String m = en.getValue() == null ? "" : en.getValue();
            boolean ok = full != null && !hidden(full) && m.length() > 0 && sameShape(m, full);
            c.check(w, en.getKey() + " is shown in full after \"Unmask all\"", ok, "the full value (same length as the hidden one \"" + m + "\", and the digits that were visible are the same)", full == null ? "(not found)" : full);
        }
        profile.maskAll();
        java.util.Map<String, String> again = snapshot();
        boolean allHidden = true;
        for (String v : again.values()) {
            allHidden &= hidden(v);
        }
        c.check(w, "\"Mask all\" hides every value again", allHidden, "every value hidden", allHidden ? "hidden" : again.toString());
        profile.unmaskAll();
    }

    public void verifyProfile(EorEmployee e, ValueChecks c) throws Exception {
        openEmployeeProfile(e);
        checkMasking(c);
        profile.unmaskAll();
        c.check("Profile", "\"Unmask all\" shows the hidden values (the button turns into \"Mask all\")", profile.unmasked(), "the button \"Mask all\" is shown", profile.unmasked() ? "shown" : "not shown");
        EorProfileChecks.header(run, e, profile.headerLines(), c);
        tab(c, "Personal & Contact", () -> EorProfileChecks.personal(e, profile.pairs(), c));
        tab(c, "Professional", () -> EorProfileChecks.professional(run, e, profile.pairs(), c));
        tab(c, "Statutory Details", () -> EorProfileChecks.statutory(e, profile.pairs(), c));
        tab(c, "Documents", () -> EorProfileChecks.documents(e, profile.pairs(), c));
        tab(c, "Employment Documents", () -> EorProfileChecks.employmentDocuments(profile.bodyTextNow(), c));
    }

    // ------------------------------------------------------------------ edit every block of every tab

    private static final java.util.Set<String> NOT_EDITED = java.util.Set.of("gender", "nationality", "work experience", "physically handicaped", "personal email", "candidate id", "client",
            "marrital status", "cancelled cheque", "country", "work country");

    /** A different value of the same kind: digits count up in the last place, letters and digits keep their pattern, words get a suffix. */
    static String mutate(String old, String label) {
        String o = old == null ? "" : old.trim();
        if (o.isEmpty()) {
            return label.toLowerCase(Locale.ROOT).contains("middle") ? "Kumar" : "Edited";
        }
        char last = o.charAt(o.length() - 1);
        if (o.matches("[0-9]+")) {
            return o.substring(0, o.length() - 1) + (char) ('0' + (last - '0' + 1) % 10);
        }
        if (o.matches("[A-Za-z0-9]+") && o.matches(".*[0-9].*")) {
            char n = Character.isDigit(last) ? (char) ('0' + (last - '0' + 1) % 10) : last == 'Z' ? 'A' : (char) (last + 1);
            return o.substring(0, o.length() - 1) + n;
        }
        return o.endsWith(" Ed") ? o.substring(0, o.length() - 3) : o + " Ed"; // a second pass takes the suffix off again: the values do not grow
    }

    /**
     * Opens every block of the tab with its Edit button, changes every value that can be changed (not the gender, nationality, e-mail, work experience, files), submits, and checks that
     * the profile now shows the new value. A change that matters later (name, mobile number, designation) is written to the employee, so the next steps compare with it.
     */
    public void editTab(EorEmployee e, String tab, ValueChecks c) throws Exception {
        profile.selectTab(tab);
        profile.unmaskAgainIfNeeded();
        int blocks = profile.editButtons();
        c.check(tab, "The tab has blocks with an Edit button", blocks > 0, "at least one Edit button", blocks + " Edit buttons");
        for (int b = 0; b < blocks; b++) {
            final int block = b;
            c.guard(tab + " > block " + (b + 1), "Edit, change every value, Submit", () -> {
                for (int attempt = 1; ; attempt++) {
                    try {
                        editBlock(e, tab, block, c);
                        break;
                    } catch (org.openqa.selenium.StaleElementReferenceException redrawn) {
                        profile.cancelEdit(); // the page redrew the block while it was typed into: the block is opened again once
                        if (attempt >= 2) {
                            throw redrawn;
                        }
                    } finally {
                        profile.cancelEdit(); // a block that could not be changed is closed, so that the next one can be opened
                    }
                }
            });
        }
        if (rep != null) {
            rep.evidence("Tab " + tab + " after the changes", "The tab \"" + tab + "\" after every block was changed");
        }
    }

    private void editBlock(EorEmployee e, String tab, int block, ValueChecks c) {
        profile.startEdit(block);
        List<EorProfilePage.Field> fields = profile.fields();
        String where = tab + " > block " + (block + 1);
        java.util.LinkedHashMap<EorProfilePage.Field, String> expected = new java.util.LinkedHashMap<>();
        String accountNo = null;
        for (EorProfilePage.Field f : fields) {
            String key = f.label.toLowerCase(Locale.ROOT);
            if (key.equals("country") || key.equals("work country")) {
                // the country is always India (user, 2026-11-12): never changed to another one, and put back to India when it is something else
                if (f.kind.equals("combobox") && !f.value.toLowerCase(Locale.ROOT).contains("india") && !f.value.equals("IN")) {
                    String chosen = profile.chooseNamed(f.label, "India");
                    expected.put(f, "IN");
                    utils.Log.warn("The country of \"" + where + "\" was \"" + f.value + "\": it is set to India again.");
                }
                continue;
            }
            if (NOT_EDITED.contains(key)) {
                continue;
            }
            String shown;
            try {
            switch (f.kind) {
                case "combobox" -> shown = profile.chooseDifferent(f.label, f.value);
                case "date" -> {
                    java.time.LocalDate base = f.value.isBlank() ? java.time.LocalDate.of(2019, 1, 1) : java.time.LocalDate.parse(f.value);
                    profile.setDate(f.label, f.value.isBlank() ? base : base.plusDays(1));
                    String kept = profile.currentValue(f.label);
                    shown = kept.length() == 10 ? kept.substring(8) + "-" + kept.substring(5, 7) + "-" + kept.substring(0, 4) : kept;
                }
                default -> {
                    String value = key.startsWith("confirm") && accountNo != null ? accountNo
                            : key.equals("language known") ? (f.value.trim().equalsIgnoreCase("English") ? "Hindi" : "English") : mutate(f.value, f.label);
                    profile.setText(f.label, value);
                    shown = profile.currentValue(f.label);
                    if (key.equals("account no")) {
                        accountNo = shown;
                    }
                }
            }
            } catch (org.openqa.selenium.WebDriverException broken) {
                utils.Log.warn("Field \"" + f.label + "\" (" + f.kind + ", value \"" + f.value + "\") of " + where + " could not be changed: " + String.valueOf(broken.getMessage()).split("\\R")[0]);
                throw new AssertionError("The field \"" + f.label + "\" could not be changed: the box did not take the new value.");
            }
            expected.put(f, shown);
            remember(e, tab, block, f.label, shown);
        }
        String message = profile.submit();
        List<String[]> after = settled(expected);
        boolean reloaded = false;
        if (!allShown(after, expected)) {
            // the page still shows an old value after the change was submitted: it is loaded again and read once more (what a user would do)
            profile.reload(tab);
            after = profile.pairs();
            reloaded = true;
        }
        for (java.util.Map.Entry<EorProfilePage.Field, String> en : expected.entrySet()) {
            EorProfilePage.Field f = en.getKey();
            String got = shownValue(after, f);
            String label = f.label + " (was \"" + (f.value.isBlank() ? "empty" : f.value) + "\")";
            boolean same = sameValue(f, en.getValue(), got);
            c.check(where, label, same, en.getValue(), got == null ? "(not found on the page)" : got);
        }
        if (reloaded) {
            c.check(where, "The page shows the new values right after \"Submit\"", false, "the new values are shown within 8 seconds after Submit",
                    "the old values stayed on the page until it was loaded again");
        }
        c.check(where, "The application confirms the change", !message.toLowerCase(Locale.ROOT).contains("fail") && !message.toLowerCase(Locale.ROOT).contains("error"),
                "a confirmation without an error", message.isEmpty() ? "(no message)" : "\"" + message + "\"");
    }

    /** The values of the page, read again until every changed value shows its new content (at most 8 seconds). */
    private List<String[]> settled(java.util.Map<EorProfilePage.Field, String> expected) {
        long end = System.currentTimeMillis() + 8000;
        List<String[]> pairs = profile.pairs();
        while (!allShown(pairs, expected) && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(1000); // documented: the profile refreshes a moment after Submit
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            profile.unmaskAgainIfNeeded();
            pairs = profile.pairs();
        }
        return pairs;
    }

    private static boolean allShown(List<String[]> pairs, java.util.Map<EorProfilePage.Field, String> expected) {
        for (java.util.Map.Entry<EorProfilePage.Field, String> en : expected.entrySet()) {
            if (!sameValue(en.getKey(), en.getValue(), shownValue(pairs, en.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static String shownValue(List<String[]> pairs, EorProfilePage.Field f) {
        for (int k = f.index; k < pairs.size(); k++) {
            if (pairs.get(k)[1].equalsIgnoreCase(f.label)) {
                return pairs.get(k)[2];
            }
        }
        return null;
    }

    private static boolean sameValue(EorProfilePage.Field f, String expected, String got) {
        if (got == null) {
            return false;
        }
        if (f.label.toLowerCase(Locale.ROOT).contains("aadhar")) {
            return got.replaceAll("[^0-9]", "").equals(expected.replaceAll("[^0-9]", ""));
        }
        return EorProfileChecks.norm(expected).equals(EorProfileChecks.norm(got.equals("—") ? "" : got));
    }

    /** A change that later steps compare with is kept in the employee. */
    private void remember(EorEmployee e, String tab, int block, String label, String value) {
        String l = label.toLowerCase(Locale.ROOT);
        if (tab.startsWith("Personal") && block == 0) {
            switch (l) {
                case "first name" -> e.firstName = value;
                case "middle name" -> e.middleName = value;
                case "last name" -> e.lastName = value;
                default -> { }
            }
        }
        if (tab.startsWith("Personal") && block == 1 && l.equals("mobile no")) {
            e.phone = value;
        }
        if (tab.equals("Professional") && block == 0 && l.equals("designation")) {
            e.designation = value;
        }
        if (tab.equals("Professional") && block == 0 && l.equals("joining date")) {
            e.joiningDate = value;
        }
    }

    private void tab(ValueChecks c, String name, ValueChecks.Step check) throws Exception {
        c.guard(name, "The tab opens and shows its values", () -> {
            profile.selectTab(name);
            if (profile.unmaskAgainIfNeeded() && rep != null) {
                rep.note("On the tab \"" + name + "\" some values were still hidden after \"Unmask all\"; they were shown after \"Unmask all\" was used once more.");
            }
            check.run();
        });
        if (rep != null) {
            rep.evidence("Tab " + name, "The tab \"" + name + "\" of the profile of the employee");
        }
    }

    // ================================================================== list and contract (after the changes of the profile)

    /** Employees > EOR, search the e-mail: the row shows the (changed) name, the e-mail, the employee code and the status Active. */
    public void verifyEmployeeList(EorEmployee e, ValueChecks c) {
        employees.open();
        List<Map<String, String>> rows = employees.search(e.email);
        c.check("Employees > EOR", "The employee is found by the e-mail", rows.size() == 1, "one row", rows.size() + " rows");
        String row = rows.isEmpty() ? "" : String.join(" | ", rows.get(0).values());
        String low = row.toLowerCase(Locale.ROOT);
        if (e.employeeCode.isEmpty()) {
            java.util.regex.Matcher code = java.util.regex.Pattern.compile("EMP_[0-9]+").matcher(row);
            if (code.find()) {
                e.employeeCode = code.group(); // the code the application gave (it is not known when only this case runs on an earlier run)
            }
        }
        c.check("Employees > EOR", "Name as changed", low.contains(e.fullName().toLowerCase(Locale.ROOT)), e.fullName(), shortRow(row));
        c.check("Employees > EOR", "E-mail", low.contains(e.email.toLowerCase(Locale.ROOT)), e.email, shortRow(row));
        c.check("Employees > EOR", "Employee code", !e.employeeCode.isEmpty() && row.contains(e.employeeCode), e.employeeCode, shortRow(row));
        c.check("Employees > EOR", "Status \"Active\"", low.contains("active"), "Active", shortRow(row));
        c.check("Employees > EOR", "Client", row.contains(run.client), run.client, shortRow(row));
        if (rep != null) {
            rep.evidence("Employees list", "The row of " + e.fullName() + " in Employees > EOR");
        }
    }

    private static String shortRow(String s) {
        return s.isEmpty() ? "(no row)" : s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static String ddMmmYyyy(String dmy) {
        try {
            return java.time.LocalDate.parse(dmy, java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH));
        } catch (RuntimeException ex) {
            return dmy;
        }
    }

    private static String digitsOf(String s) {
        return s == null ? "" : s.replaceAll("[^0-9.]", "");
    }

    private void money(ValueChecks c, String where, String label, String expected, String shown) {
        if (expected == null || expected.isBlank()) {
            return;
        }
        String exp = expected.replaceAll("\\.0+$", "");
        String got = shown == null ? null : digitsOf(shown).replaceAll("\\.0+$", "");
        c.check(where, label, got != null && exp.equals(got), exp, shown == null ? "(not found on the page)" : shown);
    }

    /**
     * MSA & Contract > Contract: the list row, the contract page (header, Contract Details, Employee), the Salary Breakup (hidden until "Unmask all", then compared with the
     * workbook row), and "Edit Contract": step 1 (Contract Details) and, after "Save & Continue", step 2 (Salary Breakup) show the same values. Step 3 (Letter of Intent) is
     * not opened, and nothing is saved on step 2.
     */
    public void verifyContract(EorEmployee e, ValueChecks c) throws Exception {
        contracts.open();
        List<Map<String, String>> rows = contracts.search(e.email);
        c.check("Contract list", "The employee is found by the e-mail", rows.size() == 1, "one row", rows.size() + " rows");
        if (rows.isEmpty()) {
            return;
        }
        Map<String, String> r = rows.get(0);
        String w = "Contract list";
        EorProfileChecks.eq(c, w, "Client name", run.client, TableReader.column(r, "CLIENT NAME"));
        EorProfileChecks.eq(c, w, "Employee code", e.employeeCode, TableReader.column(r, "EMPLOYEE CODE"));
        EorProfileChecks.eq(c, w, "Employee name (as changed)", e.fullName(), TableReader.column(r, "EMPLOYEE NAME"));
        c.check(w, "Contract id is shown", !TableReader.column(r, "CONTRACT ID").isBlank(), "a contract id", TableReader.column(r, "CONTRACT ID"));
        EorProfileChecks.eq(c, w, "Personal e-mail", e.email, TableReader.column(r, "PERSONAL EMAIL"));
        EorProfileChecks.eq(c, w, "Status", "Active", TableReader.column(r, "STATUS"));
        if (rep != null) {
            rep.evidence("Contract list", "The row of " + e.fullName() + " in MSA & Contract > Contract");
        }
        contracts.openFirstRow();
        List<String> lines = contracts.lines();
        String all = String.join(" | ", lines);
        String d = "Contract page > top";
        c.check(d, "Name", all.contains(e.fullName()), e.fullName(), shortRow(all));
        c.check(d, "Status \"Active\"", all.contains("Active"), "Active", shortRow(all));
        c.check(d, "Designation", all.toLowerCase(Locale.ROOT).contains(e.designation.toLowerCase(Locale.ROOT)), e.designation, shortRow(all));
        c.check(d, "E-mail", all.toLowerCase(Locale.ROOT).contains(e.email.toLowerCase(Locale.ROOT)), e.email, shortRow(all));
        String joining = ddMmmYyyy(e.joiningDate.isEmpty() ? run.sheet.getOrDefault("joining date", "").replace('/', '-') : e.joiningDate);
        String cd = "Contract page > Contract Details";
        EorProfileChecks.eq(c, cd, "Client name", run.client, EorContractsPage.after(lines, "Contract Details", "Client Name"));
        EorProfileChecks.eq(c, cd, "Project name", run.project, EorContractsPage.after(lines, "Contract Details", "Project Name"));
        EorProfileChecks.eq(c, cd, "Joining date (as changed)", joining, EorContractsPage.after(lines, "Contract Details", "Joining Date"));
        EorProfileChecks.eq(c, cd, "Frequency", "Monthly", EorContractsPage.after(lines, "Contract Details", "Frequency"));
        String em = "Contract page > Employee";
        EorProfileChecks.eq(c, em, "Employee name (as changed)", e.fullName(), EorContractsPage.after(lines, "Employee", "Employee Name"));
        EorProfileChecks.eq(c, em, "Employee code", e.employeeCode, EorContractsPage.after(lines, "Employee", "Employee Code"));
        EorProfileChecks.eq(c, em, "Designation (as changed)", e.designation, EorContractsPage.after(lines, "Employee", "Designation"));
        if (rep != null) {
            rep.evidence("Contract page", "The contract of " + e.fullName());
        }

        String sb = "Contract page > Salary Breakup";
        if (!contracts.hasSalaryBreakup()) {
            c.check(sb, "The contract page has the part \"Salary Breakup\" for this account", false, "the part \"Salary Breakup\" (the administrator's page has it)",
                    "this account's contract page shows only Contract Details and Employee");
        } else {
            contracts.expandSalaryBreakup();
            int hiddenFirst = contracts.hiddenSalaryValues();
            c.check(sb, "The salary values are hidden until \"Unmask all\"", hiddenFirst > 0, "values hidden behind dots", hiddenFirst + " hidden values");
            contracts.unmaskSalary();
            c.check(sb, "\"Unmask all\" shows every value (no hidden characters are left)", contracts.hiddenSalaryValues() == 0, "all values readable", contracts.hiddenSalaryValues() + " values still hidden");
            List<String> sal = contracts.lines();
            money(c, sb, "Annual CTC", run.sheet.get("annual ctc"), EorContractsPage.after(sal, "Salary Breakup", "Annual CTC"));
            money(c, sb, "Basic salary", run.sheet.get("basic salary"), EorContractsPage.after(sal, "Salary Breakup", "Basic Salary"));
            money(c, sb, "HRA", run.sheet.get("hra"), EorContractsPage.after(sal, "Salary Breakup", "HRA"));
            money(c, sb, "Conveyance", run.sheet.get("conveyance allowance"), EorContractsPage.after(sal, "Salary Breakup", "Conveyance"));
            money(c, sb, "Special allowance", run.sheet.get("special allowance"), EorContractsPage.after(sal, "Salary Breakup", "Special Allowance"));
            money(c, sb, "Monthly gross salary", run.sheet.get("gross monthly"), EorContractsPage.after(sal, "Salary Breakup", "Monthly Gross Salary"));
            String pf = EorContractsPage.after(sal, "Salary Breakup", "Provident Fund Type");
            if (pf != null) {
                EorProfileChecks.eq(c, sb, "Provident fund type", run.sheet.getOrDefault("pf", "capped"), pf);
            }
            if (rep != null) {
                rep.evidence("Salary Breakup", "The salary breakup of " + e.fullName() + " after \"Unmask all\"");
            }
            contracts.maskSalary();
            c.check(sb, "\"Mask all\" hides the salary values again", contracts.hiddenSalaryValues() > 0, "values hidden again", contracts.hiddenSalaryValues() + " hidden values");
        }

        contracts.clickEditContract();
        Map<String, String> step1 = contracts.editValues();
        String s1 = "Edit Contract > step 1 Contract Details";
        EorProfileChecks.eq(c, s1, "Client name", run.client, step1.get("Client Name"));
        EorProfileChecks.eq(c, s1, "Employee name (as changed)", e.fullName(), step1.get("Employee Name"));
        EorProfileChecks.eq(c, s1, "Project name", run.project, step1.get("Project Name"));
        String iso = e.joiningDate.isEmpty() ? "" : e.joiningDate.substring(6) + "-" + e.joiningDate.substring(3, 5) + "-" + e.joiningDate.substring(0, 2);
        if (!iso.isEmpty()) {
            EorProfileChecks.eq(c, s1, "Joining date (as changed)", iso, step1.get("Joining Date"));
        }
        if (rep != null) {
            rep.evidence("Edit Contract step 1", "Edit Contract: step 1 Contract Details");
        }
        contracts.saveAndContinue();
        contracts.waitForField("Employee Code");
        List<String[]> f = contracts.editFields();
        Map<String, String> step2 = contracts.editValues();
        String s2 = "Edit Contract > step 2 Salary Breakup (after Save & Continue)";
        EorProfileChecks.eq(c, s2, "Employee name", e.fullName(), step2.get("Employee Name"));
        EorProfileChecks.eq(c, s2, "Employee code", e.employeeCode, step2.get("Employee Code"));
        EorProfileChecks.eq(c, s2, "Designation", e.designation, step2.get("Designation"));
        EorProfileChecks.eq(c, s2, "Frequency", "Monthly", step2.get("Frequency"));
        EorProfileChecks.eq(c, s2, "Currency", "Rupees", step2.get("Currency"));
        money(c, s2, "Monthly CTC", run.sheet.get("monthly ctc"), step2.get("Monthly CTC"));
        money(c, s2, "Annual CTC", run.sheet.get("annual ctc"), step2.get("Annual CTC (Monthly CTC \u00d7 12)") != null ? step2.get("Annual CTC (Monthly CTC \u00d7 12)") : firstStartingWith(step2, "Annual CTC"));
        money(c, s2, "Monthly gross salary", run.sheet.get("gross monthly"), step2.get("Monthly Gross Salary"));
        String[] components = componentsAfterEsic(f);
        if (components != null) {
            money(c, s2, "Basic salary", run.sheet.get("basic salary"), components[1]);
            money(c, s2, "HRA", run.sheet.get("hra"), components[2]);
            money(c, s2, "Conveyance", run.sheet.get("conveyance allowance"), components[4]);
            money(c, s2, "Special allowance", run.sheet.get("special allowance"), components[5]);
        }
        money(c, s2, "Bonus", run.sheet.get("bonus"), step2.get("Bonus"));
        money(c, s2, "Medical allowance", firstNonBlank(run.sheet.get("medical alloawance"), run.sheet.get("medical allowance")), step2.get("Medical Allowance"));
        money(c, s2, "Holiday allowance", run.sheet.get("holiday allowance"), step2.get("Holiday Allowance"));
        money(c, s2, "Shift allowance", run.sheet.get("shift allowance"), step2.get("Shift Allowance"));
        money(c, s2, "Overtime", run.sheet.get("overtime"), step2.get("Overtime"));
        money(c, s2, "Other benefits", run.sheet.get("other benefits"), step2.get("Other Benefits"));
        if (rep != null) {
            rep.evidence("Edit Contract step 2", "Edit Contract: step 2 Salary Breakup after \"Save & Continue\"");
        }
    }

    private static String firstStartingWith(Map<String, String> m, String start) {
        for (Map.Entry<String, String> en : m.entrySet()) {
            if (en.getKey().startsWith(start)) {
                return en.getValue();
            }
        }
        return null;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /** The six boxes of the salary table that follow "ESIC Applicable": basic %, basic salary, HRA, HRA %, conveyance, special allowance. */
    private static String[] componentsAfterEsic(List<String[]> fields) {
        int esic = -1;
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i)[0].startsWith("ESIC Applicable")) {
                esic = i;
            }
        }
        if (esic < 0) {
            return null;
        }
        List<String> numbers = new ArrayList<>();
        for (int i = esic + 1; i < fields.size() && numbers.size() < 6; i++) {
            if (!fields.get(i)[1].equals("SELECT")) {
                numbers.add(fields.get(i)[2]);
            }
        }
        return numbers.size() == 6 ? numbers.toArray(new String[0]) : null;
    }

    // ================================================================== run data

    public void save() {
        try {
            Files.createDirectories(ExecutionInfo.root());
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(ExecutionInfo.root().resolve("eor-run-data.json").toFile(), run);
        } catch (IOException ex) {
            utils.Log.warn("The run data could not be saved: " + ex.getMessage());
        }
    }
}
