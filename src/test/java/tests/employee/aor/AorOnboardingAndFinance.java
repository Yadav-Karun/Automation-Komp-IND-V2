package tests.employee.aor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.testng.SkipException;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import base.BaseTest;
import flow.AorOnboardingFinanceFlow;
import model.AorRun;
import model.ClientData;
import reporting.TestReporter;
import utils.ConfigManager;
import utils.Downloads;
import utils.Log;

/**
 * EMPLOYEE - AOR: ONBOARDING AND FINANCE, HAPPY FLOW (positive path only; negative cases belong in a later suite).
 * Two cases in this order - the finance part depends completely on the employees of the first part:
 *
 *   TC-AOR-01  AOR employee onboarding: sign in as the AOR user, Employees > AOR > Bulk Upload, a copy of the owner's workbook is filled with new employees,
 *              uploaded, and the employees are found in the list of AOR agents
 *   TC-AOR-02  AOR Finance for the same employees: Payee Sheet upload for the run date, entries in the payroll list, Bank Sheet, DA Invoices, Generate Invoice,
 *              Refresh Report + Download Report, and the invoice amounts equal the payouts of the Payee Sheet
 *
 * The workbooks are the ones of the QA owner ({@code aorBulkUploadExcelPath}, {@code aorPayeeSheetExcelPath} in config/documents.properties): only COPIES are filled.
 * The AOR login is {@code sandbox.aor.email / sandbox.aor.password} in config/secrets.local.properties (never in code, never in a report). Aadhaar and PAN numbers
 * are the pairs of {@code config/aor-identities.local.txt}. Downloads are saved in the folder Downloads/AOR with the run id in their name.
 * Employees cannot be deleted by automation: a run creates {@code -Daor.employees} (default 5, at most 10) of them; their ids are kept in testdata/aor-used-ids.txt.
 *
 * Run:   mvn clean test -Dsuite=testng-aor-onboarding-finance.xml
 * Run the finance part again on the employees of an earlier run (nothing is created in the first part, TC-AOR-01 is written as "not run"):
 *        mvn clean test -Dsuite=testng-aor-onboarding-finance.xml -Daor.run.data=reports/AorOnboardingAndFinance/&lt;run folder&gt;/aor-run-data.json
 */
public class AorOnboardingAndFinance extends BaseTest {

    private static final String G_ONBOARD = "AOR employee onboarding";
    private static final String G_FINANCE = "AOR finance";

    private AorOnboardingFinanceFlow flow;
    /** The data of the run: it survives a restart of the browser (the pages are created again, the run is handed back). */
    private AorRun run;
    private boolean employeesUploaded;

    @Override
    protected void browserReady() {
        flow = new AorOnboardingFinanceFlow(driver);
        if (run != null) {
            flow.useRun(run);
        }
        try {
            Downloads.allowInto(driver, Downloads.folder()); // every download of this browser goes to Downloads/AOR
        } catch (RuntimeException e) {
            Log.warn("The browser could not be told where to save downloads: " + e.getMessage());
        }
    }

    /** -Daor.run.data=<aor-run-data.json of an earlier run>: the finance part runs on those employees, the onboarding part is not run. */
    private static Path earlierRun() {
        String p = ConfigManager.get("aor.run.data");
        return p == null ? null : Paths.get(p);
    }

    /** With -Daor.run.data the employees of that earlier run are used (whichever case runs first loads them). */
    private void loadEarlierRun() throws java.io.IOException {
        if (run == null && earlierRun() != null) {
            run = new ObjectMapper().readValue(Files.newInputStream(earlierRun()), AorRun.class);
            flow.useRun(run);
            employeesUploaded = true;
        }
    }

    // TC-AOR-01 - AOR employee onboarding. Steps: sign in (AOR-01) -> Employees > AOR (AOR-02, AOR-03) -> Bulk Upload window (AOR-04) -> the workbook is filled with new
    // employees and every data rule is checked in the file that was written (AOR-05) -> upload (AOR-06) -> the application's answer is checked and each employee is
    // looked up in the list of AOR agents (AOR-07). Expected: all employees are accepted, none rejected, and all are found in the list.
    @Test(priority = 1)
    public void tc01_employeeBulkUpload() throws Exception {
        if (earlierRun() == null && !isSelected("TC-AOR-01")) {
            skipRow("TC-AOR-01", G_ONBOARD, "AOR employee onboarding: new employees are uploaded with the bulk-upload workbook and appear in the list");
        }
        String title = "AOR employee onboarding: new employees are uploaded with the bulk-upload workbook and appear in the list";
        ClientData holder = holder();
        if (earlierRun() != null) {
            loadEarlierRun();
            TestReporter skipped = TestReporter.start("TC-AOR-01", G_ONBOARD, title, "Positive test of the AOR employee bulk upload", "-",
                    "The new employees are accepted and listed", driver, holder, flow::headline);
            SkipException skip = new SkipException("Not run in this run: the employees of the earlier run \"" + run.runId + "\" (-Daor.run.data) are used, nothing is uploaded. "
                    + "The result of the onboarding case is in the report of that run: " + earlierRun().toAbsolutePath().getParent().resolve("AorOnboardingAndFinance.html"));
            skipped.finish("SKIP", skip);
            throw skip;
        }
        int n = Math.min(5, ConfigManager.getInt("aor.employees", 5));
        TestReporter rep = TestReporter.start("TC-AOR-01", G_ONBOARD, title,
                "Positive test: the AOR user fills the supplied bulk-upload workbook with new employees of the client \"Bleach\", uploads it, and finds the employees in the list of AOR agents",
                "A copy of the supplied workbook with " + n + " new employees (random states, unique ids, data rules of the QA owner)",
                "The application accepts every employee and rejects none; each employee is in the list of AOR agents", driver, holder, flow::headline);
        flow.setReporter(rep);
        try {
            rep.phase("tc_aor_01_sign_in", "Sign in as the AOR user (AOR-01)", "The login succeeds and the start page of the AOR user is shown", flow::signIn);
            rep.phase("tc_aor_01_open_aor", "Open Employees, then AOR (AOR-02, AOR-03)", "The page with the list of AOR agents opens", flow::openAorEmployees);
            rep.phase("tc_aor_01_open_bulk_upload", "Open Bulk Upload (AOR-04)", "The upload window opens and offers a box to choose the workbook", flow::openBulkUpload);
            rep.phaseChecks("tc_aor_01_fill_workbook", "Fill the employee workbook (AOR-05)",
                    "The workbook has one row per new employee and follows every data rule of the QA owner; the supplied template is not changed", flow::prepareEmployeeWorkbook);
            rep.phase("tc_aor_01_upload", "Upload the workbook (AOR-06)", "The application accepts the workbook and answers with the result of the upload", flow::uploadEmployeeWorkbook);
            employeesUploaded = true;
            rep.phaseChecks("tc_aor_01_check_result", "Check the result of the upload (AOR-07)",
                    "No row is rejected and every new employee is found in the list of AOR agents", flow::validateUpload);
            rep.phaseChecks("tc_aor_01_profiles", "Open the profile of every new employee (Action, View) and check each tab in turn (AOR-08)",
                    "Basic Details, Personal Info, Agent Bank Details, Identification Information and Area Manager/Supervisor show exactly what was written in the workbook", flow::verifyProfiles);
        } finally {
            run = flow.run();
        }
        assertNoDefects(rep);
    }

    // TC-AOR-02 - AOR finance for the employees of TC-AOR-01. Steps: AOR Financials > Payroll (FIN-01) -> the Payee Sheet is built from the same employees (FIN-05..11) -> Upload
    // Pay Sheet for the run date (FIN-02..04) -> entries in the payroll list, one new payroll session (FIN-12) -> Bank Sheet download -> DA Invoices download (INV-01/02) ->
    // Generate Invoice (INV-03/04) -> Refresh Report and Download Report -> the invoice amounts equal the payouts of the Payee Sheet. Skipped when TC-AOR-01 did not upload.
    @Test(priority = 2)
    public void tc02_payrollAndInvoices() throws Exception {
        if (!isSelected("TC-AOR-02")) {
            skipRow("TC-AOR-02", G_FINANCE, "AOR finance: pay sheet, bank sheet, invoices and payroll report for the new employees");
        }
        loadEarlierRun();
        String title = "AOR finance: pay sheet, bank sheet, invoices and payroll report for the new employees";
        ClientData holder = holder();
        if (!employeesUploaded || run == null) {
            TestReporter rep = TestReporter.start("TC-AOR-02", G_FINANCE, title, "Positive test of AOR Finance for the employees of the bulk upload", "-",
                    "The pay sheet, bank sheet, invoices and payroll report work for the new employees", driver, holder, flow::headline);
            SkipException skip = new SkipException("The AOR employees were not uploaded (TC-AOR-01 did not get that far), so there are no employees to run the finance part for.");
            rep.finish("SKIP", skip);
            throw skip;
        }
        flow.useRun(run);
        TestReporter rep = TestReporter.start("TC-AOR-02", G_FINANCE, title,
                "Positive test: for the employees of the bulk upload the AOR user uploads the Payee Sheet, downloads the Bank Sheet and the DA Invoices, generates the invoice, "
                        + "refreshes and downloads the payroll report, and checks that the invoice amounts equal the payouts",
                "The Payee Sheet of the same employees for the run date " + run.runDate + "; client \"" + run.client + "\", project \"" + run.project + "\"",
                "Every step succeeds, the downloaded files can be opened, and the invoice total equals the total of the Payee Sheet payouts", driver, holder, flow::headline);
        flow.setReporter(rep);
        rep.phase("tc_aor_02_sign_in", "Sign in as the AOR user again, so that this case also works on its own (AOR-01)", "The login succeeds and the start page is shown", flow::signIn);
        rep.phase("tc_aor_02_open_payroll", "Open AOR Financials, then Payroll (FIN-01)", "The page \"AOR Payroll\" opens", flow::openPayroll);
        rep.phaseChecks("tc_aor_02_pay_sheet_file", "Prepare the Payee Sheet from the same employees (FIN-05 to FIN-11)",
                "The sheet has one entry per employee with the same state, id and hub as in the employee upload", flow::preparePayeeSheet);
        rep.phase("tc_aor_02_upload_pay_sheet", "Upload the Payee Sheet for client, project and the run date (FIN-02 to FIN-04)",
                "The application accepts the Payee Sheet and answers with the result", flow::uploadPayeeSheet);
        rep.phaseChecks("tc_aor_02_payroll_entries", "Check the entries in the payroll list (FIN-12)",
                "Every employee has an entry for the run date with the right hub, state, employee id and payout, all in one new payroll session", flow::verifyPayeeEntries);
        rep.phaseChecks("tc_aor_02_bank_sheet", "Download the Bank Sheet for the payroll session and check it",
                "The Bank Sheet is saved in the folder Downloads/AOR, opens, and lists the employees of the session with their bank details and nobody else", flow::downloadBankSheet);
        rep.phaseChecks("tc_aor_02_da_invoices", "Download the DA Invoices for the payroll session and check them (INV-01, INV-02)",
                "The DA invoice file is saved, opens, and holds an invoice document for every employee of the session", flow::downloadDaInvoices);
        rep.phase("tc_aor_02_generate_invoice", "Generate the invoice for the payroll session (INV-03, INV-04)", "The application confirms that the invoice was generated", flow::generateInvoice);
        rep.phaseChecks("tc_aor_02_payroll_report", "Refresh the payroll report, download it with all columns and check it",
                "The refresh finishes, and the report is saved in the folder Downloads/AOR, opens, and has a row with the right data for every employee", flow::refreshAndDownloadReport);
        // PAYROLL TAB: each employee on its own (View, invoice PDF, payout of that employee) - no grand total
        rep.phaseChecks("tc_aor_02_payroll_invoices", "Payroll tab: check the invoice of every employee on its own",
                "For each employee the invoice (View) shows the right employee, session, bank details, pay period and payout, and its PDF is saved in Downloads/AOR", flow::verifyEmployeeInvoices);
        // INVOICE TAB: the combined grand total over all state-wise invoice amounts - no employee-wise matching
        rep.phaseChecks("tc_aor_02_invoice_total", "Invoice tab: refresh the report, download it and compare the combined grand total",
                "The sum of all state-wise invoice amounts of the generated payroll session(s) equals the Payee Sheet grand total, difference 0", flow::invoiceTabGrandTotal);
        // DELETE: Payroll and Invoice must stay in step. Only sessions created by this run are touched.
        rep.phaseChecks("tc_aor_02_second_pay_sheet", "Second payroll session for the delete check: prepare the Payee Sheet again (same employees)",
                "A second Payee Sheet with the same employees is written", flow::prepareSecondPayeeSheet);
        rep.phase("tc_aor_02_second_upload", "Upload the second Payee Sheet", "The application creates a new payroll session", flow::uploadPayeeSheet);
        rep.phaseChecks("tc_aor_02_second_entries", "Check the entries of the second payroll session",
                "Every employee has an entry in one new payroll session", flow::verifyPayeeEntries);
        rep.phase("tc_aor_02_second_invoice", "Generate the invoice for the second payroll session", "The application confirms that the invoice was generated", flow::generateInvoice);
        rep.phaseChecks("tc_aor_02_bank_two_sessions", "Bank Sheet while two payroll sessions have payees on the same date: it must hold only the selected session",
                "The Bank Sheet of the second session lists only its own employees, once each, with the amounts of that session", flow::downloadBankSheetWithTwoSessions);
        rep.phaseChecks("tc_aor_02_delete_from_payroll", "Delete the payroll session of this run on the Payroll tab: it must also disappear from the Invoice tab",
                "Action, Delete (after the session id and the employee are checked) removes the session from the Payroll tab and the Invoice tab; other sessions stay", flow::deleteSessionFromPayroll);
        rep.phaseChecks("tc_aor_02_delete_from_invoice", "Delete the second payroll session on the Invoice tab: it must also disappear from the Payroll tab",
                "Action, Delete removes the session from the Invoice tab and the Payroll tab; other sessions stay", flow::deleteSessionFromInvoice);
        assertNoDefects(rep);
    }

    /** A case that -Dcases leaves out still has its row in the report (as "not run"). */
    private void skipRow(String id, String group, String title) {
        TestReporter skipped = TestReporter.start(id, group, title, "Positive test of the AOR module", "-", "See the case when it is run", driver, holder(), flow::headline);
        SkipException skip = new SkipException("Not run in this run: -Dcases does not list " + id + ". It runs in a full run of the suite.");
        skipped.finish("SKIP", skip);
        throw skip;
    }

    private ClientData holder() {
        ClientData holder = new ClientData(); // the report header needs a client record; the real header line is flow.headline()
        holder.legalName = "Bleach";
        holder.clientType = "AOR";
        holder.email = ConfigManager.aorEmail();
        return holder;
    }
}
