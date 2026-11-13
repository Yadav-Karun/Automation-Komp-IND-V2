package flow;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.openqa.selenium.WebDriver;
import org.testng.Assert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import model.AorEmployee;
import model.AorRun;
import pages.AorAgentsPage;
import pages.DeleteWindow;
import pages.AorDialog;
import pages.AorInvoicesPage;
import pages.AorPayrollPage;
import pages.LoginPage;
import reporting.ExecutionInfo;
import reporting.TestReporter;
import reporting.ValueChecks;
import utils.AorDataGenerator;
import utils.AorRunHistory;
import utils.AorWorkbooks;
import utils.ConfigManager;
import utils.Downloads;
import utils.Log;
import utils.PlainText;
import utils.SecretMasker;
import utils.TableReader;

/**
 * The AOR happy flow, one method per report step (specification "AOR Employee Onboarding & AOR Finance Automation"):
 * <ul>
 *   <li><b>Employee onboarding</b> - sign in as the AOR user, Employees &gt; AOR, Bulk Upload: a copy of the owner's workbook is filled with new employees
 *       (every data rule is checked in the file that was really written), uploaded, and the employees are looked up in the list of agents;</li>
 *   <li><b>AOR Finance</b> (depends on the employees of the first part) - the Payee Sheet is built from the SAME employees and uploaded for the run date, its entries
 *       are checked in the payroll list, then Bank Sheet, DA Invoices, Generate Invoice, Refresh Report + Download Report, and finally the invoice amounts are
 *       compared with the payouts of the Payee Sheet.</li>
 * </ul>
 * One {@link AorRun} holds everything the two parts share. Every sentence that reaches a report is plain language (standing rule of the project).
 */
public class AorOnboardingFinanceFlow {

    private static final DateTimeFormatter LIST_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final Pattern SESSION = Pattern.compile("PAYIN-(\\d{6})(\\d{6})-(\\d+)");

    private final WebDriver driver;
    private final LoginPage login;
    private final AorAgentsPage agents;
    private final AorPayrollPage payroll;
    private final AorInvoicesPage invoices;
    private TestReporter rep;

    /** The data of this run; null until the employee workbook is prepared (or loaded with -Daor.run.data). */
    private AorRun run;
    /** The payroll sessions that already existed for the run date before the Payee Sheet was uploaded. */
    private List<String> earlierSessions = new ArrayList<>();
    /** The first payroll session, once a second one is created for the delete check (the second Payee Sheet gets its own file name). */
    private String firstSessionId;
    private boolean secondSession;
    private int downloadRetries;

    public AorOnboardingFinanceFlow(WebDriver driver) {
        this.driver = driver;
        this.login = new LoginPage(driver);
        this.agents = new AorAgentsPage(driver);
        this.payroll = new AorPayrollPage(driver);
        this.invoices = new AorInvoicesPage(driver);
    }

    public void setReporter(TestReporter reporter) {
        this.rep = reporter;
    }

    public AorRun run() {
        return run;
    }

    public void useRun(AorRun existing) {
        this.run = existing;
    }

    private LocalDate runDate() {
        return LocalDate.parse(run.runDate);
    }

    /** The workbook the employees were uploaded with (a path, or - in older run data - a file name of this run's folder). */
    private Path employeeWorkbookPath() {
        Path p = Paths.get(run.employeeWorkbook);
        return p.isAbsolute() ? p : uploads().resolve(run.employeeWorkbook);
    }

    /** -Daor.session.reuse=true: the Payee Sheet goes into the payroll session that already exists for the run date (no unnecessary new session). */
    private static boolean reuseSession() {
        return ConfigManager.getBool("aor.session.reuse", false);
    }

    private Path uploads() {
        return ExecutionInfo.root().resolve("uploads");
    }

    // ================================================================== part 1: employee onboarding

    /** AOR-01 - sign in as the AOR user; the start page with the menu of the AOR user is shown. */
    public String signIn() {
        login.freshLogin(ConfigManager.baseUrl());
        login.login(ConfigManager.aorEmail(), ConfigManager.aorPassword());
        Assert.assertTrue(agents.sidebarVisible(), "After signing in, the start page of the AOR user did not open: the menu with \"Employees\" is not shown.");
        return "Signed in as " + SecretMasker.maskEmail(ConfigManager.aorEmail()) + ". The start page opened with the menu \"Employees\" and \"AOR Financials\".";
    }

    /** AOR-02 / AOR-03 - Employees, then AOR: the list of AOR agents is shown. */
    public String openAorEmployees() {
        agents.open();
        return "The page \"AOR Agents\" is open. " + (TableReader.showing(driver).isEmpty() ? "" : "The list says: \"" + TableReader.showing(driver) + "\".");
    }

    /** AOR-04 - Bulk Upload: the window with the file box is shown. */
    public String openBulkUpload() {
        agents.openBulkUpload();
        return "The window \"Bulk Upload Agents\" opened. It offers the buttons \"Download template\", \"Cancel\" and \"Upload\" and a box to choose a file.";
    }

    /**
     * AOR-05 - a copy of the owner's workbook is filled with new employees. Every rule of the owner is then checked in the file that was really written (not in
     * the program's memory), and the template itself must be unchanged.
     */
    public void prepareEmployeeWorkbook(ValueChecks c) throws IOException {
        Path template = AorWorkbooks.templateOf("aorBulkUploadTemplatePath", "aorBulkUploadExcelPath");

        int count = Math.min(5, ConfigManager.getInt("aor.employees", 5)); // the happy flow onboards at most 5 employees (user, 2026-11-11)
        run = AorDataGenerator.newRun(count);
        Path file = uploads().resolve(run.runId + "_AOR - Bulk Upload.xlsx");
        AorWorkbooks.writeEmployeeWorkbook(run, file);
        run.employeeWorkbook = file.toAbsolutePath().toString(); // absolute: a later run can read it again (-Daor.run.data)
        saveRunData();
        Log.info("AOR run " + run.runId + ": employee workbook " + file);
        rep.note("The workbook for the upload was saved as \"" + file.toAbsolutePath() + "\" (a copy of \"" + template.getFileName() + "\"). Aadhaar and PAN numbers come from "
                + "the list supplied by the QA owner and are not shown in this report.");

        List<Map<String, String>> rows = AorWorkbooks.readRows(file);
        int n = run.employees.size();
        String w = "Employee workbook";
        c.check(w, "Number of employee rows", rows.size() == n, n + " rows, one per new employee", rows.size() + " rows");
        rule(c, w, "Client name is the client given by the QA owner", rows, r -> run.client.equals(r.get("client name")), "\"" + run.client + "\" in every row");
        rule(c, w, "Project name is the project given by the QA owner", rows, r -> run.project.equals(r.get("project name")), "\"" + run.project + "\" in every row");
        rule(c, w, "State is a state of India", rows, r -> AorDataGenerator.states().contains(r.get("state")), "one of the states of India in every row");
        rule(c, w, "Hub location is the capital of the state", rows, r -> AorDataGenerator.capitalOf(r.get("state")).equals(r.get("hub location")),
                "for example Uttar Pradesh -> Lucknow");
        rule(c, w, "Delivery Partner Id is different for every employee", rows, unique(rows, "delivery partner id"), "no id twice");
        rule(c, w, "Company Employee Id is different for every employee", rows, unique(rows, "company employee id"), "no id twice");
        Set<String> dpUsed = AorRunHistory.used("DP");
        Set<String> empUsed = AorRunHistory.used("EMP");
        rule(c, w, "The ids are written to the run history (so no later run can reuse them)", rows,
                r -> dpUsed.contains(r.get("delivery partner id").toLowerCase(Locale.ROOT)) && empUsed.contains(r.get("company employee id").toLowerCase(Locale.ROOT)),
                "both ids of every employee are in testdata/aor-used-ids.txt");
        rule(c, w, "Name as per company, DP name as per company and the name are the same", rows,
                r -> r.get("name").equals(r.get("name as per company")) && r.get("name").equals(r.get("dp name as per company")), "identical in every row");
        rule(c, w, "Merge name is \"AOR-\" and the full name", rows, r -> ("AOR-" + r.get("name")).equals(r.get("marge name")), "for example \"AOR-Aarav Bhatia\"");
        rule(c, w, "Contact number has 10 digits", rows, r -> r.get("contact number").matches("\\d{10}"), "10 digits");
        rule(c, w, "Onboarding date is before 1 January 2025", rows, r -> LocalDate.parse(r.get("date of onboarding")).isBefore(LocalDate.of(2025, 1, 1)),
                "a date before 01-01-2025");
        rule(c, w, "Date of joining is before 1 January 2024", rows, r -> LocalDate.parse(r.get("date of joining")).isBefore(LocalDate.of(2024, 1, 1)),
                "a date before 01-01-2024");
        rule(c, w, "The employee is at least 18 years old on the run date (" + run.runDate + ")", rows,
                r -> ChronoUnit.YEARS.between(LocalDate.parse(r.get("date of birth")), runDate()) >= 18, "date of birth at least 18 years back");
        rule(c, w, "Hub name is the hub location, a hyphen and the Company Employee Id", rows,
                r -> (r.get("hub location") + "-" + r.get("company employee id")).equals(r.get("hub name")), "for example \"Lucknow-TSS-123456\"");
        rule(c, w, "DA status is \"Active\"", rows, r -> "Active".equals(r.get("da status")), "\"Active\" in every row");
        rule(c, w, "Personal e-mail is the name, a dot, the client and project letters and @yopmail.com", rows,
                r -> (r.get("name").replace(" ", "") + "." + AorDataGenerator.emailTag() + "@yopmail.com").equals(r.get("personal email id")), "for example \"AaravBhatia.BTSS@yopmail.com\"");
        List<String[]> pool = AorDataGenerator.identityPool();
        rule(c, w, "Aadhaar and PAN numbers are pairs from the list supplied by the QA owner", rows,
                r -> pool.stream().anyMatch(p -> p[0].equals(r.get("aadhar number")) && p[1].equals(r.get("pan number"))),
                "every pair is one of the supplied pairs");
        List<Map<String, String>> tpl = AorWorkbooks.readRows(template);
        rule(c, w, "Address, bank name, manager, area manager and supervisor keep the values of the template (only the columns of the QA owner's list are filled)", rows,
                indexed(rows, (r, i) -> {
                    Map<String, String> t = tpl.get(Math.min(i, tpl.size() - 1));
                    for (String col : List.of("address", "bank name", "manager name", "manager contact number", "area manager email", "area manager name", "supervisor name")) {
                        if (!String.valueOf(t.get(col)).trim().equals(String.valueOf(r.get(col)).trim())) {
                            return false;
                        }
                    }
                    return true;
                }), "the same values as the template row");
        rule(c, w, "These columns stay empty: letter issue date, letter issued by, PAN status, IFSC right", rows,
                r -> r.get("letter issue date").isEmpty() && r.get("letter issued by").isEmpty() && r.get("pan status").isEmpty() && r.get("ifsc-right").isEmpty(),
                "empty in every row");
        rule(c, w, "The values the template already had are kept (verified status, bank account length, acknowledgement)", rows,
                r -> "VERIFIED BANK AND IFSC CODE".equalsIgnoreCase(r.get("verified status")) && !r.get("bank a/c length").isEmpty()
                        && "yes".equalsIgnoreCase(r.get("acknowledgement status")),
                "\"VERIFIED BANK AND IFSC CODE\", a number, \"yes\"");
        rule(c, w, "IFSC length is 11", rows, r -> "11".equals(r.get("ifsc length")), "11 in every row");
        retainCopy(file, "aorBulkUploadExcelPath", rows);
    }

    /**
     * The file of the QA owner's folder holds the data of the latest run until the next run replaces it (user, 2026-11-11). Only the copy of the run was filled from the pristine
     * template; here it is copied over the owner's file. A file that is open in Excel cannot be replaced: that is only noted.
     */
    private void retainCopy(Path copy, String configKey, List<Map<String, String>> rows) {
        Path target = Paths.get(ConfigManager.document(configKey));
        try {
            Files.copy(copy, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            rep.note("The file \"" + target + "\" now holds the data of this run (" + PlainText.count(rows.size(), "row", "rows") + ") until the next run replaces it.");
        } catch (IOException e) {
            rep.note("The file \"" + target + "\" could not be replaced with the data of this run (is it open in Excel?): " + e.getMessage());
        }
    }

    /** AOR-06 - the workbook is chosen in the window and uploaded; the answer of the application is recorded. */
    public String uploadEmployeeWorkbook() {
        Assert.assertNotNull(run, "The employee workbook was not prepared.");
        Path file = employeeWorkbookPath();
        agents.attach(file);
        rep.evidence("workbook_chosen", "The upload window with the chosen workbook \"" + file.getFileName() + "\"");
        AorDialog.Answer a = agents.clickUploadAndWait(300);
        run.uploadResultText = a.everything();
        rep.note("What the application answered to the upload: " + a.describe());
        Assert.assertFalse(AorDialog.tellsOfProblem(a.everything()), "The application reported a problem with the employee upload. " + a.describe());
        Assert.assertTrue(!a.messages.isEmpty() || a.windowClosed || !a.windowText.isEmpty(), "The application gave no answer to the upload. " + a.describe());
        return "The workbook \"" + file.getFileName() + "\" with " + PlainText.count(run.employees.size(), "employee", "employees") + " was uploaded. " + a.describe();
    }

    /** AOR-07 - the answer is checked (nothing rejected) and every new employee is looked up in the list of AOR agents. */
    public void validateUpload(ValueChecks c) {
        String answer = run.uploadResultText == null ? "" : run.uploadResultText;
        int n = run.employees.size();
        c.check("Answer of the application", "No row was rejected and no error was reported", !AorDialog.tellsOfProblem(answer), "a success message and no rejected row",
                answer.isEmpty() ? "(no text)" : answer);
        int accepted = AorDialog.numberBefore(answer, "created", "added", "uploaded", "inserted", "success", "onboarded", "saved");
        int updated = AorDialog.numberBefore(answer, "updated");
        int rejected = AorDialog.numberBefore(answer, "failed", "rejected", "errors?", "invalid", "skipped");
        if (accepted >= 0 || updated >= 0 || rejected >= 0) {
            int ok = Math.max(accepted, 0) + Math.max(updated, 0);
            c.check("Answer of the application", "Rows submitted / accepted / updated / rejected", ok == n && Math.max(rejected, 0) == 0,
                    n + " rows submitted, " + n + " accepted or updated, 0 rejected",
                    n + " submitted; the application says accepted " + (accepted < 0 ? "-" : accepted) + ", updated " + (updated < 0 ? "-" : updated) + ", rejected "
                            + (rejected < 0 ? "-" : rejected));
        } else {
            rep.note("The application's answer does not give counts of accepted / updated / rejected rows, so they are not compared; each employee is looked up in the list instead.");
        }
        agents.dialog().dismiss();
        agents.open();
        for (AorEmployee e : run.employees) {
            List<Map<String, String>> found = agents.search(e.name);
            Map<String, String> row = found.stream().filter(r -> String.join(" ", r.values()).contains(e.name)).findFirst().orElse(null);
            String line = row == null ? "" : String.join(" | ", row.values());
            boolean listed = row != null && line.contains(e.state) && line.contains(run.client)
                    && (line.contains(e.companyEmployeeId) || line.contains(e.deliveryPartnerId));
            c.check("Agent \"" + e.name + "\"", "Is in the list of AOR agents", listed,
                    "listed with the state \"" + e.state + "\", the client \"" + run.client + "\" and the id \"" + e.companyEmployeeId + "\" or \"" + e.deliveryPartnerId + "\"",
                    row == null ? "not found in the list (search: \"" + e.name + "\")" : line);
        }
    }

    /**
     * AOR-08 - the profile of every new employee (Actions > View): each of the five tabs is opened in turn and every value is compared with what was written in the
     * employee workbook; a tab is finished before the next one is opened.
     */
    public void verifyProfiles(ValueChecks c) {
        agents.open();
        DateTimeFormatter shown = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
        List<Map<String, String>> sheet = AorWorkbooks.readRows(employeeWorkbookPath());
        for (AorEmployee e : run.employees) {
            Map<String, String> w = sheet.stream().filter(r -> e.deliveryPartnerId.equals(r.get("delivery partner id"))).findFirst().orElse(new LinkedHashMap<>());
            String who = "Employee \"" + e.name + "\"";
            agents.openProfileOf(e.name);
            List<String> top = agents.profileLines();
            int back = top.indexOf("Back to AOR Agents");
            List<String> head = top.subList(Math.max(back, 0), Math.min(top.size(), Math.max(back, 0) + 6));
            c.eq(who + " > Top of the profile", "Name", e.name, head.contains(e.name) ? e.name : "not in the heading (" + head + ")");
            c.eq(who + " > Top of the profile", "Phone number", e.contactNumber, String.valueOf(AorAgentsPage.valueOf(top, "PHONE NO.")));
            c.eq(who + " > Top of the profile", "E-mail", e.personalEmail, String.valueOf(AorAgentsPage.valueOf(top, "EMAIL")));
            c.eq(who + " > Top of the profile", "Client", run.client, String.valueOf(AorAgentsPage.valueOf(top, "CLIENT")));
            c.eq(who + " > Top of the profile", "Project", run.project, String.valueOf(AorAgentsPage.valueOf(top, "PROJECT")));

            profileTab(c, e, "Basic Details", Map.of(
                    "CLIENT NAME", run.client, "NAME AS PER COMPANY", e.name, "FATHER'S NAME", e.fatherName,
                    "DATE OF BIRTH", LocalDate.parse(e.birthDate).format(shown)));
            profileTab(c, e, "Personal Info", Map.of(
                    "MERGE NAME", "AOR-" + e.name, "DISPLAY NAME (AS PER BANK)", e.name, "FATHER/HUSBAND NAME", e.fatherName, "HUB LOCATION", e.hubLocation,
                    "HUB NAME", e.hubName, "DATE OF JOINING", LocalDate.parse(e.joiningDate).format(shown), "DATE OF BIRTH", LocalDate.parse(e.birthDate).format(shown),
                    "PERSONAL EMAIL ID", e.personalEmail, "ADDRESS", String.valueOf(w.getOrDefault("address", ""))));
            profileTab(c, e, "Agent Bank Details", Map.of("BANK NAME", String.valueOf(w.getOrDefault("bank name", "")), "ACCOUNT NUMBER", e.accountNumber, "IFSC CODE", e.ifscCode));
            Map<String, String> ids = new LinkedHashMap<>();
            if (e.aadhaar != null) {
                ids.put("AADHAAR NUMBER", e.aadhaar);
            }
            if (e.pan != null) {
                ids.put("PAN CARD NUMBER", e.pan);
            }
            if (e.drivingLicence != null && !e.drivingLicence.isEmpty()) {
                ids.put("DRIVING LICENCE NUMBER", e.drivingLicence);
            }
            profileTab(c, e, "Identification Information", ids);
            profileTab(c, e, "Area Manager/Supervisor", Map.of(
                    "MANAGER NAME", String.valueOf(w.getOrDefault("manager name", "")), "MANAGER CONTACT", String.valueOf(w.getOrDefault("manager contact number", "")),
                    "AREA MANAGER NAME", String.valueOf(w.getOrDefault("area manager name", "")), "AREA MANAGER EMAIL", String.valueOf(w.getOrDefault("area manager email", "")),
                    "SUPERVISOR NAME", String.valueOf(w.getOrDefault("supervisor name", ""))));
            agents.backToList();
        }
    }

    private void profileTab(ValueChecks c, AorEmployee e, String tab, Map<String, String> expected) {
        agents.selectProfileTab(tab);
        List<String> lines = agents.profileLines();
        String group = "Employee \"" + e.name + "\" > " + tab;
        for (Map.Entry<String, String> x : expected.entrySet()) {
            String got = AorAgentsPage.valueOf(lines, x.getKey());
            String field = x.getKey().charAt(0) + x.getKey().substring(1).toLowerCase(Locale.ROOT);
            c.eq(group, field, x.getValue().replaceAll("\s+", " ").trim(), got == null ? "(not on the page)" : got.replaceAll("\s+", " ").trim());
        }
        rep.evidence("profile_" + e.companyEmployeeId.toLowerCase(Locale.ROOT) + "_" + tab.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "_"),
                e.name + ": the tab \"" + tab + "\" of the profile");
    }

    // ================================================================== part 2: AOR Finance

    /** FIN-01 - AOR Financials, Payroll: the payroll page is shown. */
    public String openPayroll() {
        agents.dialog().dismiss();
        payroll.open();
        return "The page \"AOR Payroll\" is open with the actions \"Upload Pay Sheet\", \"Download Bank Sheet\", \"Download DA Invoices\" and \"Generate Invoice\". "
                + (payroll.showing().isEmpty() ? "" : "The list says: \"" + payroll.showing() + "\".");
    }

    /**
     * FIN-05 .. FIN-11 - the Payee Sheet is built from the SAME employees as the bulk upload and read back from the file: state, FHR id, Wm name, final payout,
     * remark, delivery hub.
     */
    public void preparePayeeSheet(ValueChecks c) {
        Assert.assertNotNull(run, "There are no employees: the employee upload has to run first.");
        AorDataGenerator.assignPayouts(run);
        Path file = uploads().resolve(run.runId + (secondSession ? "_2" : "") + "_AOR Payroll - Calculation.xlsx");
        AorWorkbooks.writePayeeWorkbook(run, file);
        run.payeeWorkbook = file.getFileName().toString();
        if (!secondSession) {
            retainCopy(file, "aorPayeeSheetExcelPath", AorWorkbooks.readRows(file));
        }
        saveRunData();
        rep.note("The Payee Sheet was saved as \"" + file.toAbsolutePath() + "\" (a copy of the owner's pay sheet template).");

        List<Map<String, String>> pay = AorWorkbooks.readRows(file);
        List<Map<String, String>> bulk = run.employeeWorkbook == null ? List.of() : AorWorkbooks.readRows(employeeWorkbookPath());
        boolean wmId = "employeeId".equalsIgnoreCase(ConfigManager.get("aor.payee.wmname", "name"));
        int n = run.employees.size();
        String w = "Payee Sheet";
        c.check(w, "Number of entries", pay.size() == n, n + " entries, one per employee of the bulk upload", pay.size() + " entries");
        rule(c, w, "State is the state of the employee in the bulk upload", pay, indexed(pay, (r, i) -> r.get("state").equals(bulk.get(i).get("state"))),
                "the same state as in the employee workbook");
        rule(c, w, "FHR id is the Delivery Partner Id of the employee", pay, indexed(pay, (r, i) -> r.get("fhrid").equals(bulk.get(i).get("delivery partner id"))),
                "the same id as in the employee workbook");
        rule(c, w, wmId ? "Wm name is the Company Employee Id of the employee" : "Wm name is the name of the employee (as in the owner's example sheets)", pay,
                indexed(pay, (r, i) -> r.get("wm name").equals(wmId ? bulk.get(i).get("company employee id") : bulk.get(i).get("name"))),
                wmId ? "the Company Employee Id" : "the employee's name");
        rule(c, w, "Final payout is a whole amount", pay, r -> r.get("final payout").matches("\\d{1,7}"), "a number such as 5321");
        rule(c, w, "Remark stays empty", pay, r -> r.get("remark").isEmpty(), "empty in every entry");
        rule(c, w, "Delivery hub is the Hub Name of the employee in the bulk upload", pay,
                indexed(pay, (r, i) -> r.get("delivery_hub").equals(bulk.get(i).get("hub name"))), "the same hub name as in the employee workbook");
        long total = run.employees.stream().mapToLong(e -> e.finalPayout).sum();
        c.check(w, "Total of all final payouts (used for the invoice check at the end)", total > 0, "a total", String.valueOf(total));
    }

    /** FIN-02 .. FIN-04 and FIN-12 - Upload Pay Sheet: client, project, pay period = the run date, the Payee Sheet; the answer is recorded. */
    public String uploadPayeeSheet() {
        Assert.assertNotNull(run, "There are no employees: the employee upload has to run first.");
        AorDialog d = payroll.openAction("Upload Pay Sheet", "Upload Pay Sheet");
        d.choose("Client", run.client);
        d.choose("Project", run.project);
        d.setDate("Period Start", runDate());
        d.setDate("Period End", runDate());
        waitEnabled(d, "Session", 5);
        earlierSessions = d.fieldEnabled("Session") ? d.optionsOf("Session") : new ArrayList<>();
        rep.note(earlierSessions.isEmpty()
                ? "No payroll session exists yet for this client, project and date: the upload creates a new one."
                : "Payroll sessions that already exist for this client, project and date: " + earlierSessions + ". The upload creates a new one (an unused session is not selected).");
        if (reuseSession() && !secondSession && !earlierSessions.isEmpty()) {
            String session = earlierSessions.get(earlierSessions.size() - 1);
            d.chooseContaining("Session", session);
            rep.note("As asked (-Daor.session.reuse=true) the earlier payroll session \"" + session + "\" is used again: no new session is created.");
        }
        d.attach(uploads().resolve(run.payeeWorkbook));
        rep.evidence("payee_sheet_chosen", "The upload window with client \"" + run.client + "\", project \"" + run.project + "\", the run date and the Payee Sheet \"" + run.payeeWorkbook + "\"");
        AorDialog.Answer a = d.clickAndWait("Upload", 300);
        rep.note("What the application answered to the Payee Sheet upload: " + a.describe());
        Assert.assertFalse(AorDialog.tellsOfProblem(a.everything()), "The application reported a problem with the Payee Sheet upload. " + a.describe());
        Assert.assertTrue(!a.messages.isEmpty() || a.windowClosed || !a.windowText.isEmpty(), "The application gave no answer to the Payee Sheet upload. " + a.describe());
        int created = AorDialog.numberBefore(a.everything(), "created");
        int updated = AorDialog.numberBefore(a.everything(), "updated");
        if (created >= 0 && Math.max(updated, 0) + created != run.employees.size()) {
            rep.defect("The application did not take over every entry of the Payee Sheet", run.employees.size() + " entries created or updated",
                    "Created " + created + ", updated " + Math.max(updated, 0) + " (the sheet has " + run.employees.size() + " entries)");
        }
        d.dismiss();
        return "The Payee Sheet \"" + run.payeeWorkbook + "\" was uploaded for " + run.client + " / " + run.project + " and the date " + run.runDate + ". " + a.describe();
    }

    /**
     * The SECOND upload (only there to have a second payroll session for the delete check; everything about the entries was checked after the first upload, user 2026-11-13):
     * finds the id of the session that the upload created - the session of the run date that was not there before - with one search, and checks nothing else.
     */
    public void identifySecondSession(ValueChecks c) {
        payroll.open();
        payroll.filterDates(runDate(), runDate());
        payroll.setPageSize(10);
        AorEmployee e = run.employees.get(0);
        payroll.search(e.name);
        String id = payroll.rows().stream().filter(r -> TableReader.column(r, "AGENT NAME").equals(e.name) && !earlierSessions.contains(TableReader.column(r, "SESSION ID")))
                .map(r -> TableReader.column(r, "SESSION ID")).findFirst().orElse(null);
        c.check("Payroll session", "The second upload created a new payroll session", id != null, "a session that was not there before (not one of " + earlierSessions + ")", id == null ? "no new session found" : id);
        if (id != null) {
            run.sessionId = id;
            saveRunData();
        }
        payroll.search("");
    }

    /**
     * FIN-12 - the payroll list for the run date shows an entry for every employee with the right hub, state, employee id, pay period and payout, all in ONE new payroll
     * session; its id is kept for the downloads and the invoice.
     */
    public void verifyPayeeEntries(ValueChecks c) {
        payroll.open();
        payroll.filterDates(runDate(), runDate());
        String size = payroll.setPageSize(10);
        if (!size.isEmpty()) {
            rep.note("The list shows 10 entries per page.");
        }
        String period = runDate().format(LIST_DATE);
        Set<String> sessions = new HashSet<>();
        for (AorEmployee e : run.employees) {
            payroll.search(e.name);
            Map<String, String> row = payroll.rows().stream().filter(r -> TableReader.column(r, "AGENT NAME").equals(e.name)
                    && (reuseSession() || !earlierSessions.contains(TableReader.column(r, "SESSION ID")))).findFirst().orElse(null);
            String shown = row == null ? "no entry found (search: \"" + e.name + "\")" : "hub \"" + TableReader.column(row, "DELIVERY HUB") + "\", employee id \""
                    + TableReader.column(row, "EMPLOYEE ID") + "\", state \"" + TableReader.column(row, "STATE") + "\", pay period \"" + TableReader.column(row, "PAY PERIOD")
                    + "\", final payout \"" + TableReader.column(row, "FINAL PAYOUT") + "\", session \"" + TableReader.column(row, "SESSION ID") + "\"";
            boolean ok = row != null && TableReader.column(row, "DELIVERY HUB").equals(e.hubName) && TableReader.column(row, "EMPLOYEE ID").equals(e.deliveryPartnerId)
                    && TableReader.column(row, "STATE").equals(e.state) && TableReader.column(row, "PAY PERIOD").equals(period)
                    && amount(TableReader.column(row, "FINAL PAYOUT")).compareTo(BigDecimal.valueOf(e.finalPayout)) == 0;
            if (row != null) {
                sessions.add(TableReader.column(row, "SESSION ID"));
            }
            c.check("Entry of \"" + e.name + "\"", "Is in the payroll list with the data of the Payee Sheet", ok,
                    "hub \"" + e.hubName + "\", employee id \"" + e.deliveryPartnerId + "\" (the list's \"Employee Id\" column shows the FHR id = Delivery Partner Id), state \"" + e.state + "\", pay period \"" + period + "\", final payout \""
                            + e.finalPayout + "\"", shown);
        }
        c.check("Payroll session", "All entries are in ONE payroll session", sessions.size() == 1, "one session id", sessions.isEmpty() ? "no session found" : sessions.toString());
        if (sessions.size() == 1) {
            run.sessionId = sessions.iterator().next();
            boolean fresh = reuseSession() || !earlierSessions.contains(run.sessionId);
            c.check("Payroll session", "The session is a new one (not an earlier session of the day)", fresh, "not one of " + (earlierSessions.isEmpty() ? "(no earlier session)" : earlierSessions),
                    run.sessionId);
            java.util.regex.Matcher m = SESSION.matcher(run.sessionId);
            String day = runDate().format(DateTimeFormatter.ofPattern("ddMMyy"));
            boolean looks = m.matches() && m.group(1).equals(day) && m.group(2).equals(day);
            c.check("Payroll session", "The session id carries the run date", looks, "PAYIN-" + day + day + "-<number>", run.sessionId);
            saveRunData();
        }
        payroll.search("");
        List<Map<String, String>> all = payroll.allRows();
        rep.evidence("payroll_list_for_the_date", "The payroll list for the run date with the entries of the new session");
        long mine = run.sessionId == null ? 0 : all.stream().filter(r -> run.sessionId.equals(TableReader.column(r, "SESSION ID"))).count();
        c.check("Payroll list", "All entries of the new session are listed for the run date", mine == run.employees.size(), run.employees.size() + " entries in the session",
                mine + " entries in the session (the list for the date has " + all.size() + " entries)");
        rep.note("The payroll list for the run date: " + payroll.showing());
        payroll.reload(); // the windows of the next steps must offer the NEW session (see AorPayrollPage#reload)
    }

    /**
     * Section 9 - Download Bank Sheet: same client, project, dates and payroll session; the file is saved in Downloads/AOR with a run-specific name. The file is
     * opened and checked: every employee of the session is in it with the bank account and IFSC code of the employee workbook, and nobody else.
     */
    public void downloadBankSheet(ValueChecks c) {
        Path file = download("Download Bank Sheet", "Download Bank Sheet", "BankSheet", this::fillSessionWindow);
        fileRows(c, "Bank Sheet", file);
        List<Map<String, String>> rows = AorWorkbooks.readRows(file);
        for (AorEmployee e : run.employees) {
            Map<String, String> row = rows.stream().filter(r -> e.name.equals(r.get("beneficiary name"))).findFirst().orElse(null);
            boolean ok = row != null && e.accountNumber.equals(row.get("beneficiary ac no")) && e.ifscCode.equals(row.get("ifsc"));
            c.check("Bank Sheet: " + e.name, "Is in the sheet with the bank account and IFSC code of the employee", ok,
                    "account number " + e.accountNumber + ", IFSC code " + e.ifscCode,
                    row == null ? "no row for this employee" : "account number " + row.get("beneficiary ac no") + ", IFSC code " + row.get("ifsc") + ", amount " + row.get("amt"));
        }
        // stricter (works also when two sessions hold the SAME employees): each employee once, with the amount of THIS session
        List<String> twice = new ArrayList<>();
        List<String> wrongAmount = new ArrayList<>();
        for (AorEmployee e : run.employees) {
            List<Map<String, String>> mine = rows.stream().filter(r -> e.name.equals(r.get("beneficiary name"))).collect(java.util.stream.Collectors.toList());
            if (mine.size() > 1) {
                twice.add(e.name + " (" + mine.size() + " rows: " + mine.stream().map(r -> r.get("amt")).toList() + ")");
            }
            if (!mine.isEmpty() && mine.stream().noneMatch(r -> amount(String.valueOf(r.get("amt"))).compareTo(afterDeduction(e.finalPayout)) == 0)) {
                wrongAmount.add(e.name + " (sheet " + mine.get(0).get("amt") + ", expected " + afterDeduction(e.finalPayout).toPlainString() + " = final payout " + e.finalPayout + " less 1%)");
            }
        }
        c.check("Bank Sheet", "Every employee of the session is listed exactly once", twice.isEmpty(), "one row per employee",
                twice.isEmpty() ? "one row per employee" : "listed more than once: " + PlainText.some(twice, 4));
        c.check("Bank Sheet", "ASSUMPTION, not a confirmed rule (pending the client / finance team): each amount is the final payout of the SELECTED session less 1% - seen in every run", wrongAmount.isEmpty(), "the final payout of this session less 1% for every employee",
                wrongAmount.isEmpty() ? "every amount matches" : "different amount: " + PlainText.some(wrongAmount, 4));
        c.check("Bank Sheet", "The number of rows equals the number of employees of the session", rows.size() == run.employees.size(), run.employees.size() + " rows", rows.size() + " rows");
        List<String> others = new ArrayList<>();
        for (Map<String, String> r : rows) {
            if (run.employees.stream().noneMatch(e -> e.name.equals(r.get("beneficiary name")))) {
                others.add(r.get("beneficiary name"));
            }
        }
        if (!others.isEmpty()) {
            rep.note("APPLICATION DEFECT (not an automation failure) - Bank Sheet of one payroll session lists payees of OTHER payroll sessions of the same date. "
                    + "How to reproduce: 1) AOR Financials > Payroll, the date " + runDate() + " has more than one payroll session with payees; "
                    + "2) click Download Bank Sheet and choose client \"" + run.client + "\", project \"" + run.project + "\", the date and the session \"" + run.sessionId + "\"; "
                    + "3) open the downloaded file \"" + file.getFileName() + "\" (kept in Downloads/AOR). "
                    + "Expected: only the " + PlainText.count(run.employees.size(), "employee", "employees") + " of the selected session. "
                    + "Actual: " + PlainText.count(rows.size(), "row", "rows") + ", including " + PlainText.count(others.size(), "payee", "payees") + " of other sessions: " + PlainText.some(others, 8) + ".");
        }
        c.check("Bank Sheet", "Contains only the employees of the selected payroll session", others.isEmpty(),
                "only the " + PlainText.count(run.employees.size(), "employee", "employees") + " of the session " + run.sessionId,
                others.isEmpty() ? "only those employees" : "also " + PlainText.count(others.size(), "other payee", "other payees") + " who are not in this session: " + PlainText.some(others, 6));
    }

    /** The amount of the Bank Sheet: the final payout less 1% (verified on 2490 -> 2465.10, 4572 -> 4526.28, 5595 -> 5539.05, 7450 -> 7375.50). */
    private static BigDecimal afterDeduction(int payout) {
        return BigDecimal.valueOf(payout).multiply(new BigDecimal("0.99")).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** INV-01 / INV-02 - Download DA Invoices: same client, project, dates and payroll session; the zip holds one invoice PDF for every employee. */
    public void downloadDaInvoices(ValueChecks c) throws IOException {
        Path file = download("Download DA Invoices", "Download DA Invoices", "DAInvoices", this::fillSessionWindow);
        List<String> names = new ArrayList<>();
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(file.toFile())) {
            z.stream().forEach(en -> names.add(en.getName()));
        }
        c.check("DA Invoices", "The file is a zip with PDF documents", !names.isEmpty() && names.stream().allMatch(n -> n.toLowerCase(Locale.ROOT).endsWith(".pdf")),
                "a zip with PDF files", names.isEmpty() ? "empty zip" : PlainText.count(names.size(), "file", "files") + ": " + PlainText.some(names, 4));
        for (AorEmployee e : run.employees) {
            boolean has = names.stream().anyMatch(n -> n.contains(e.companyEmployeeId) && n.contains(e.name));
            c.check("DA Invoices: " + e.name, "Has an invoice document", has, "a PDF named with \"" + e.companyEmployeeId + "\" and \"" + e.name + "\"",
                    has ? "found" : "no document for this employee");
        }
        int employeeDocuments = (int) names.stream().filter(n -> n.startsWith("Invoice - ")).count();
        c.check("DA Invoices", "No invoice document of anybody else", employeeDocuments == run.employees.size(),
                PlainText.count(run.employees.size(), "invoice document", "invoice documents") + " for the employees of the session",
                PlainText.count(employeeDocuments, "invoice document", "invoice documents"));
    }

    private void fillSessionWindow(AorDialog d) {
        d.choose("Client", run.client);
        d.choose("Project", run.project);
        d.setDate("Start Date", runDate());
        d.setDate("End Date", runDate());
        waitEnabled(d, "Payroll Session", 8);
        d.chooseContaining("Payroll Session", run.sessionId);
    }

    /** One table row: the file really is an Excel file with data rows, saved under a name with the run id. */
    private void fileRows(ValueChecks c, String where, Path file) {
        List<Map<String, String>> rows = AorWorkbooks.readRows(file);
        c.check(where, "The file was saved in Downloads/AOR with the run id in its name and opens as an Excel file",
                file.getFileName().toString().startsWith(run.runId) && !rows.isEmpty(),
                "\"" + run.runId + "_...\" with at least one data row", file.getFileName() + ", " + PlainText.count(rows.size(), "data row", "data rows"));
    }

    /** INV-03 / INV-04 - Generate Invoice for the payroll session; the application confirms. */
    public String generateInvoice() {
        AorDialog d = payroll.openAction("Generate Invoice", "Generate Invoice");
        d.choose("Client", run.client);
        d.choose("Project", run.project);
        d.setDate("Period Start", runDate());
        d.setDate("Period End", runDate());
        waitEnabled(d, "Payroll Session", 8);
        d.tick("Payroll Session", run.sessionId);
        rep.evidence("invoice_form_filled", "The Generate Invoice window with client, project, the run date and the session \"" + run.sessionId + "\"");
        AorDialog.Answer a = d.clickAndWait("Generate Invoice", 150);
        rep.note("What the application answered to Generate Invoice: " + a.describe());
        Assert.assertFalse(AorDialog.tellsOfProblem(a.everything()), "The application reported a problem while generating the invoice. " + a.describe());
        Assert.assertTrue(Pattern.compile("(?i)(success|generated|created)").matcher(a.everything()).find(),
                "The application did not confirm that the invoice was generated. " + a.describe());
        d.dismiss();
        return "The invoice for the payroll session \"" + run.sessionId + "\" was generated. " + a.describe();
    }

    /**
     * Section 11 - Refresh Report (wait until it is done), then Download Report with every column; the file is saved in Downloads/AOR. The report is opened:
     * it has one row per employee of the session with the right ids, hub, state and final payout (the full comparison with the screen is not part of the spec).
     */
    public void refreshAndDownloadReport(ValueChecks c) {
        payroll.open();
        List<String> messages = payroll.refreshReport(180);
        rep.note("After \"Refresh Report\" the application said: " + (messages.isEmpty() ? "nothing on the screen (the refresh finished without a message)" : "\"" + String.join("\" | \"", messages) + "\""));
        c.check("Refresh Report", "The refresh finished without a problem", !AorDialog.tellsOfProblem(String.join(" | ", messages)), "no error message",
                messages.isEmpty() ? "finished, no message" : String.join(" | ", messages));
        Path file = download("Download Report", "Download Report", "PayrollReport", d -> {
            d.choose("Client", run.client);
            waitEnabled(d, "Projects", 5);
            d.choose("Projects", run.project);
            d.setDate("Period Start Date", runDate());
            d.setDate("Period End Date", runDate());
            waitEnabled(d, "Session ID", 8);
            d.chooseContaining("Session ID", run.sessionId);
            int columns = d.tickAll("Select Columns");
            rep.note("All " + columns + " available report columns were selected.");
        });
        fileRows(c, "Payroll report", file);
        List<Map<String, String>> rows = AorWorkbooks.readRows(file);
        c.check("Payroll report", "One row per employee of the session", rows.size() == run.employees.size(), PlainText.count(run.employees.size(), "row", "rows"),
                PlainText.count(rows.size(), "row", "rows"));
        for (AorEmployee e : run.employees) {
            Map<String, String> row = rows.stream().filter(r -> e.companyEmployeeId.equals(r.get("company employee id"))).findFirst().orElse(null);
            boolean ok = row != null && e.deliveryPartnerId.equals(row.get("fhr id")) && e.hubName.equals(row.get("hub name")) && e.state.equals(row.get("state"))
                    && String.valueOf(e.finalPayout).equals(row.get("final payout"));
            c.check("Payroll report: " + e.name, "Has the ids, hub, state and final payout of the Payee Sheet", ok,
                    "FHR id " + e.deliveryPartnerId + ", hub " + e.hubName + ", state " + e.state + ", final payout " + e.finalPayout,
                    row == null ? "no row for this employee" : "FHR id " + row.get("fhr id") + ", hub " + row.get("hub name") + ", state " + row.get("state") + ", final payout " + row.get("final payout"));
        }
    }

    /**
     * PAYROLL TAB - validation of each employee on its own (no grand total, no state-wise logic): for every employee the payroll entry of this session is opened with
     * the "..." button and "View", the invoice on the screen is checked against the Payee Sheet / employee workbook, the invoice PDF is downloaded into Downloads/AOR under
     * a name with the session id and the employee id, and the PDF itself is checked.
     */
    public void verifyEmployeeInvoices(ValueChecks c) {
        payroll.open();
        payroll.filterDates(runDate(), runDate());
        String period = runDate().format(LIST_DATE);
        Path dir = Downloads.folder();
        for (AorEmployee e : run.employees) {
            String who = "Employee \"" + e.name + "\"";
            payroll.search(e.name);
            List<Map<String, String>> rows = payroll.rows();
            int index = -1;
            for (int i = 0; i < rows.size() && index < 0; i++) {
                if (e.name.equals(TableReader.column(rows.get(i), "AGENT NAME")) && run.sessionId.equals(TableReader.column(rows.get(i), "SESSION ID"))) {
                    index = i;
                }
            }
            c.check(who, "Has its own entry in payroll session " + run.sessionId, index >= 0, "an entry with this name in this session",
                    index >= 0 ? "found" : "no entry found (search: \"" + e.name + "\", " + PlainText.count(rows.size(), "entry", "entries") + " listed)");
            if (index < 0) {
                continue;
            }
            String text = payroll.viewInvoiceOfRow(index);
            Map<String, String> f = invoiceFields(text);
            rep.evidence("invoice_" + e.companyEmployeeId.toLowerCase(Locale.ROOT), "The invoice of " + e.name + " opened with \"View\" (session " + run.sessionId + ")");
            c.eq(who, "Invoice: agent name", e.name, f.get("agent name"));
            c.eq(who, "Invoice: reference id (the Company Employee Id)", e.companyEmployeeId, f.get("reference id"));
            c.eq(who, "Invoice: working location (the state)", e.state, f.get("working location"));
            c.eq(who, "Invoice: bank account number", e.accountNumber, f.get("account number"));
            c.eq(who, "Invoice: IFSC code", e.ifscCode, f.get("ifsc no"));
            if (e.pan != null && !e.pan.isEmpty()) {
                c.check(who, "Invoice: PAN number (masked here)", e.pan.equals(f.get("pan no")), AorEmployee.maskPan(e.pan), AorEmployee.maskPan(f.get("pan no")));
            }
            c.eq(who, "Invoice: pay period", period + " / " + period, f.get("pay period"));
            BigDecimal shown = amount(f.getOrDefault("total payable", ""));
            c.check(who, "Invoice: total payable equals the final payout of the Payee Sheet entry", shown.compareTo(BigDecimal.valueOf(e.finalPayout)) == 0,
                    String.valueOf(e.finalPayout), f.containsKey("total payable") ? shown.toPlainString() : "no amount found on the invoice");

            Map<String, Long> before = Downloads.snapshot(dir);
            payroll.downloadOpenInvoice();
            Path file = Downloads.waitForNew(dir, before, 90);
            if (file == null) {
                c.check(who, "The invoice PDF is downloaded into Downloads/AOR", false, "a PDF file", "no file arrived within 90 seconds");
                payroll.dialog().dismiss();
                continue;
            }
            Path named = Downloads.renameForRun(file, run.runId, "PayrollInvoice_" + run.sessionId + "_" + e.companyEmployeeId + "_" + e.name.replace(" ", ""));
            run.downloads.add(named.getFileName().toString());
            c.check(who, "The invoice PDF is saved with the session id and the employee id in its name", Downloads.readable(named)
                    && named.getFileName().toString().contains(run.sessionId) && named.getFileName().toString().contains(e.companyEmployeeId), "a readable PDF in Downloads/AOR",
                    Downloads.describe(named));
            String pdf = Downloads.pdfText(named);
            if (pdf == null) {
                rep.note("The text of the PDF " + named.getFileName() + " could not be read (it may consist of pictures): only the file itself was checked.");
            } else {
                c.check(who, "The PDF belongs to this employee (name, reference id) and shows the payout", pdf.contains(e.name) && pdf.contains(e.companyEmployeeId)
                        && pdf.replace(",", "").contains(String.valueOf(e.finalPayout)), e.name + ", " + e.companyEmployeeId + ", " + e.finalPayout,
                        pdf.contains(e.name) ? "the name is there; reference id " + (pdf.contains(e.companyEmployeeId) ? "and payout " + (pdf.replace(",", "").contains(String.valueOf(e.finalPayout)) ? "too" : "but not the payout") : "is missing")
                                : "the name of the employee is not in the PDF");
            }
            payroll.dialog().dismiss();
        }
        payroll.search("");
        saveRunData();
    }

    /**
     * INVOICE TAB - the combined grand total: "Refresh Report" (until the application confirms), the invoice report for the same client and project, the month of the
     * run and all states is downloaded, every generated payroll session id is looked up on the Invoice tab, the state-wise amounts of ALL its invoices are taken from
     * the report (each invoice once) and added up; the total must equal the Payee Sheet grand total (the sum of the final payouts of all employees): difference 0.
     * No employee-wise matching here: an invoice entry is one state, and several states can be billed together under one entry.
     */
    public void invoiceTabGrandTotal(ValueChecks c) {
        invoices.open();
        List<String> messages = invoices.refreshReport(180);
        boolean refreshed = messages.stream().anyMatch(m -> m.toLowerCase(Locale.ROOT).contains("refreshed successfully"));
        c.check("Invoice report", "The refresh is confirmed by the application", refreshed, "\"... report refreshed successfully.\"",
                messages.isEmpty() ? "no message appeared" : "\"" + String.join("\" | \"", messages) + "\"");
        rep.evidence("invoice_report_refreshed", "The Invoice tab after \"Refresh Report\"");
        String month = runDate().toString().substring(0, 7);
        Path file = download(invoices::openReportDialog, "Download Report", "InvoiceReport", d -> {
            d.choose("Client", run.client);
            waitEnabled(d, "Projects", 8);
            d.choose("Projects", run.project);
            d.setText("Invoice Generate Month", month);
            waitEnabled(d, "State", 5);
            List<String> states = d.fieldEnabled("State") ? d.optionsOf("State") : new ArrayList<>();
            String all = states.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith("all")).findFirst().orElse(null);
            if (all != null) {
                d.choose("State", all);
                rep.note("In the report window the State \"" + all + "\" was selected (the list offers " + states.size() + " choices).");
            } else {
                rep.note("The State list of the report window offers no \"All\" choice (" + states + "): the State was left empty, which means all states.");
            }
        });
        c.check("Invoice report", "The report was downloaded into Downloads/AOR for client, project and the month " + month + " (all states)", Downloads.readable(file),
                "an Excel file", file.getFileName().toString());

        // every generated payroll session id on the Invoice tab (state-wise entries)
        List<String> sessions = new ArrayList<>(List.of(run.sessionId));
        Map<String, Map<String, String>> onScreen = new LinkedHashMap<>();
        for (String session : sessions) {
            List<Map<String, String>> found = new ArrayList<>();
            for (Map<String, String> r : invoices.search(session)) {
                if (session.equals(TableReader.column(r, "SESSION ID")) && run.client.equals(TableReader.column(r, "CLIENT NAME"))) {
                    found.add(r);
                    onScreen.putIfAbsent(TableReader.column(r, "INVOICE ID"), r);
                }
            }
            c.check("Payroll session " + session, "Is on the Invoice tab with its state-wise invoice entries", !found.isEmpty(), "at least one invoice entry",
                    found.isEmpty() ? "no invoice entry found for this session" : PlainText.count(found.size(), "entry", "entries") + ": "
                            + found.stream().map(r -> TableReader.column(r, "STATE") + " " + TableReader.column(r, "AMOUNT")).toList());
        }
        // every invoice of the session: Action > View > Download, saved in Downloads/AOR under its own name
        Path dir = Downloads.folder();
        for (Map.Entry<String, Map<String, String>> inv : onScreen.entrySet()) {
            String id = inv.getKey();
            String state = TableReader.column(inv.getValue(), "STATE");
            String who = "Invoice " + id + " (" + state + ")";
            List<Map<String, String>> rows = invoices.search(run.sessionId);
            int idx = -1;
            for (int i = 0; i < rows.size() && idx < 0; i++) {
                if (id.equals(TableReader.column(rows.get(i), "INVOICE ID"))) {
                    idx = i;
                }
            }
            c.check(who, "Is found on the Invoice tab by the session id", idx >= 0, "the invoice entry", idx >= 0 ? "found" : "not found");
            if (idx < 0) {
                continue;
            }
            String text = invoices.viewRow(idx);
            rep.evidence("invoice_view_" + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_"), "The invoice " + id + " (" + state + ") opened with Action, View");
            c.check(who, "The invoice window opens and shows this invoice", !text.isEmpty() && (text.contains(id) || text.toLowerCase(Locale.ROOT).contains("invoice")), "the invoice of " + id,
                    text.isEmpty() ? "no window opened" : "\"" + (text.length() > 160 ? text.substring(0, 160) + "..." : text) + "\"");
            Map<String, Long> before = Downloads.snapshot(dir);
            try {
                invoices.downloadOpenInvoice();
            } catch (AssertionError e) {
                c.check(who, "The invoice is downloaded", false, "a file in Downloads/AOR", e.getMessage());
                new AorDialog(driver).dismiss();
                continue;
            }
            Path file1 = Downloads.waitForNew(dir, before, 90);
            if (file1 == null) {
                c.check(who, "The invoice is downloaded", false, "a file in Downloads/AOR", "no file arrived within 90 seconds");
            } else {
                Path named = Downloads.renameForRun(file1, run.runId, "InvoiceTab_" + run.sessionId + "_" + id.replaceAll("[^A-Za-z0-9-]+", "-") + "_" + state.replaceAll("[^A-Za-z0-9]+", ""));
                run.downloads.add(named.getFileName().toString());
                c.check(who, "The invoice is downloaded into Downloads/AOR with the session id and invoice id in its name", Downloads.readable(named), "a readable file", Downloads.describe(named));
                String pdf = named.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf") ? Downloads.pdfText(named) : null;
                if (pdf != null) {
                    c.check(who, "The downloaded invoice carries the invoice id", pdf.contains(id), id, pdf.contains(id) ? "found" : "the id is not in the file");
                }
            }
            new AorDialog(driver).dismiss();
        }
        invoices.search("");

        // the state-wise amounts of the report: the sheet of the month only (the "All Months" sheet holds the same rows again), each voucher once
        String monthName = runDate().format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH));
        List<String> sheets = AorWorkbooks.sheetNames(file);
        String sheet = monthName + " - Sales Register";
        boolean hasSheet = sheets.contains(sheet);
        c.check("Invoice report", "Has the sales register of " + monthName, hasSheet, "a sheet \"" + sheet + "\"", sheets.toString());
        BigDecimal combined = BigDecimal.ZERO;
        if (hasSheet) {
            Map<String, Map<String, String>> byVoucher = new LinkedHashMap<>();
            List<String> twice = new ArrayList<>();
            for (Map<String, String> r : AorWorkbooks.readRows(file, sheet)) {
                if (!r.getOrDefault("voucher no.", "").isEmpty() && byVoucher.putIfAbsent(r.get("voucher no."), r) != null) {
                    twice.add(r.get("voucher no."));
                }
            }
            c.check("Invoice report", "No invoice is listed twice (nothing is counted twice)", twice.isEmpty(), "every invoice number once",
                    twice.isEmpty() ? "every invoice once (" + PlainText.count(byVoucher.size(), "invoice", "invoices") + " in the month)" : "listed twice: " + twice + " (counted once)");
            for (Map.Entry<String, Map<String, String>> inv : onScreen.entrySet()) {
                Map<String, String> row = byVoucher.get(inv.getKey());
                String uiState = TableReader.column(inv.getValue(), "STATE");
                BigDecimal uiAmount = amount(TableReader.column(inv.getValue(), "AMOUNT"));
                if (row == null) {
                    c.check("Invoice " + inv.getKey() + " (" + uiState + ")", "Its state-wise amount is in the report", false, "a row with this invoice number", "no row in the report");
                    continue;
                }
                BigDecimal amount = amount(row.get("delivery partner fees"));
                boolean same = uiState.equals(row.get("state")) && amount.compareTo(uiAmount) == 0;
                c.check("Invoice " + inv.getKey() + " (" + uiState + ")", "State and amount in the report are the same as on the Invoice tab", same,
                        uiState + " " + uiAmount.toPlainString(), row.get("state") + " " + amount.toPlainString());
                combined = combined.add(amount);
            }
        }
        BigDecimal payeeTotal = run.employees.stream().map(e -> BigDecimal.valueOf(e.finalPayout)).reduce(BigDecimal.ZERO, BigDecimal::add);
        c.check("Grand total", "The combined invoice grand total equals the Payee Sheet grand total (difference 0)", combined.compareTo(payeeTotal) == 0,
                payeeTotal.toPlainString() + " (sum of the final payouts of all " + run.employees.size() + " employees)",
                combined.toPlainString() + " (sum of the state-wise amounts of the invoices of " + String.join(", ", sessions) + "); difference "
                        + payeeTotal.subtract(combined).toPlainString());
    }

    /** The values of an invoice window ("Agent Name : X Reference ID : Y ... Total Payable ? 6050.00"), by label. */
    private static Map<String, String> invoiceFields(String text) {
        Map<String, String> f = new LinkedHashMap<>();
        String[] labels = { "Agent Name", "Reference ID", "Working Location", "Pan No", "Account Number", "IFSC No", "Pay Period", "Address", "Bill To" };
        for (int i = 0; i < labels.length - 1; i++) {
            java.util.regex.Matcher m = Pattern.compile(Pattern.quote(labels[i]) + "\\s*:\\s*(.*?)\\s*" + Pattern.quote(labels[i + 1])).matcher(text);
            if (m.find()) {
                f.put(labels[i].toLowerCase(Locale.ROOT), m.group(1).trim());
            }
        }
        java.util.regex.Matcher total = Pattern.compile("Total Payable\\s*\\S?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)").matcher(text);
        if (total.find()) {
            f.put("total payable", total.group(1));
        }
        return f;
    }

    // ================================================================== delete: Payroll <-> Invoice stay in step

    /** A second payroll session for the delete check: the same employees, a new Payee Sheet (file name with _2), a new session. */
    public void prepareSecondPayeeSheet(ValueChecks c) {
        firstSessionId = run.sessionId;
        secondSession = true;
        payroll.open();
        preparePayeeSheet(c);
    }

    /**
     * Bank Sheet while TWO payroll sessions have payees on the same date (the first session is still there): first it is checked that both sessions are in the
     * payroll list, then the Bank Sheet of the second session is downloaded and checked like the first one - nobody of the other session may be in it.
     */
    public void downloadBankSheetWithTwoSessions(ValueChecks c) {
        List<Map<String, String>> rows = payrollRowsOfDate();
        long a = ofSession(rows, firstSessionId).size();
        long b = ofSession(rows, run.sessionId).size();
        c.check("Payroll list", "Two payroll sessions of this run have payees on the same date", a > 0 && b > 0, "entries in both " + firstSessionId + " and " + run.sessionId,
                firstSessionId + ": " + PlainText.count((int) a, "entry", "entries") + ", " + run.sessionId + ": " + PlainText.count((int) b, "entry", "entries"));
        rep.evidence("bank_two_sessions_list", "The payroll list of the run date: the sessions " + firstSessionId + " and " + run.sessionId + " both have payees");
        payroll.reload();
        downloadBankSheet(c);
    }

    /** After a deletion: the site is refreshed once, then the deleted session id is searched in the search bar of the Payroll tab and of the Invoice tab: nothing may be found. */
    private void searchDeletedSession(ValueChecks c, String session, String tag) {
        payroll.open();
        payroll.reload(); // the site is refreshed once
        payroll.filterDates(runDate(), runDate());
        payroll.search(session);
        long inPayroll = ofSession(payroll.rows(), session).size();
        rep.evidence("delete_" + tag + "_search_payroll", "After refreshing the site: the session id " + session + " searched on the Payroll tab");
        c.check("Deleted session " + session, "After refreshing the site, searching its id on the Payroll tab finds nothing", inPayroll == 0, "no entry", inPayroll == 0 ? "nothing found"
                : PlainText.count((int) inPayroll, "entry", "entries") + " found");
        payroll.search("");
        invoices.open();
        invoices.reload();
        long inInvoice = ofSession(invoices.search(session), session).size();
        rep.evidence("delete_" + tag + "_search_invoice", "After refreshing the site: the session id " + session + " searched on the Invoice tab");
        c.check("Deleted session " + session, "After refreshing the site, searching its id on the Invoice tab finds nothing", inInvoice == 0, "no invoice entry", inInvoice == 0 ? "nothing found"
                : PlainText.count((int) inInvoice, "invoice entry", "invoice entries") + " found");
        invoices.search("");
    }

    private List<Map<String, String>> payrollRowsOfDate() {
        payroll.open();
        payroll.filterDates(runDate(), runDate());
        payroll.setPageSize(10);
        payroll.search("");
        return payroll.allRows();
    }

    private List<Map<String, String>> invoiceRowsAll() {
        invoices.open();
        invoices.search("");
        return invoices.allRows();
    }

    private static List<Map<String, String>> ofSession(List<Map<String, String>> rows, String session) {
        return rows.stream().filter(r -> session.equals(TableReader.column(r, "SESSION ID"))).collect(java.util.stream.Collectors.toList());
    }

    private boolean allMine(List<Map<String, String>> rows, java.util.function.Function<Map<String, String>, String> who) {
        Set<String> mine = new HashSet<>();
        run.employees.forEach(e -> mine.add(e.name));
        return rows.stream().allMatch(r -> mine.contains(who.apply(r)));
    }

    /** Takes the words "PAYIN-..." out of the text of the confirmation window: the ids it names. */
    private static List<String> sessionsIn(String text) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = Pattern.compile("PAYIN-\\d{12}-\\d+").matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /**
     * Delete from the PAYROLL tab (the first session of this run): Action > Delete on one entry of the session after the exact session id and the employee were
     * verified; the confirmation window is read; then the session must be gone from Payroll AND from the Invoice tab and the other sessions must be unchanged.
     */
    public void deleteSessionFromPayroll(ValueChecks c) {
        String session = firstSessionId != null ? firstSessionId : run.sessionId;
        Assert.assertNotNull(session, "There is no payroll session of this run to delete.");
        String where = "Session " + session + " (this run)";
        List<Map<String, String>> payBefore = payrollRowsOfDate();
        List<Map<String, String>> mineBefore = ofSession(payBefore, session);
        boolean safe = !mineBefore.isEmpty() && allMine(mineBefore, r -> TableReader.column(r, "AGENT NAME"));
        c.check(where, "Before deleting: the session is in the Payroll tab and holds only employees created by this test run", safe, "entries of this run's employees only",
                mineBefore.isEmpty() ? "no entry of the session found" : PlainText.count(mineBefore.size(), "entry", "entries") + (safe ? ", all of this run's employees" : ", some belong to other people: nothing is deleted"));
        List<Map<String, String>> invBefore = invoiceRowsAll();
        List<Map<String, String>> invMine = ofSession(invBefore, session);
        c.check(where, "Before deleting: the session is also in the Invoice tab", !invMine.isEmpty(), "at least one invoice entry of the session",
                invMine.isEmpty() ? "no invoice entry found" : PlainText.count(invMine.size(), "invoice entry", "invoice entries"));
        if (!safe) {
            return;
        }
        // the row to delete: found again through the search, the session id and the name are read from the row itself just before the click
        AorEmployee target = run.employees.get(0);
        payroll.open();
        payroll.filterDates(runDate(), runDate());
        payroll.search(session); // the session id goes into the search bar: only this session is listed
        List<Map<String, String>> rows = payroll.rows();
        if (rows.isEmpty()) {
            rep.note("The Payroll search did not find the session by its id; the first employee of the session is searched by name instead.");
            payroll.search(target.name);
            rows = payroll.rows();
        }
        int index = -1;
        for (int i = 0; i < rows.size() && index < 0; i++) {
            if (session.equals(TableReader.column(rows.get(i), "SESSION ID")) && allMine(List.of(rows.get(i)), r -> TableReader.column(r, "AGENT NAME"))) {
                index = i;
            }
        }
        c.check(where, "Searching the session id lists only this session, and its first entry is found (exact session id, employee of this run)", index >= 0
                && rows.stream().allMatch(r -> session.equals(TableReader.column(r, "SESSION ID"))), "only entries of " + session,
                rows.isEmpty() ? "nothing found" : PlainText.count(rows.size(), "entry", "entries") + " listed, sessions: " + rows.stream().map(r -> TableReader.column(r, "SESSION ID")).distinct().toList());
        if (index < 0) {
            return;
        }
        rep.evidence("delete_payroll_1_before", "The Payroll tab before the deletion: the entry of " + target.name + " in the session " + session);
        String popup = payroll.openDeleteOfRow(index);
        rep.evidence("delete_payroll_2_confirmation", "The confirmation window that \"Delete\" opens on the Payroll tab");
        List<String> named = sessionsIn(popup);
        boolean okToConfirm = !popup.isEmpty() && (named.isEmpty() || named.contains(session)) && named.stream().allMatch(session::equals);
        c.check("Confirmation window (Payroll tab)", "It opens and does not name a different session", okToConfirm, "a window; if it names a session, it is " + session,
                popup.isEmpty() ? "no window opened" : "\"" + popup + "\"");
        if (!okToConfirm) {
            DeleteWindow.cancel(driver);
            return;
        }
        List<String> messages = DeleteWindow.confirm(driver);
        rep.note("What the application answered to the deletion on the Payroll tab: " + (messages.isEmpty() ? "no message" : "\"" + String.join("\" | \"", messages) + "\""));
        searchDeletedSession(c, session, "payroll");
        List<Map<String, String>> payAfter = payrollRowsOfDate();
        rep.evidence("delete_payroll_3_after", "The Payroll tab after the deletion (all entries of the run date)");
        long left = ofSession(payAfter, session).size();
        c.check(where, "After deleting on the Payroll tab: the session is gone from the Payroll tab", left == 0, "no entry of the session",
                left == 0 ? "no entry of the session" : PlainText.count((int) left, "entry", "entries") + " of the session are still listed (the application removed " + (mineBefore.size() - left) + " of " + mineBefore.size() + ")");
        long others = payAfter.size() - left;
        c.check("Other payroll entries", "The entries of all other sessions are unchanged", others == payBefore.size() - mineBefore.size(), (payBefore.size() - mineBefore.size()) + " entries", others + " entries");
        List<Map<String, String>> invAfter = invoiceRowsAll();
        rep.evidence("delete_payroll_4_invoice_after", "The Invoice tab after the deletion on the Payroll tab");
        long invLeft = ofSession(invAfter, session).size();
        c.check(where, "After deleting on the Payroll tab: the session is gone from the Invoice tab too", invLeft == 0, "no invoice entry of the session",
                invLeft == 0 ? "no invoice entry of the session" : PlainText.count((int) invLeft, "invoice entry", "invoice entries") + " of the session are still listed");
        long invOthers = invAfter.size() - invLeft;
        c.check("Other invoices", "The invoices of all other sessions are unchanged", invOthers == invBefore.size() - invMine.size(), (invBefore.size() - invMine.size()) + " invoice entries",
                invOthers + " invoice entries");
    }

    /**
     * Delete from the INVOICE tab (the second session of this run): Action > Delete on an invoice entry of the session; then the session must be gone from the
     * Invoice tab AND from the Payroll tab and everything else must be unchanged.
     */
    public void deleteSessionFromInvoice(ValueChecks c) {
        String session = run.sessionId;
        Assert.assertNotNull(session, "There is no second payroll session of this run to delete.");
        Assert.assertNotEquals(session, firstSessionId, "The second payroll session has the id of the first one: nothing is deleted.");
        String where = "Session " + session + " (this run)";
        List<Map<String, String>> payBefore = payrollRowsOfDate();
        List<Map<String, String>> payMine = ofSession(payBefore, session);
        boolean safe = !payMine.isEmpty() && allMine(payMine, r -> TableReader.column(r, "AGENT NAME"));
        c.check(where, "Before deleting: the session is in the Payroll tab and holds only employees created by this test run", safe, "entries of this run's employees only",
                payMine.isEmpty() ? "no entry of the session found" : PlainText.count(payMine.size(), "entry", "entries") + (safe ? ", all of this run's employees" : ", some belong to other people: nothing is deleted"));
        List<Map<String, String>> invBefore = invoiceRowsAll();
        List<Map<String, String>> invMine = ofSession(invBefore, session);
        c.check(where, "Before deleting: the session is in the Invoice tab", !invMine.isEmpty(), "at least one invoice entry of the session",
                invMine.isEmpty() ? "no invoice entry found" : PlainText.count(invMine.size(), "invoice entry", "invoice entries"));
        if (!safe || invMine.isEmpty()) {
            return;
        }
        invoices.open();
        int index = -1;
        List<Map<String, String>> rows = List.of();
        for (int attempt = 0; attempt < 3 && index < 0; attempt++) {
            if (attempt > 0) {
                invoices.reload(); // the list can be stale right after the invoice was generated
            }
            rows = invoices.search(session);
            for (int i = 0; i < rows.size() && index < 0; i++) {
                if (session.equals(TableReader.column(rows.get(i), "SESSION ID")) && run.client.equals(TableReader.column(rows.get(i), "CLIENT NAME"))) {
                    index = i;
                }
            }
        }
        c.check(where, "The invoice entry of this session is found (exact session id and client)", index >= 0, "one entry", index >= 0 ? "found" : "not found");
        if (index < 0) {
            return;
        }
        rep.evidence("delete_invoice_1_before", "The Invoice tab before the deletion: the entry of the session " + session);
        String popup = invoices.openDeleteOfRow(index);
        rep.evidence("delete_invoice_2_confirmation", "The confirmation window that \"Delete\" opens on the Invoice tab");
        List<String> named = sessionsIn(popup);
        boolean okToConfirm = !popup.isEmpty() && named.stream().allMatch(session::equals);
        c.check("Confirmation window (Invoice tab)", "It opens and does not name a different session", okToConfirm, "a window; if it names a session, it is " + session,
                popup.isEmpty() ? "no window opened" : "\"" + popup + "\"");
        if (!okToConfirm) {
            DeleteWindow.cancel(driver);
            return;
        }
        List<String> messages = DeleteWindow.confirm(driver);
        rep.note("What the application answered to the deletion on the Invoice tab: " + (messages.isEmpty() ? "no message" : "\"" + String.join("\" | \"", messages) + "\""));
        searchDeletedSession(c, session, "invoice");
        invoices.reload();
        List<Map<String, String>> invAfter = invoiceRowsAll();
        rep.evidence("delete_invoice_3_after", "The Invoice tab after the deletion (all invoices)");
        long invLeft = ofSession(invAfter, session).size();
        c.check(where, "After deleting on the Invoice tab: the session is gone from the Invoice tab", invLeft == 0, "no invoice entry of the session",
                invLeft == 0 ? "no invoice entry of the session" : PlainText.count((int) invLeft, "invoice entry", "invoice entries") + " of the session are still listed (the application removed "
                        + (invMine.size() - invLeft) + " of " + invMine.size() + ")");
        long invOthers = invAfter.size() - invLeft;
        c.check("Other invoices", "The invoices of all other sessions are unchanged", invOthers == invBefore.size() - invMine.size(), (invBefore.size() - invMine.size()) + " invoice entries",
                invOthers + " invoice entries");
        List<Map<String, String>> payAfter = payrollRowsOfDate();
        rep.evidence("delete_invoice_4_payroll_after", "The Payroll tab after the deletion on the Invoice tab (all entries of the run date)");
        long left = ofSession(payAfter, session).size();
        c.check(where, "After deleting on the Invoice tab: the session is gone from the Payroll tab too", left == 0, "no entry of the session",
                left == 0 ? "no entry of the session" : PlainText.count((int) left, "entry", "entries") + " of the session are still listed in the Payroll tab");
        long others = payAfter.size() - left;
        c.check("Other payroll entries", "The entries of all other sessions are unchanged", others == payBefore.size() - payMine.size(), (payBefore.size() - payMine.size()) + " entries", others + " entries");
    }

    // ================================================================== helpers

    private Path download(String button, String heading, String purpose, Consumer<AorDialog> fill) {
        return download(() -> payroll.openAction(button, heading), heading, purpose, fill);
    }

    /** Opens a download window (with the given opener), fills it, clicks Download and waits for the file; the file is renamed for the run and checked. */
    private Path download(java.util.function.Supplier<AorDialog> opener, String heading, String purpose, Consumer<AorDialog> fill) {
        Assert.assertNotNull(run.sessionId, "There is no payroll session: the Payee Sheet upload has to succeed first.");
        Path dir = Downloads.folder();
        Map<String, Long> before = Downloads.snapshot(dir);
        AorDialog d = null;
        AorDialog.Answer a = null;
        for (int attempt = 1; attempt <= 2 && a == null; attempt++) {
            try {
                d = opener.get();
                fill.accept(d);
                rep.evidence(purpose.toLowerCase(Locale.ROOT) + "_form", "The \"" + heading + "\" window filled in (session \"" + run.sessionId + "\")");
                a = d.clickAndWait("Download", 5);
            } catch (org.openqa.selenium.WebDriverException e) {
                if (attempt == 2) {
                    throw e;
                }
                rep.note("The window \"" + heading + "\" did not react at the first try (a slow page); it is closed and opened again once.");
                try {
                    if (d != null) {
                        d.dismiss();
                    }
                } catch (RuntimeException ignored) {
                    // the window may already be gone
                }
            }
        }
        Path file = Downloads.waitForNew(dir, before, 120);
        if (file == null) {
            throw new AssertionError("No file was downloaded within 2 minutes after clicking \"Download\" in \"" + heading + "\". " + a.describe());
        }
        Path named = Downloads.renameForRun(file, run.runId, purpose);
        run.downloads.add(named.getFileName().toString());
        saveRunData();
        d.dismiss();
        String storage = Downloads.storageError(named);
        if (storage != null && downloadRetries < 2) {
            downloadRetries++;
            rep.note("The application's file storage did not deliver the file at attempt " + downloadRetries + " (it answered \"" + storage + "\" instead of the file; saved as \"" + named.getFileName()
                    + "\" as evidence). The download is tried again.");
            Path again = download(opener, heading, purpose, fill);
            downloadRetries = 0;
            return again;
        }
        downloadRetries = 0;
        String what = Downloads.describe(named);
        Assert.assertTrue(Downloads.readable(named), "The downloaded file is missing, empty or cannot be opened: " + what);
        rep.note("The file was saved as \"" + named.toAbsolutePath() + "\": " + what + ".");
        return named;
    }

    private void waitEnabled(AorDialog d, String label, int seconds) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            try {
                if (d.fieldEnabled(label)) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // the field is not there yet
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static BigDecimal amount(String text) {
        String t = text == null ? "" : text.replaceAll("[^0-9.\\-]", "");
        return t.isEmpty() ? BigDecimal.ZERO : new BigDecimal(t);
    }

    /** One table row for a rule that has to hold in every row of a workbook: how many rows break it and which. */
    private void rule(ValueChecks c, String where, String what, List<Map<String, String>> rows, Predicate<Map<String, String>> holds, String expected) {
        List<String> bad = new ArrayList<>();
        for (Map<String, String> r : rows) {
            boolean ok;
            try {
                ok = holds.test(r);
            } catch (RuntimeException e) {
                ok = false;
            }
            if (!ok) {
                bad.add(String.valueOf(r.getOrDefault("name", r.getOrDefault("wm name", "row " + (bad.size() + 1)))));
            }
        }
        c.check(where, what, bad.isEmpty(), expected, bad.isEmpty() ? "true for all " + rows.size() + " rows" : bad.size() + " of " + rows.size() + " rows break it: " + PlainText.some(bad, 3));
    }

    /** Rows whose value of the column appears only once in the file. */
    private static Predicate<Map<String, String>> unique(List<Map<String, String>> rows, String column) {
        Map<String, Integer> count = new LinkedHashMap<>();
        rows.forEach(r -> count.merge(r.get(column).toLowerCase(Locale.ROOT), 1, Integer::sum));
        return r -> count.get(r.get(column).toLowerCase(Locale.ROOT)) == 1;
    }

    /** A rule that needs the position of the row (to compare it with the same row of the other workbook). */
    private static Predicate<Map<String, String>> indexed(List<Map<String, String>> rows, java.util.function.BiPredicate<Map<String, String>, Integer> rule) {
        return r -> rule.test(r, rows.indexOf(r));
    }

    /** The data of the run, without any Aadhaar / PAN number, next to the report (and the way to run the finance part again with -Daor.run.data). */
    public void saveRunData() {
        try {
            Files.createDirectories(ExecutionInfo.root());
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(Files.newOutputStream(ExecutionInfo.root().resolve("aor-run-data.json")), run);
        } catch (IOException e) {
            Log.warn("The run data was not saved: " + e.getMessage());
        }
    }

    /** The line under the case title: the run, the user (never a password) and what is known so far. */
    public String headline() {
        StringBuilder sb = new StringBuilder("AOR run: ").append(run == null ? "(not started)" : run.runId).append(" | signed in as ")
                .append(SecretMasker.maskEmail(ConfigManager.aorEmail()));
        if (run != null) {
            sb.append(" | client \"").append(run.client).append("\", project \"").append(run.project).append("\" | run date ").append(run.runDate).append(" | ")
                    .append(PlainText.count(run.employees.size(), "employee", "employees"));
            if (run.sessionId != null) {
                sb.append(" | payroll session ").append(run.sessionId);
            }
        }
        return sb.toString();
    }
}
