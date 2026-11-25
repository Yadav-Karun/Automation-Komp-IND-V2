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
import pages.AdminHomePage;
import pages.AorDialog;
import pages.EorContractsPage;
import pages.EorCandidatePortalPage;
import pages.EorCandidatesPage;
import pages.EorEmployeesPage;
import pages.EmployeeMailbox;
import pages.EorMailbox;
import pages.ImapMailbox;
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
    private EmployeeMailbox mailbox;
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
        // -Dmail.provider=imap reads the mails of the employees with a mail program (Yopmail does not let the automated browser in); the default is Yopmail in a tab of the test browser
        this.mailbox = "imap".equalsIgnoreCase(ConfigManager.get("mail.provider", "yopmail")) ? new ImapMailbox(d) : new EorMailbox(d);
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
            boolean viaMailbox = "imap".equalsIgnoreCase(ConfigManager.get("mail.provider", "yopmail"));
            String mailbox = ConfigManager.get("imap.address.base", "");
            String domainRule = viaMailbox ? "is a variant of the QA mailbox " + mailbox : "ends with @yopmail.com";
            checks.check(who, viaMailbox ? "E-mail is a variant of the QA mailbox" : "E-mail is a yopmail address",
                    viaMailbox ? e.email.matches(java.util.regex.Pattern.quote(mailbox.substring(0, mailbox.indexOf('@')).toLowerCase()) + "\\+\\d+" + java.util.regex.Pattern.quote(mailbox.substring(mailbox.indexOf('@')).toLowerCase())) : e.email.endsWith("@yopmail.com"),
                    domainRule, e.email);
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
            if (rep != null && mailbox.mailDriver() != null) {
                rep.setDriver(mailbox.mailDriver()); // the picture is taken of the window that shows the mail
                try {
                    rep.evidence("Mail with the login password", "The mail \"Candidate Login\" of " + e.email);
                } finally {
                    rep.setDriver(driver);
                }
            } else if (rep != null) {
                rep.note("The mail \"Candidate Login\" of " + e.email + " was read from the mailbox by a mail program (no picture of it).");
            }
            return answer + ". The mail \"Candidate Login\" arrived and holds the password " + e.password + ".";
        } finally {
            mailbox.close();
        }
    }

    private boolean stoppedOnPurpose;

    /** true after a run was stopped with -Deor.stop.after: the cases after that one are not run, nothing may touch the browser any more. */
    public boolean stoppedOnPurpose() {
        return stoppedOnPurpose;
    }

    /** The employee signs in to the candidate portal and fills in every section; one table row per section. */
    public void fillProfile(EorEmployee e, ValueChecks checks) throws Exception {
        withRestart(() -> {
            portal.signIn(e.email, e.password);
            return null;
        });
        checks.check("Sign in", "The password of the mail works", true, "the candidate portal opens", "signed in");
        if ("candidate-login".equalsIgnoreCase(ConfigManager.get("eor.stop.after", ""))) {
            // a run for looking at the candidate portal (user, 2026-11-23): everything up to the candidate's sign-in is done, then the run stops WITHOUT closing the browser
            stoppedOnPurpose = true;
            base.DriverFactory.keepBrowserOpen();
            utils.Log.info("Run stopped on purpose after the candidate signed in (-Deor.stop.after=candidate-login): the browser stays open on the candidate portal.");
            throw new org.testng.SkipException("Stopped on purpose after the candidate signed in (-Deor.stop.after=candidate-login). The browser is left open on the candidate portal.");
        }
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
            {"Documents", "Aadhaar", "Aadhar No"}, {"Documents", "PAN", "Pan No"}};

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
        // a masked value shows runs of X (XXXXXX1234, XXXXX7389X); a real value may contain a single letter X (a PAN such as YOHXJ8948Z), which is not a mask
        return v != null && (v.contains("XXX") || v.contains("\u2022") || v.contains("*"));
    }

    private static final String[] PROFILE_TABS = {"Personal & Contact", "Professional", "Statutory Details", "Documents", "Employment Documents"};

    /**
     * Every tab of the profile is opened ONCE, in the order of the page, and on every tab the values are UNMASKED FIRST, then compared with what was entered, then the next tab
     * (user, 2026-11-18: "first unmask and compare data, then move to the next tab"). Only the very first look is different: when the profile opens its values are hidden, so on the first tab
     * the hidden values are read once before "Unmask all" (hidden = runs of X, e.g. XXXXXX1234 or XXXXX3302X) and compared with the shown values afterwards (same length, same visible digits).
     * The profile is left unmasked.
     */
    public void verifyProfile(EorEmployee e, ValueChecks c) throws Exception {
        openEmployeeProfile(e);
        String w = "Mask and unmask";
        c.check(w, "The button \"Unmask all\" is offered while the values are hidden", profile.unmaskOffered(), "the button \"Unmask all\"", profile.unmaskOffered() ? "offered" : "not offered");
        boolean[] first = {true};
        for (String tab : PROFILE_TABS) {
            visitTab(c, tab, first, () -> enteredCheck(e, tab, c));
        }
        c.check("Profile", "\"Unmask all\" shows the hidden values (the button turns into \"Mask all\")", profile.unmasked(), "the button \"Mask all\" is shown", profile.unmasked() ? "shown" : "not shown");
        // the phone number in the header was once read hidden although every value of the tabs was shown: it is read again until it is shown
        EorProfileChecks.header(run, e, headerShown(), c);
    }

    /** The hidden values of one tab, by label (the tab is open): {label -> value}. */
    private java.util.Map<String, String> readMasked(String tab) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        List<String[]> pairs = profile.pairs();
        for (String[] m : MASKED) {
            if (m[0].equals(tab)) {
                out.put(m[2], EorProfilePage.value(pairs, m[1], m[2], 0));
            }
        }
        return out;
    }

    /** One visit of a tab: unmask first, then compare the values of the tab with what was entered (a picture of the tab as it is shown). */
    private void visitTab(ValueChecks c, String tab, boolean[] first, ValueChecks.Step values) throws Exception {
        String w = "Mask and unmask";
        c.guard(tab, "The tab opens and shows its values", () -> {
            profile.selectTab(tab);
            if (first[0]) {
                first[0] = false;
                // the profile has just been opened: its values are still hidden. They are read once, then "Unmask all" is clicked and the shown values are compared with them.
                java.util.Map<String, String> hiddenNow = readMasked(tab);
                for (java.util.Map.Entry<String, String> en : hiddenNow.entrySet()) {
                    c.check(w, tab + " > " + en.getKey() + " is hidden at first", hidden(en.getValue()), "hidden (for example XXXXXX1234)", en.getValue() == null ? "(not found on the page)" : en.getValue());
                }
                profile.unmaskAll();
                c.check(w, "\"Unmask all\" turns the button into \"Mask all\"", profile.unmasked(), "the button \"Mask all\"", profile.unmasked() ? "shown" : "not shown");
                java.util.Map<String, String> shown = readMasked(tab);
                for (java.util.Map.Entry<String, String> en : hiddenNow.entrySet()) {
                    String full = shown.get(en.getKey());
                    String m = en.getValue() == null ? "" : en.getValue();
                    boolean ok = full != null && !hidden(full) && m.length() > 0 && sameShape(m, full);
                    c.check(w, tab + " > " + en.getKey() + " is shown in full after \"Unmask all\"", ok, "the full value (same length as the hidden one \"" + m + "\", and the digits that were visible are the same)", full == null ? "(not found)" : full);
                }
            }
            if (profile.unmaskAgainIfNeeded() && rep != null) {
                rep.note("On the tab \"" + tab + "\" some values were still hidden after \"Unmask all\"; they were shown after \"Unmask all\" was used once more.");
            }
            values.run();
        });
        if (rep != null) {
            rep.evidence("Tab " + tab, "The tab \"" + tab + "\" of the profile of the employee, values unmasked");
        }
    }

    /** The values of one tab compared with what was entered when the employee was onboarded (the tab is open and its values are shown). */
    private void enteredCheck(EorEmployee e, String tab, ValueChecks c) {
        switch (tab) {
            case "Personal & Contact" -> EorProfileChecks.personal(e, profile.pairs(), c);
            case "Professional" -> EorProfileChecks.professional(run, e, profile.pairs(), c);
            case "Statutory Details" -> EorProfileChecks.statutory(e, profile.pairs(), c);
            case "Documents" -> EorProfileChecks.documents(e, profile.pairs(), c);
            default -> EorProfileChecks.employmentDocuments(profile.bodyTextNow(), c);
        }
    }

    // ================================================================== edit the profile (administrator), check it again after signing in again, contract, employee account

    /** Employees > EOR > search > View, then "Unmask all": the profile that the edit case works on. */
    public String openProfileForEdit(EorEmployee e) {
        openEmployeeProfile(e);
        profile.unmaskAll();
        Assert.assertTrue(profile.unmasked(), "\"Unmask all\" did not turn into \"Mask all\" on the profile of " + e.fullName() + ".");
        return "The profile of " + e.fullName() + " is open (Employees > EOR > search > View) and \"Unmask all\" was clicked: every value is shown.";
    }

    /** Opens the profile menu and signs out of the administrator console. */
    public String signOutAdmin() {
        boolean out = new AdminHomePage(driver).signOut();
        Assert.assertTrue(out, "After \"Log out\" the login page did not appear.");
        return "The profile menu was opened and \"Log out\" was clicked: the login page is shown.";
    }

    private static final java.util.Set<String> NOT_EDITED = java.util.Set.of("gender", "nationality", "work experience", "physically handicaped", "personal email", "candidate id", "client",
            "cancelled cheque", "country", "work country", "document type");

    /** A different value of the same kind: digits count up in the last place, letters and digits keep their pattern, e-mail addresses stay valid yopmail addresses, words get a suffix. */
    static String mutate(String old, String label) {
        String o = old == null ? "" : old.trim();
        boolean mail = label.toLowerCase(Locale.ROOT).contains("mail");
        if (mail) {
            return o.contains("@") ? o.substring(0, o.indexOf('@')) + "x" + o.substring(o.indexOf('@')) : "cc.edited@yopmail.com";
        }
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
     * One tab: opens every block with its Edit button, changes every value that can be changed (not the gender, nationality, e-mail, work experience, files), submits, and checks that the
     * profile shows the new value. The values the tab must show from now on (the first look with every change put in) are kept in the employee for the checks after signing in again.
     * A change that later steps compare with (name, mobile number, designation, joining date) is written to the employee.
     */
    public void editTab(EorEmployee e, String tab, ValueChecks c) throws Exception {
        profile.selectTab(tab);
        profile.unmaskAgainIfNeeded();
        List<String[]> expectedRows = new ArrayList<>();
        for (String[] p : profile.pairs()) {
            expectedRows.add(p.clone());
        }
        int blocks = profile.editButtons();
        if (blocks == 0) {
            c.check(tab, "The tab has nothing that can be edited (no Edit button: it only lists documents)", true, "-", "no Edit button on this tab");
        }
        for (int b = 0; b < blocks; b++) {
            final int block = b;
            c.guard(tab + " > block " + (b + 1), "Edit, change every value, Submit", () -> {
                for (int attempt = 1; ; attempt++) {
                    try {
                        editBlock(e, tab, block, c, expectedRows);
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
        if (!expectedRows.isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            for (String[] r : expectedRows) {
                rows.add(new ArrayList<>(java.util.Arrays.asList(r)));
            }
            e.expectedTabs.put(tab, rows);
        }
        if (rep != null) {
            rep.evidence("Tab " + tab + " after the changes", "The tab \"" + tab + "\" after every block was changed");
        }
    }

    private void editBlock(EorEmployee e, String tab, int block, ValueChecks c, List<String[]> expectedRows) {
        profile.startEdit(block);
        List<EorProfilePage.Field> fields = profile.fields();
        String where = tab + " > block " + (block + 1);
        // The marital status the employee chose in the candidate portal may be one that the profile does not accept (seen: "Other"; the profile accepts divorced, married, separated, single, unmarried,
        // widowed). Such a block cannot be saved at all: it is first saved WITHOUT a change, and what the application answers is a finding of its own.
        for (EorProfilePage.Field f : fields) {
            if (f.label.equalsIgnoreCase("marrital status") && !f.value.isBlank()
                    && !java.util.Set.of("divorced", "married", "separated", "single", "unmarried", "widowed").contains(f.value.trim().toLowerCase(Locale.ROOT))) {
                String saved;
                try {
                    profile.submit();
                    saved = null;
                } catch (AssertionError refused) {
                    saved = String.valueOf(refused.getMessage());
                }
                c.check(where, "The block can be saved without any change (its marital status \"" + f.value + "\" is the one the employee chose in the candidate portal)", saved == null,
                        "the application saves the block", saved == null ? "saved" : "the application refused it: " + saved.replaceAll("(?s)^.*The application said: ", ""));
                if (saved != null) {
                    profile.cancelEdit();
                    profile.startEdit(block);
                    fields = profile.fields();
                } else {
                    profile.startEdit(block);
                    fields = profile.fields();
                }
                break;
            }
        }
        List<String[]> toRemember = new ArrayList<>();
        String targetMarital = null; // the marital status the block gets (only Married or Unmarried, user 2026-11-19)
        for (EorProfilePage.Field f : fields) {
            if (f.label.equalsIgnoreCase("marrital status")) {
                targetMarital = f.value.trim().equalsIgnoreCase("Married") ? "Unmarried" : "Married";
            }
        }
        String blockHeading = profile.editHeading();
        for (EorProfilePage.Field f : fields) {
            f.heading = blockHeading;
        }
        java.util.LinkedHashMap<EorProfilePage.Field, String> expected = new java.util.LinkedHashMap<>();
        String accountNo = null;
        for (EorProfilePage.Field f : fields) {
            String key = f.label.toLowerCase(Locale.ROOT);
            if (key.equals("country") || key.equals("work country")) {
                // the country is always India (user, 2026-11-12): never changed to another one, and put back to India when it is something else
                if (f.kind.equals("combobox") && !f.value.toLowerCase(Locale.ROOT).contains("india") && !f.value.equals("IN")) {
                    profile.chooseNamed(f.label, "India");
                    expected.put(f, "IN");
                    utils.Log.warn("The country of \"" + where + "\" was \"" + f.value + "\": it is set to India again.");
                }
                continue;
            }
            if (NOT_EDITED.contains(key)) {
                continue;
            }
            if (key.contains("spouse") && !"Married".equals(targetMarital)) {
                continue; // the spouse's name is only shown on the page when the employee is married: it is not changed for an unmarried employee
            }
            String shown;
            try {
                switch (f.kind) {
                    case "combobox" -> shown = key.equals("marrital status") ? profile.chooseNamed(f.label, f.value.trim().equalsIgnoreCase("Married") ? "Unmarried" : "Married") // user, 2026-11-19: only between Married and Unmarried
                            : profile.chooseDifferent(f.label, f.value);
                    case "date" -> {
                        java.time.LocalDate base = f.value.isBlank() ? java.time.LocalDate.of(2019, 1, 1) : java.time.LocalDate.parse(f.value);
                        profile.setDate(f.label, f.value.isBlank() ? base : base.plusDays(1));
                        String kept = profile.currentValue(f.label);
                        shown = kept.length() == 10 ? kept.substring(8) + "-" + kept.substring(5, 7) + "-" + kept.substring(0, 4) : kept;
                    }
                    case "time" -> {
                        profile.setTime(f.label, key.contains("end") ? "19:30" : "10:30");
                        shown = profile.currentValue(f.label);
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
            toRemember.add(new String[] {f.label, shown});
        }
        String message = profile.submit();
        for (String[] kept : toRemember) {
            remember(e, tab, block, kept[0], kept[1]); // only a block that the application saved changes what the later steps compare with
        }
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
            boolean same = sameText(f.label, en.getValue(), got);
            c.check(where, label, same, en.getValue(), got == null ? "(not found on the page)" : got);
            int row = rowOf(expectedRows, f.heading, f.label); // what the tab must show from now on
            if (row >= 0) {
                expectedRows.get(row)[2] = en.getValue();
            }
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
            if (!sameText(en.getKey().label, en.getValue(), shownValue(pairs, en.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** The value of the field on the page: the label under the heading of its block (the same label can be in several blocks). */
    private static String shownValue(List<String[]> pairs, EorProfilePage.Field f) {
        int k = rowOf(pairs, f.heading, f.label);
        return k < 0 ? null : pairs.get(k)[2];
    }

    private static int rowOf(List<String[]> pairs, String heading, String label) {
        for (int k = 0; k < pairs.size(); k++) {
            if (pairs.get(k)[1].equalsIgnoreCase(label) && (heading == null || heading.isBlank() || pairs.get(k)[0].equalsIgnoreCase(heading))) {
                return k;
            }
        }
        return -1;
    }

    /** Equal as a person reads it: spaces and case do not count, Aadhaar numbers are compared by their digits, a time of day by hour and minute (also "10:30 AM"). */
    private static boolean sameText(String label, String expected, String got) {
        if (got == null) {
            return false;
        }
        String l = label.toLowerCase(Locale.ROOT);
        if (l.contains("aadhar")) {
            return got.replaceAll("[^0-9]", "").equals(expected.replaceAll("[^0-9]", ""));
        }
        if (l.contains("shift")) {
            return time24(expected).equals(time24(got));
        }
        return EorProfileChecks.norm(expected.trim().equals("—") ? "" : expected).equals(EorProfileChecks.norm(got.trim().equals("—") ? "" : got));
    }

    private static String time24(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(\\d{1,2}):(\\d{2})\\s*(am|pm)?").matcher(s == null ? "" : s);
        if (!m.find()) {
            return s == null ? "" : s.trim();
        }
        int h = Integer.parseInt(m.group(1)) % 12 + ("pm".equalsIgnoreCase(m.group(3)) ? 12 : 0);
        if (m.group(3) == null) {
            h = Integer.parseInt(m.group(1));
        }
        return String.format("%02d:%s", h, m.group(2));
    }

    /** A change that later steps compare with is kept in the employee. */
    private void remember(EorEmployee e, String tab, int block, String label, String value) {
        String l = label.toLowerCase(Locale.ROOT);
        if (tab.startsWith("Personal") && block == 0 && e.originalName.isEmpty()) {
            e.originalName = e.fullName(); // the "Welcome Aboard" mail names the employee as he / she was before the change
        }
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

    /** The first position at or after {@code from} that holds this label under this heading (anywhere when none does); -1 when the label is not on the page. */
    private static int indexOf(List<String[]> pairs, String heading, String label, int from) {
        for (int k = Math.max(0, from); k < pairs.size(); k++) {
            if (pairs.get(k)[0].equals(heading) && pairs.get(k)[1].equalsIgnoreCase(label)) {
                return k;
            }
        }
        for (int k = 0; k < pairs.size(); k++) {
            if (pairs.get(k)[1].equalsIgnoreCase(label)) {
                return k;
            }
        }
        return -1;
    }

    /** The lines of the header with the phone number shown: read again (up to 3 times) while it is still hidden behind X characters. */
    private List<String> headerShown() {
        List<String> lines = profile.headerLines();
        for (int i = 0; i < 3 && String.join(" ", lines).contains("XXX"); i++) {
            profile.unmaskAgainIfNeeded();
            try {
                Thread.sleep(1500); // documented: the header is redrawn a moment after "Unmask all"
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            lines = profile.headerLines();
        }
        return lines;
    }

    /** The tab that is open, compared value by value with what it must show after the changes (the first look with every change put in); without a record of changes: with what was entered. */
    private void compareExpected(EorEmployee e, String tab, ValueChecks c, boolean employeeView) {
        List<List<String>> expected = e.expectedTabs.get(tab);
        if (employeeView && tab.equals("Employment Documents")) {
            long end = System.currentTimeMillis() + 10000; // the list of documents is filled a moment after the tab opens
            while (!profile.bodyTextNow().toLowerCase(Locale.ROOT).contains("offer_letter.pdf") && System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(1000); // documented: the documents are loaded after the tab is shown
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            EorProfileChecks.employeeDocuments(profile.bodyTextNow(), c);
            return;
        }
        if (expected == null || expected.isEmpty()) {
            enteredCheck(e, tab, c);
            return;
        }
        List<String[]> got = profile.pairs();
        int from = 0;
        for (List<String> row : expected) {
            String heading = row.get(0);
            String label = row.get(1);
            String value = row.get(2);
            int k = indexOf(got, heading, label, from);
            String shown = k < 0 ? null : got.get(k)[2];
            if (k >= 0) {
                from = k + 1;
            }
            boolean noValue = value.trim().isEmpty() || value.trim().equals("—");
            if (shown == null && noValue) {
                continue; // a value that is empty may be left out by the page (the employee's own account does not list "Disability Certificate" when there is none)
            }
            if (shown == null && employeeView) {
                // the employee's own account is a shorter view of the profile (the "Client" is in its header, not in the list): the row is listed as not shown, it is not a different value
                c.check(tab + " > " + heading, label + " (this row is not part of the employee's own account)", true, value, "not shown in the employee's account");
                continue;
            }
            c.check(tab + " > " + heading, label, sameText(label, value, shown), value, shown == null ? "(not found on the page)" : shown);
        }
    }

    /**
     * After signing in again: Employees &gt; EOR &gt; search &gt; View, "Unmask all", every tab in turn compared with the values that were typed in the edit case (and with the values that
     * were not changed), then the header of the profile.
     */
    public void verifyEditedProfile(EorEmployee e, ValueChecks c) throws Exception {
        openEmployeeProfile(e);
        profile.unmaskAll();
        c.check("Profile", "\"Unmask all\" shows the hidden values (the button turns into \"Mask all\")", profile.unmasked(), "the button \"Mask all\" is shown", profile.unmasked() ? "shown" : "not shown");
        for (String tab : PROFILE_TABS) {
            c.guard(tab, "The tab opens and shows the changed values", () -> {
                profile.selectTab(tab);
                if (profile.unmaskAgainIfNeeded() && rep != null) {
                    rep.note("On the tab \"" + tab + "\" some values were still hidden after \"Unmask all\"; they were shown after \"Unmask all\" was used once more.");
                }
                compareExpected(e, tab, c, false);
            });
            if (rep != null) {
                rep.evidence("Tab " + tab + " after signing in again", "The tab \"" + tab + "\" after signing in again, values unmasked");
            }
        }
        // (the header is read above)
        EorProfileChecks.header(run, e, headerShown(), c);
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
     * MSA &amp; Contract &gt; Contract: search the employee, the list row, a click on the row opens the contract: header, Contract Details, Employee and the Salary Breakup (hidden until
     * "Unmask all", then compared with the workbook row). The values that were changed in the profile (name, designation, joining date) must be the new ones. Nothing is edited or saved.
     */
    public void verifyContract(EorEmployee e, ValueChecks c) throws Exception {
        contracts.open();
        List<Map<String, String>> rows = contracts.search(e.email);
        c.check("Contract list", "The employee is found by the e-mail", rows.size() == 1, "one row", rows.size() + " rows");
        if (rows.isEmpty()) {
            return;
        }
        Map<String, String> r = rows.get(0);
        if (e.employeeCode.isEmpty()) {
            e.employeeCode = TableReader.column(r, "EMPLOYEE CODE");
        }
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
        c.check(d, "Name (as changed)", all.contains(e.fullName()), e.fullName(), shortRow(all));
        c.check(d, "Status \"Active\"", all.contains("Active"), "Active", shortRow(all));
        c.check(d, "Designation (as changed)", all.toLowerCase(Locale.ROOT).contains(e.designation.toLowerCase(Locale.ROOT)), e.designation, shortRow(all));
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
            return;
        }
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
    }

    // ------------------------------------------------------------------ the employee's own account (login data of the "Welcome Aboard" mail)

    /** What the mail "Welcome Aboard" said, per employee e-mail: {password, employee id}. Kept in memory only (never written to the run data file). */
    private final java.util.Map<String, String[]> welcome = new java.util.HashMap<>();

    /** Opens the mail "Welcome Aboard - Your Employee Login Details", checks that it is for this employee and reads the employee id, e-mail and temporary password. */
    public String readWelcomeMail(EorEmployee e) {
        mailbox.open(e.email);
        String text = mailbox.readMail("Welcome Aboard", ConfigManager.getInt("email.poll.timeout.seconds", 120));
        String who = e.originalName.isEmpty() ? e.fullName() : e.originalName;
        Assert.assertTrue(text.contains(who), "The mail \"Welcome Aboard\" is not for " + who + ". It says: \"" + (text.length() > 200 ? text.substring(0, 200) + "..." : text) + "\"");
        java.util.regex.Matcher pw = java.util.regex.Pattern.compile("(?i)Temporary Password\\s+(\\S+)").matcher(text);
        Assert.assertTrue(pw.find(), "The mail \"Welcome Aboard\" does not contain a temporary password. It says: \"" + (text.length() > 200 ? text.substring(0, 200) + "..." : text) + "\"");
        String password = pw.group(1);
        SecretMasker.register(password);
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("Employee ID\\s+(EMP_[0-9]+)").matcher(text);
        String employeeId = id.find() ? id.group(1) : "";
        java.util.regex.Matcher mail = java.util.regex.Pattern.compile("Email\\s+(\\S+@\\S+)").matcher(text);
        String loginMail = mail.find() ? mail.group(1) : "";
        Assert.assertTrue(loginMail.equalsIgnoreCase(e.email), "The mail \"Welcome Aboard\" gives the login e-mail \"" + loginMail + "\" but the employee was registered with \"" + e.email + "\".");
        welcome.put(e.email, new String[] {password, employeeId});
        mailbox.close();
        return "The mail \"Welcome Aboard\" arrived for " + who + ". It gives the login e-mail " + loginMail + ", the employee id " + employeeId + " and the temporary password " + password + ".";
    }

    /** Opens the login page and signs in with the e-mail and the temporary password of the mail; the employee's own account (/employee) must open. */
    public String signInEmployee(EorEmployee e) throws Exception {
        String[] w = welcome.get(e.email);
        Assert.assertNotNull(w, "The mail \"Welcome Aboard\" of " + e.fullName() + " was not read, so there is no password to sign in with.");
        return withRestart(() -> {
            login.freshLogin(ConfigManager.baseUrl());
            login.login(e.email, w[0]);
            try {
                new org.openqa.selenium.support.ui.WebDriverWait(driver, java.time.Duration.ofSeconds(40)).until(d -> d.getCurrentUrl().contains("/employee"));
            } catch (org.openqa.selenium.TimeoutException late) {
                String page = driver.findElement(org.openqa.selenium.By.tagName("body")).getText();
                throw new AssertionError("After signing in with the login data of the mail the employee's account did not open. The page shows: \"" + (page.length() > 200 ? page.substring(0, 200) + "..." : page) + "\"");
            }
            return "Signed in as the employee " + e.email + " with the data of the mail: the account page opened (" + driver.getCurrentUrl() + ").";
        });
    }

    /** The employee's account page: the welcome line, the header and every tab (after "Unmask all") compared with what the administrator's profile shows after the changes. */
    public void verifyEmployeeAccount(EorEmployee e, ValueChecks c) throws Exception {
        String page = (String) ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("return document.body.innerText;"); // the welcome line is above the main part of the page
        String acc = "Employee account";
        String lowerPage = page.toLowerCase(Locale.ROOT);
        c.check(acc, "The page welcomes the employee by name", lowerPage.contains(("Welcome, " + e.fullName()).toLowerCase(Locale.ROOT)), "Welcome, " + e.fullName(),
                shortRow(page.replaceAll("\\s+", " ")));
        String[] w = welcome.get(e.email);
        String mailId = w == null ? "" : w[1];
        c.check(acc, "The employee id of the mail is the employee code shown on the account", !mailId.isEmpty() && page.contains(mailId), mailId, shortRow(page.replaceAll("\\s+", " ")));
        profile.unmaskAll();
        c.check(acc, "\"Unmask all\" shows the hidden values (the button turns into \"Mask all\")", profile.unmasked(), "the button \"Mask all\" is shown", profile.unmasked() ? "shown" : "not shown");
        for (String tab : PROFILE_TABS) {
            c.guard(tab, "The tab opens and shows the values", () -> {
                profile.selectTab(tab);
                if (profile.unmaskAgainIfNeeded() && rep != null) {
                    rep.note("On the tab \"" + tab + "\" some values were still hidden after \"Unmask all\"; they were shown after \"Unmask all\" was used once more.");
                }
                compareExpected(e, tab, c, true);
            });
            if (rep != null) {
                rep.evidence("Employee account, tab " + tab, "The tab \"" + tab + "\" in the employee's own account, values unmasked");
            }
        }
        // (the header is read above)
        EorProfileChecks.header(run, e, headerShown(), c);
    }

    /** The "Log out" of the employee's account (top right) and, when asked, the confirmation. */
    public String signOutEmployee() {
        org.openqa.selenium.By logout = org.openqa.selenium.By.xpath("(//button[normalize-space()='Log out'] | //a[normalize-space()='Log out'])[1]");
        new org.openqa.selenium.support.ui.WebDriverWait(driver, java.time.Duration.ofSeconds(20)).until(org.openqa.selenium.support.ui.ExpectedConditions.elementToBeClickable(logout));
        driver.findElement(logout).click();
        org.openqa.selenium.By confirm = org.openqa.selenium.By.xpath("//div[@role='dialog']//button[normalize-space()='Log out']");
        try {
            new org.openqa.selenium.support.ui.WebDriverWait(driver, java.time.Duration.ofSeconds(4)).until(org.openqa.selenium.support.ui.ExpectedConditions.elementToBeClickable(confirm));
            driver.findElement(confirm).click();
        } catch (org.openqa.selenium.TimeoutException noDialog) {
            // signed out without a confirmation
        }
        try {
            new org.openqa.selenium.support.ui.WebDriverWait(driver, java.time.Duration.ofSeconds(15)).until(d -> !d.findElements(org.openqa.selenium.By.id("password")).isEmpty());
        } catch (org.openqa.selenium.TimeoutException stillIn) {
            throw new AssertionError("After \"Log out\" the login page did not appear.");
        }
        return "\"Log out\" was clicked: the login page is shown.";
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
