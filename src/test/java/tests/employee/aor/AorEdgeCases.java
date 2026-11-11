package tests.employee.aor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.testng.SkipException;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import base.BaseTest;
import model.AorEmployee;
import model.AorRun;
import model.ClientData;
import pages.AorAgentsPage;
import pages.AorDialog;
import pages.AorPayrollPage;
import pages.LoginPage;
import reporting.ExecutionInfo;
import reporting.ScenarioBoard;
import reporting.ExtentManager;
import reporting.TestReporter;
import utils.AorDataGenerator;
import utils.AorEdgeRegistry;
import utils.AorEmployeeBudget;
import utils.AorWorkbooks;
import utils.ConfigManager;
import utils.Log;
import utils.PlainText;
import utils.SecretMasker;

/**
 * EMPLOYEE - AOR: EDGE AND NEGATIVE CASES (the happy flow is {@link AorOnboardingAndFinance}).
 * <p>
 * Two cases, each holding all its scenarios as report steps (a scenario never stops the case; a finding is a red step and the case fails at the end):
 * <ul>
 *   <li>TC-AOR-EDGE-01 - the bulk upload of employees. The application answers per row and per column ("Row - 2 : Personal Email Id: value is not a valid email address"), so
 *       several wrong values are put into ONE row and the answer shows which of them the application noticed; a value that is not reported was accepted. Every refused
 *       row carries one value that is known to be refused (an empty Delivery Partner Id), so such a row can never create an employee;</li>
 *   <li>TC-AOR-EDGE-02 - the Payee Sheet (payroll calculation) for the base employees: a valid baseline, then one thing wrong at a time.</li>
 * </ul>
 * <b>Employee limit (user's rule): at most 5 employees are created for these cases, in ALL runs together</b> ({@link AorEdgeRegistry}, {@link AorEmployeeBudget}): the valid
 * base employees of an earlier run are re-used (read from the edge workbook), an upload that could create someone asks first, whoever the application wrongly accepts is
 * booked, and a scenario that does not fit any more is skipped with the reason.
 * <p>
 * The two files are the "...EdgeCases.xlsx" copies in the folder of the QA owner ({@code aorBulkUploadEdgeExcelPath}, {@code aorPayeeSheetEdgeExcelPath}); the pristine
 * files are only read. They are replaced on every upload, and the last step of each case writes them with the data of the employees that are really onboarded.
 * <p>
 * Run: mvn clean test -Dsuite=testng-aor-edge-cases.xml   (one case: -Dcases=TC-AOR-EDGE-01; some scenarios: -Daor.edge.only=U2,Q05)
 */
public class AorEdgeCases extends BaseTest {

    private static final String G_BULK = "AOR bulk upload - edge and negative cases";
    private static final String G_PAY = "AOR Payee Sheet - edge and negative cases";
    private static final DateTimeFormatter XL = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final Pattern TECHNICAL = Pattern.compile("(?i)(traceback|exception|sqlstate|constraint|integrityerror|nullpointer|stack trace|syntax error|undefined|violates|pydantic|validation errors? for|type=value_error|input_value|input should be)");
    private static final Pattern ROW = Pattern.compile("Row\\s*-?\\s*(\\d+)\\s*:\\s*");
    /** Columns that the QA owner's data rules leave blank or optional: a value in them that is not reported is not a finding. */
    private static final Set<String> OPTIONAL = Set.of("driving licence number", "vehicle insurance", "letter issue date", "letter issued by", "pan status");
    private static final String[][] DUPLICATES = { { "delivery partner id", "Delivery partner id" }, { "company employee id", "Company employee id" }, { "aadhar number", "Aadhaar number" },
            { "pan number", "PAN number" }, { "personal email id", "E-mail" }, { "contact number", "Contact number" }, { "account number", "Bank account number" } };

    /** Business-rule scenarios whose outcome is established (accepted, employee created) by the run of 2026-11-11_12.10.32. */
    private static final Set<String> ESTABLISHED = Set.of("S02", "S03", "S04", "S05", "S07", "S08", "S09", "S10", "S11", "S12", "S13", "S14");

    private LoginPage login;
    private AorAgentsPage agents;
    private AorPayrollPage payroll;
    private final AorEmployeeBudget budget = new AorEmployeeBudget();
    private TestReporter rep;
    private String tc = "";

    /** The valid base employees (re-used from the edge workbook when an earlier run onboarded them). */
    private AorRun run;
    private final List<AorEmployee> onboarded = new ArrayList<>();
    private final Map<AorEmployee, Map<String, String>> acceptedChanges = new LinkedHashMap<>();
    private int sessions;
    private final int sessionLimit = ConfigManager.getInt("aor.edge.sessions.max", 100);
    private int ran;
    private int shots;
    private final List<String> skippedForLimit = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    @Override
    protected void browserReady() {
        login = new LoginPage(driver);
        agents = new AorAgentsPage(driver);
        payroll = new AorPayrollPage(driver);
    }

    /** The edge workbooks of the QA owner's folder: written only by the closing step (R00) with the employees that really exist. */
    private static Path bulkEdge() {
        return Paths.get(ConfigManager.document("aorBulkUploadEdgeExcelPath"));
    }

    private static Path payEdge() {
        return Paths.get(ConfigManager.document("aorPayeeSheetEdgeExcelPath"));
    }

    private String currentId = "x";

    /** The workbook of the running scenario: kept in the run folder (uploads/) as evidence of exactly what was uploaded. */
    private Path bulkFile() {
        try {
            return scratch(idSlug(tc) + "_" + idSlug(currentId) + ".xlsx");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path payFile() {
        return bulkFile();
    }

    private Path scratch(String name) throws IOException {
        Path dir = ExecutionInfo.root().resolve("uploads");
        Files.createDirectories(dir);
        return dir.resolve(name);
    }

    private ClientData holder() {
        ClientData holder = new ClientData();
        holder.legalName = "Bleach";
        holder.clientType = "AOR";
        holder.email = ConfigManager.aorEmail();
        return holder;
    }

    private String headline() {
        return "AOR edge cases | signed in as " + SecretMasker.maskEmail(ConfigManager.aorEmail()) + " | client \"Bleach\", project \"The Substitute Shinigami\" | " + budget
                + " (all runs) | payroll sessions created " + sessions + " of " + sessionLimit + " | scenarios run " + ran;
    }

    // =================================================================================================================================== TC-AOR-EDGE-01

    // TC-AOR-EDGE-01 - bulk upload of AOR employees: edge and negative cases. B00 makes sure the valid base employees exist (re-used from an earlier run, else onboarded); then
    // unusable files, then rows with several wrong values (empty, wrong format, impossible dates, wrong client / project / country), then duplicates of an onboarded employee
    // and duplicates inside one file. The application must refuse every wrong row and name the wrong column; whoever it wrongly accepts is booked against the limit of 5.
    @Test(priority = 1)
    public void tc03_bulkUploadEdgeCases() throws Exception {
        runCase("TC-AOR-EDGE-01", "AOR bulk upload: wrong, missing and duplicate employee data is refused and the wrong column is named", () -> { }, this::defineEdge01);
    }

    /** The scenarios of TC-AOR-EDGE-01 in run order. Only scenario calls live here (the method is also run once without a browser to list the scenarios). */
    private void defineEdge01() {
            step("S00", "Sign in as the AOR user", "The login succeeds and the start page of the AOR user is shown", this::signIn);
            step("B00", "The valid base employees exist (re-used from the edge workbook, or onboarded now)", "At least " + base() + " valid employees are onboarded", this::ensureBase);

            // ---- unusable files (nobody can be created: no cost)
            fileScenario("W01", "A workbook with the column headings only (no employee)", () -> {
                AorRun none = new AorRun();
                none.client = "Bleach";
                none.project = "The Substitute Shinigami";
                AorWorkbooks.writeEmployeeWorkbook(none, bulkFile());
                return bulkFile();
            });
            fileScenario("W02", "A file with the extension .xlsx that is really plain text", () -> AorWorkbooks.writePlainText(bulkFile(), "this is not a workbook"));
            fileScenario("W03", "An empty (0 byte) file with the extension .xlsx", () -> {
                Files.write(bulkFile(), new byte[0]);
                return bulkFile();
            });
            fileScenario("W04", "A text file (.txt) instead of a workbook", () -> AorWorkbooks.writePlainText(scratch("employees.txt"), "Name,State\nA,B"));
            step("W05", "Upload clicked without choosing a file", "The application should not accept it and should say that a file is needed", () -> judgeNoEmployee(uploadBulkWithoutFile()));

            // ---- rows with several wrong values; every row also has an empty Delivery Partner Id, which the application refuses, so no row can create an employee
            batch("U1", "Every required value left empty (one row)", List.of(
                    spec("All required columns empty", x -> {
                        Map<String, String> map = new LinkedHashMap<>();
                        for (String col : REQUIRED) {
                            map.put(col, null);
                        }
                        return map;
                    })));
            batch("X00", "Diagnostic: a row with only the columns of the QA owner's list filled and an invalid PAN - which of the template's own values does the application object to?", List.of(
                    spec("Listed columns filled, template values kept", x -> m("pan number", "ABC123"))));

            // =============================================== ZERO COST: rows with several wrong values. Every row also carries an invalid Area Manager E-mail (the application checks the single values
            // of such a row and refuses it), so no row can create an employee, and a value the application does not report was accepted.
            batch("G1", "Contact number: letters, mixed, spaces, country codes, wrong lengths, symbols, blank (12 rows)", variants("contact number",
                    "abcdefghij", "98a7654321", "98765 43210", "+919876543210", "919876543210", "0123456789", "987654321", "98765432101", "+91-98765", "   ", "@@@@@@@@@@", null));
            batch("G2", "Aadhaar number: wrong lengths, letters, symbols, spaces, leading digits, zeros, blank (11 rows)", List.of(
                    spec("11 digits", x -> a("aadhar number", x.aadhaar.substring(0, 11))),
                    spec("13 digits", x -> a("aadhar number", x.aadhaar + "1")),
                    spec("letters", x -> a("aadhar number", "ABCDEFGHIJKL")),
                    spec("digits with a letter", x -> a("aadhar number", x.aadhaar.substring(0, 11) + "A")),
                    spec("symbols between digits", x -> a("aadhar number", "8679-4271-784")),
                    spec("spaces between digits", x -> a("aadhar number", "8679 4271 7842")),
                    spec("starts with 0", x -> a("aadhar number", "067942717842")),
                    spec("starts with 1", x -> a("aadhar number", "167942717842")),
                    spec("all zeros", x -> a("aadhar number", "000000000000")),
                    spec("only spaces", x -> a("aadhar number", "   ")),
                    spec("blank", x -> a("aadhar number", null))));
            batch("G3", "PAN number: lower case, mixed case, wrong lengths, symbols, spaces, wrong pattern, blank (10 rows)", List.of(
                    spec("lower case", x -> a("pan number", x.pan.toLowerCase(Locale.ROOT))),
                    spec("mixed case", x -> a("pan number", x.pan.substring(0, 2).toUpperCase(Locale.ROOT) + x.pan.substring(2, 5).toLowerCase(Locale.ROOT) + x.pan.substring(5))),
                    spec("9 characters", x -> a("pan number", x.pan.substring(0, 9))),
                    spec("11 characters", x -> a("pan number", x.pan + "A")),
                    spec("symbol inside", x -> a("pan number", "BIJPN28#5A")),
                    spec("space inside", x -> a("pan number", "BIJ PN2865A")),
                    spec("letters only", x -> a("pan number", "ABCDEFGHIJ")),
                    spec("digits only", x -> a("pan number", "1234567890")),
                    spec("only spaces", x -> a("pan number", "   ")),
                    spec("blank", x -> a("pan number", null))));
            batch("G4", "Company employee id: special characters, spaces, long, lower case, blank (9 rows)", variants("company employee id",
                    "TSS-@#$%", "TSS 12 34", "T".repeat(100), "tss-123456", "   ", null, "TSS-", "-----", "TSS-é123"));
            batch("G5", "Delivery partner id (FHRID): wrong formats, special characters, spaces, long, blank (8 rows)", variants("delivery partner id",
                    "12345", "dpa-xx", "@#$%^&", "DPA 12 34", "D".repeat(100), "   ", null, "DPA-"));
            batch("G6", "Every required value is only spaces / the word null (two rows)", List.of(
                    spec("only spaces in all required columns", x -> allRequired("   ")),
                    spec("the text null in all required columns", x -> allRequired("null"))));
            batch("G7", "Names and free text: digits, symbols, spaces around, accents, emoji, very long, script, SQL (7 rows)", List.of(
                    spec("name = digits; father name = digits; bank name = digits", x -> a("name", "12345", "father name", "12345", "bank name", "12345")),
                    spec("name = symbols; supervisor name = digits", x -> a("name", "@@@@@@", "supervisor name", "12345")),
                    spec("name with spaces around", x -> a("name", "  John Doe  ")),
                    spec("name with accents; hub location = digits", x -> a("name", "José Ñandú", "hub location", "12345")),
                    spec("name with emoji", x -> a("name", "John 😀")),
                    spec("name of 300 characters; address of 1000 characters", x -> a("name", "A".repeat(300), "address", "Flat 1, " + "B".repeat(1000))),
                    spec("name = script; address = SQL", x -> a("name", "<script>alert(1)</script>", "address", "x'); DROP TABLE employees;--"))));
            batch("G8", "Dates, e-mails, state, bank details, delivery status, manager contact (8 rows)", List.of(
                    spec("date of birth 31-Feb-1990; e-mail without @; state Atlantis; IFSC SBIN123; account letters; status Maybe",
                            x -> a("date of birth", "31-Feb-1990", "personal email id", "plainaddress", "state", "Atlantis", "ifsc code", "SBIN123", "account number", "ABCDEFGHIJK", "da status", "Maybe")),
                    spec("date of birth as text; e-mail with two @; state in lower case; IFSC digits; account too short; manager contact letters",
                            x -> a("date of birth", "not a date", "personal email id", "user@@yopmail.com", "state", "kerala", "ifsc code", "12345678901", "account number", "12345",
                                    "manager contact number", "abcdefghij")),
                    spec("date of birth 1990-13-45; e-mail with a space; state in upper case; IFSC lower case; account 30 digits; manager contact 5 digits",
                            x -> a("date of birth", "1990-13-45", "personal email id", "us er@yopmail.com", "state", "KERALA", "ifsc code", x.ifscCode.toLowerCase(Locale.ROOT), "account number", "9".repeat(30),
                                    "manager contact number", "12345")),
                    spec("date of birth 00-00-0000; e-mail without domain; state = digits; IFSC too long; account negative; manager contact 11 digits",
                            x -> a("date of birth", "00-00-0000", "personal email id", "user@", "state", "123", "ifsc code", "SBIN1234567890123456", "account number", "-12345678901",
                                    "manager contact number", "98765432101")),
                    spec("date of joining as text; e-mail without name; state with spaces; IFSC with symbols; account with spaces; status in lower case",
                            x -> a("date of joining", "not a date", "personal email id", "@yopmail.com", "state", "  Kerala  ", "ifsc code", "SBIN0@#$%^&", "account number", "1234 5678 901",
                                    "da status", "active")),
                    spec("date of onboarding as text; e-mail of 250 characters; IFSC only spaces; account only spaces",
                            x -> a("date of onboarding", "not a date", "personal email id", "a".repeat(250) + "@yopmail.com", "ifsc code", "   ", "account number", "   ")),
                    spec("e-mail without top-level domain; IFSC blank; account blank; status in upper case",
                            x -> a("personal email id", "user@yopmail", "ifsc code", null, "account number", null, "da status", "ACTIVE")),
                    spec("e-mail with accents; account of zeros", x -> a("personal email id", "üser@yopmail.com", "account number", "0000000000"))));

            // =============================================== DATA INTEGRITY: an existing employee must not be changed by a row that repeats its company employee id
            step("I00", "The first base employee: what the application holds now compared with what was uploaded", "Every value is the one that was uploaded", this::baseIntegrity);
            step("I01", "Existing employee: a new row repeats its company employee id (profile before and after)", "The row is refused and the existing employee stays exactly as it is", this::updateExisting);

            // =============================================== COST 1 EACH if the application accepts it: one row, valid except one value
            single("S01", "Date of birth in the future", "birth", x -> m("date of birth", LocalDate.now().plusYears(5).format(XL)));
            single("S02", "Employee younger than 18 (born 10 years ago)", "birth|age", x -> m("date of birth", LocalDate.now().minusYears(10).format(XL)));
            single("S03", "Employee one day short of 18 years", "birth|age", x -> m("date of birth", LocalDate.now().minusYears(18).plusDays(1).format(XL)));
            single("S04", "Date of joining in the future", "joining", x -> m("date of joining", LocalDate.now().plusYears(5).format(XL)));
            single("S05", "Date of onboarding in the future", "onboarding", x -> m("date of onboarding", LocalDate.now().plusYears(5).format(XL)));
            single("S06", "Date of joining 5 years before the date of birth", "joining|birth", x -> m("date of joining", LocalDate.parse(x.birthDate).minusYears(5).format(XL)));
            single("S07", "Hub name that does not follow the rule (hub location - company employee id)", "hub", x -> m("hub name", "RandomHub"));
            single("S08", "State that is not the state of the hub", "state", x -> m("state", x.state.equals("Kerala") ? "Goa" : "Kerala"));
            single("S09", "Country other than India", "country", x -> m("country", "US"));
            single("S10", "Delivery status that does not exist", "status", x -> m("da status", "Maybe"));
            single("S11", "Contact number of letters (valid otherwise)", "contact", x -> m("contact number", "abcdefghij"));
            single("S12", "E-mail address of an onboarded employee", "email|e-mail", x -> m("personal email id", run == null ? "x@yopmail.com" : run.employees.get(0).personalEmail));
            single("S13", "Contact number of an onboarded employee", "contact", x -> m("contact number", run == null ? "9999999999" : run.employees.get(0).contactNumber));
            single("S14", "Bank account number of an onboarded employee", "account", x -> m("account number", run == null ? "9999999999" : run.employees.get(0).accountNumber));
            single("S15", "Delivery partner id of an onboarded employee", "fhrid|delivery partner|already assigned", x -> m("delivery partner id", run == null ? "DPA-000000" : run.employees.get(0).deliveryPartnerId));

            // =============================================== several rows in one file
            step("M01", "Valid, invalid, valid: are the valid rows processed when another row fails?", "The invalid row is refused and named; what happens to the valid rows is recorded", this::mixedRows);
            step("M02", "The same employee twice in one file (two identical rows)", "At most one employee is created; the second row is refused with a message", this::identicalRows);
            step("U6", "Two rows of one file with the same delivery partner id and company employee id", "At most the first employee is created; the second row is refused with a message", this::sameFile);

            // =============================================== columns of the workbook
            columnScenario("K01", "A required column is missing (the heading of \"Name\" removed)", "name", w -> AorWorkbooks.changeHeader(w, "name", null), true);
            columnScenario("K02", "A column has the wrong heading (\"Name\" called \"Full Name\")", "name", w -> AorWorkbooks.changeHeader(w, "name", "Full Name"), true);
            columnScenario("K03", "An extra column that the template does not have", "", w -> AorWorkbooks.addColumn(w, "Extra Column", "extra value"), false);

            // =============================================== valid values at the edge: they must be accepted
            single("V01", "Contact number that starts with 6 (the lowest valid start)", "", x -> m("contact number", "6000000000"), true);
            single("V02", "Name with an apostrophe and a hyphen (O'Brien-Smith Jr.)", "", x -> m("name", "O'Brien-Smith Jr.", "name as per company", "O'Brien-Smith Jr.", "dp name as per company", "O'Brien-Smith Jr."), true);

            step("R00", "Leave the edge workbook with the data of the employees that are onboarded", "The file \"" + bulkEdge().getFileName() + "\" holds the onboarded employees", () -> {
                if (run == null) {
                    throw new SkipException("Not run: there are no base employees (B00).");
                }
                retainBulk();
                return "The file \"" + bulkEdge() + "\" now holds " + PlainText.count(onboarded.size(), "employee", "employees") + ": " + names();
            });
    }

    private static int base() {
        return Math.max(1, Math.min(3, ConfigManager.getInt("aor.edge.base", 2)));
    }

    /** B00: the base employees are read from the edge workbook (an earlier run wrote them there); only when too few exist are new ones onboarded. */
    private String ensureBase() {
        if ("X00".equalsIgnoreCase(ConfigManager.get("aor.edge.only"))) {
            throw new SkipException("Not needed for the diagnostic (X00).");
        }
        loadBaseFromFile();
        if (run == null || run.employees.size() < base()) {
            borrowFromHappyFlow();
        }
        int have = run == null ? 0 : run.employees.size();
        if (have >= base()) {
            String names = run.employees.stream().map(e -> e.name).toList().toString();
            return borrowed
                    ? PlainText.count(have, "valid employee", "valid employees") + " of the last happy flow are used as base employees (read from its files): " + names + ". Spare for the update check: "
                            + spare.stream().map(e -> e.name).toList() + ". Nobody is onboarded now and nothing is used up of the edge limit."
                    : PlainText.count(have, "valid employee", "valid employees") + " from earlier edge runs are re-used (read from \"" + bulkEdge().getFileName() + "\"): " + names + ". Nobody is onboarded now.";
        }
        int need = base() - have;
        if (!budget.reserve(need)) {
            throw new SkipException("Not run: " + need + " more base employee(s) are needed but the limit of " + budget.limit() + " employees is reached (" + budget + ").");
        }
        AorRun fresh = AorDataGenerator.newRun(need);
        AorDataGenerator.assignPayouts(fresh);
        AorWorkbooks.writeEmployeeWorkbook(fresh, bulkFile());
        AorDialog.Answer a = uploadBulk(bulkFile());
        int created = AorDialog.numberBefore(a.everything(), "created", "added", "inserted", "onboarded");
        if (created != need) {
            throw new AssertionError("The base employees were not created (" + need + " expected). " + a.describe());
        }
        budget.created(created);
        for (AorEmployee e : fresh.employees) {
            AorEdgeRegistry.record(fresh.runId, "base", e);
            onboarded.add(e);
        }
        if (run == null) {
            run = fresh;
        } else {
            run.employees.addAll(fresh.employees);
        }
        saveRun();
        return created + " valid employees were onboarded: " + a.describe();
    }

    /** Employees of the last happy flow (the files of the happy flow hold them): real, valid employees that cost nothing of the edge limit. The first ones are the base, the others are spare. */
    private final List<AorEmployee> spare = new ArrayList<>();
    private boolean borrowed;

    private void borrowFromHappyFlow() {
        Path happy = Paths.get(ConfigManager.document("aorBulkUploadExcelPath"));
        if (!Files.isRegularFile(happy)) {
            return;
        }
        List<Map<String, String>> rows;
        List<Map<String, String>> pays;
        try {
            rows = AorWorkbooks.readRows(happy);
            pays = AorWorkbooks.readRows(Paths.get(ConfigManager.document("aorPayeeSheetExcelPath")));
        } catch (RuntimeException e) {
            Log.warn("The files of the happy flow cannot be read, so no employee is borrowed: " + e.getMessage());
            return;
        }
        List<AorEmployee> found = new ArrayList<>();
        AorRun r = new AorRun();
        r.runId = "AOR-EDGE-" + LocalDate.now().toString().replace("-", "");
        r.runDate = LocalDate.now().toString();
        r.client = "Bleach";
        r.project = "The Substitute Shinigami";
        for (Map<String, String> row : rows) {
            if (row.getOrDefault("delivery partner id", "").isBlank() || row.getOrDefault("name", "").isBlank() || !row.getOrDefault("aadhar number", "").matches("\\d{12}")) {
                continue;
            }
            AorEmployee e = employeeOf(row, new LinkedHashMap<>());
            e.finalPayout = pays.stream().filter(p -> e.deliveryPartnerId.equals(p.get("fhrid"))).findFirst().map(p -> (int) Double.parseDouble(p.get("final payout"))).orElse(0);
            r.areaManagerName = row.get("area manager name");
            r.supervisorName = row.get("supervisor name");
            found.add(e);
        }
        if (found.size() < base()) {
            return;
        }
        r.employees.addAll(found.subList(0, base()));
        spare.addAll(found.subList(base(), found.size()));
        AorDataGenerator.assignPayoutsMissing(r);
        run = r;
        borrowed = true;
    }

    private static AorEmployee employeeOf(Map<String, String> row, Map<String, String> raw) {
        AorEmployee e = new AorEmployee();
        e.state = row.get("state");
        e.deliveryPartnerId = row.get("delivery partner id");
        e.companyEmployeeId = row.get("company employee id");
        e.name = row.get("name");
        e.fatherName = row.get("father name");
        e.contactNumber = digits(row, "contact number", raw);
        e.onboardingDate = row.get("date of onboarding");
        e.joiningDate = row.get("date of joining");
        e.birthDate = row.get("date of birth");
        e.hubLocation = row.get("hub location");
        e.hubName = row.get("hub name");
        e.personalEmail = row.get("personal email id");
        e.address = row.get("address");
        e.aadhaar = digits(row, "aadhar number", raw);
        e.pan = row.get("pan number");
        e.bankName = row.get("bank name");
        e.accountNumber = digits(row, "account number", raw);
        e.ifscCode = row.get("ifsc code");
        e.drivingLicence = row.get("driving licence number");
        e.vehicleInsurance = row.get("vehicle insurance");
        e.managerContactNumber = row.get("manager contact number");
        return e;
    }

    /** The employees of the edge workbook that are in the register: "base" ones become the base employees, "accepted" ones are only kept (they stay in the file). */
    private void loadBaseFromFile() {
        Map<String, String> kinds = new LinkedHashMap<>();
        for (String[] e : AorEdgeRegistry.entries()) {
            kinds.put(e[3], e[2]);
        }
        if (kinds.isEmpty() || !Files.exists(bulkEdge())) {
            return;
        }
        List<Map<String, String>> rows;
        try {
            rows = AorWorkbooks.readRows(bulkEdge());
        } catch (RuntimeException e) {
            Log.warn("The edge workbook cannot be read, so no base employee is re-used: " + e.getMessage());
            return;
        }
        AorRun r = new AorRun();
        r.runId = "AOR-EDGE-" + LocalDate.now().toString().replace("-", "");
        r.runDate = LocalDate.now().toString();
        r.client = "Bleach";
        r.project = "The Substitute Shinigami";
        List<AorEmployee> all = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String kind = kinds.get(row.getOrDefault("delivery partner id", ""));
            if (kind == null) {
                continue;
            }
            Map<String, String> raw = new LinkedHashMap<>();
            AorEmployee e = new AorEmployee();
            e.state = row.get("state");
            e.deliveryPartnerId = row.get("delivery partner id");
            e.companyEmployeeId = row.get("company employee id");
            e.name = row.get("name");
            e.fatherName = row.get("father name");
            e.contactNumber = digits(row, "contact number", raw);
            e.onboardingDate = row.get("date of onboarding");
            e.joiningDate = row.get("date of joining");
            e.birthDate = row.get("date of birth");
            e.hubLocation = row.get("hub location");
            e.hubName = row.get("hub name");
            e.personalEmail = row.get("personal email id");
            e.address = row.get("address");
            e.aadhaar = digits(row, "aadhar number", raw);
            e.pan = row.get("pan number");
            e.bankName = row.get("bank name");
            e.accountNumber = digits(row, "account number", raw);
            e.ifscCode = row.get("ifsc code");
            e.drivingLicence = row.get("driving licence number");
            e.vehicleInsurance = row.get("vehicle insurance");
            e.managerContactNumber = row.get("manager contact number");
            r.areaManagerName = row.get("area manager name");
            r.supervisorName = row.get("supervisor name");
            all.add(e);
            acceptedChanges.put(e, raw);
            if ("base".equals(kind)) {
                r.employees.add(e);
            }
        }
        if (!r.employees.isEmpty()) {
            AorDataGenerator.assignPayouts(r);
            run = r;
            onboarded.clear();
            onboarded.addAll(all);
        }
    }

    /** A number column of the file as digits; a value that is not a number (an accepted wrong value such as letters) is kept as it is in {@code raw} and written back unchanged. */
    private static String digits(Map<String, String> row, String column, Map<String, String> raw) {
        String v = row.getOrDefault(column, "");
        if (v.matches("\\d+")) {
            return v;
        }
        raw.put(column, v);
        return "0";
    }

    // ---- helpers of the zero-cost batches

    /** The invalid Area Manager E-mail that every batch row carries: the application checks the single values of such a row and refuses it. */
    private static final String ANCHOR_VALUE = "anchor@@yopmail.com";

    /** A change map whose row is always refused at field level: it carries an invalid value in a column that is NOT the one under test (an invalid PAN, or an invalid e-mail when the e-mail is tested). */
    private static final class AnchoredMap extends LinkedHashMap<String, String> {
        private static final long serialVersionUID = 1L;
        final String anchorKey;

        AnchoredMap(String anchorKey) {
            this.anchorKey = anchorKey;
        }
    }

    /** A change map that starts with the anchor, then pairs (header, value). */
    private static Map<String, String> a(String... kv) {
        boolean emailTested = false;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            emailTested |= kv[i].equals("personal email id");
        }
        AnchoredMap map = new AnchoredMap(emailTested ? "pan number" : "personal email id");
        map.put(map.anchorKey, emailTested ? "ABC123" : ANCHOR_VALUE);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    /** One row per value of one column. */
    private static List<Spec> variants(String column, String... values) {
        List<Spec> out = new ArrayList<>();
        for (String v : values) {
            out.add(spec(v == null ? "blank" : v.isBlank() ? "only spaces" : v.length() > 30 ? v.substring(0, 12) + "... (" + v.length() + " characters)" : v, x -> a(column, v)));
        }
        return out;
    }

    /** The columns that the QA owner's list fills (the others keep the template's values). */
    private static final List<String> REQUIRED = List.of("state", "delivery partner id", "company employee id", "name", "name as per company", "contact number", "father name",
            "date of onboarding", "hub location", "hub name", "date of joining", "date of birth", "personal email id", "aadhar number", "pan number", "account number", "ifsc code");

    private static Map<String, String> allRequired(String value) {
        AnchoredMap map = new AnchoredMap(null);
        for (String col : REQUIRED) {
            map.put(col, value);
        }
        return map;
    }

    // ---- COST: one row with one wrong value (or one valid edge value)

    private void single(String id, String title, String alias, Function<AorEmployee, Map<String, String>> change) {
        single(id, title, alias, change, false);
    }

    /** One row, valid except one value. {@code shouldBeAccepted}: the value is valid at its edge, so the row must be created. */
    private void single(String id, String title, String alias, Function<AorEmployee, Map<String, String>> change, boolean shouldBeAccepted) {
        step(id, title, shouldBeAccepted ? "The application accepts the employee" : "The application refuses the row, creates nobody and names the wrong value", () -> {
            if (ESTABLISHED.contains(id) && "true".equalsIgnoreCase(ConfigManager.get("aor.edge.skip.established"))) {
                throw new SkipException("Not repeated on request (-Daor.edge.skip.established=true): this scenario was run on 2026-11-11 (report reports/AorEdgeCases/2026-11-11_12.10.32) and the application ACCEPTED the row.");
            }
            needLimit(id, 1);
            AorRun one = AorDataGenerator.newRun(1);
            AorEmployee e = one.employees.get(0);
            Map<String, String> changes = change.apply(e);
            AorWorkbooks.writeEmployeeWorkbookWith(one, bulkFile(), List.of(changes));
            AorDialog.Answer a = uploadBulk(bulkFile());
            String all = a.everything();
            int created = Math.max(0, AorDialog.numberBefore(all, "created", "added", "inserted", "onboarded"));
            int updated = Math.max(0, AorDialog.numberBefore(all, "updated"));
            if (created > 0) {
                book(e, changes, shouldBeAccepted ? "base" : "accepted");
            }
            if (shouldBeAccepted) {
                if (created == 0) {
                    rep.defect("A valid value was refused", "The employee is accepted", a.describe());
                }
            } else if (created > 0) {
                rep.defect("The application accepted an employee that it should have refused (" + title + ")", "The row is refused and nobody is created",
                        "An employee was created (" + budget + "). " + a.describe());
            } else if (updated > 0) {
                rep.defect("The application changed an existing employee instead of refusing the row (" + title + ")", "The row is refused", a.describe());
            } else {
                Map<Integer, List<String>> issues = parseIssues(all);
                boolean named = alias.isBlank() || issues.values().stream().flatMap(List::stream).anyMatch(t -> Pattern.compile(alias, Pattern.CASE_INSENSITIVE).matcher(t).find());
                if (issues.isEmpty() && !AorDialog.tellsOfProblem(all)) {
                    rep.defect("The application did not say why the row was not created", "A message that says what is wrong", a.describe());
                } else if (!named) {
                    rep.defect("The application refused the row but its message does not name the wrong value (" + title + ")", "A message that names the wrong value", a.describe());
                }
                if (TECHNICAL.matcher(all).find()) {
                    rep.defect("The message shows developer text instead of a plain explanation (" + title + ")", "A message a user can understand", all);
                }
            }
            return a.describe();
        });
    }

    // ---- the profile of an employee, read page by page (Action > View)

    private static final String[][] PROFILE_TABS = {
            { "Header", "PHONE NO.", "EMAIL", "CLIENT", "PROJECT" },
            { "Basic Details", "CLIENT NAME", "NAME AS PER COMPANY", "FATHER'S NAME", "DATE OF BIRTH" },
            { "Personal Info", "MERGE NAME", "DISPLAY NAME (AS PER BANK)", "FATHER/HUSBAND NAME", "HUB LOCATION", "HUB NAME", "DA STATUS", "PAN STATUS", "DATE OF JOINING", "DATE OF BIRTH",
                    "PERSONAL EMAIL ID", "ADDRESS" },
            { "Agent Bank Details", "BANK NAME", "ACCOUNT NUMBER", "IFSC CODE" },
            { "Identification Information", "AADHAAR NUMBER", "PAN CARD NUMBER", "DRIVING LICENCE NUMBER" },
            { "Area Manager/Supervisor", "MANAGER NAME", "MANAGER CONTACT", "AREA MANAGER NAME", "AREA MANAGER EMAIL", "SUPERVISOR NAME" } };

    /** Every value of the profile of the employee whose row contains {@code searchText}: "Tab > LABEL" -> value; null when no such employee is in the list. */
    private Map<String, String> captureProfile(String searchText) {
        agents.dialog().dismiss();
        agents.open();
        List<Map<String, String>> found = agents.search(searchText);
        if (found.isEmpty()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("List > row (name, delivery partner id, state, client, status)", String.join(" | ", found.get(0).values()).replaceAll("\\s+", " ").trim());
        agents.openProfileOf(searchText);
        List<String> top = agents.profileLines();
        int back = top.indexOf("Back to AOR Agents");
        out.put("Header > NAME", back >= 0 && back + 2 < top.size() ? top.get(back + 2) : "?");
        for (String[] tab : PROFILE_TABS) {
            if (!tab[0].equals("Header")) {
                agents.selectProfileTab(tab[0]);
            }
            List<String> lines = agents.profileLines();
            for (int i = 1; i < tab.length; i++) {
                String v = AorAgentsPage.valueOf(lines, tab[i]);
                out.put(tab[0] + " > " + tab[i], v == null ? "(not shown)" : v);
            }
        }
        rep.evidence("profile_" + (++shots), "The profile of the employee found by \"" + searchText + "\"");
        agents.backToList();
        return out;
    }

    /** I00: what the application holds now for the first base employee compared with the data that was uploaded for him / her. */
    private String baseIntegrity() {
        if (run == null) {
            throw new SkipException("Not run: there are no base employees (B00).");
        }
        AorEmployee b = run.employees.get(0);
        Map<String, String> now = captureProfile(b.deliveryPartnerId);
        if (now == null) {
            now = captureProfile(b.name);
        }
        if (now == null) {
            rep.defect("An onboarded employee can no longer be found under the delivery partner id or the name that were uploaded",
                    "The employee " + b.name + " (" + b.deliveryPartnerId + ") is still in the list", "No employee found for \"" + b.deliveryPartnerId + "\" or \"" + b.name + "\"");
            return "The employee " + b.name + " was not found.";
        }
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Header > NAME", b.name);
        expected.put("Basic Details > FATHER'S NAME", b.fatherName);
        expected.put("Personal Info > HUB NAME", b.hubName);
        expected.put("Personal Info > PERSONAL EMAIL ID", b.personalEmail);
        expected.put("Personal Info > ADDRESS", b.address);
        expected.put("Agent Bank Details > ACCOUNT NUMBER", b.accountNumber);
        expected.put("Agent Bank Details > IFSC CODE", b.ifscCode);
        expected.put("Identification Information > AADHAAR NUMBER", b.aadhaar);
        expected.put("Identification Information > PAN CARD NUMBER", b.pan);
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String seen = now.getOrDefault(e.getKey(), "?");
            String a1 = seen.replaceAll("\\s+", " ").trim();
            String a2 = String.valueOf(e.getValue()).replaceAll("\\s+", " ").trim();
            boolean same = e.getKey().contains("EMAIL") ? a1.equalsIgnoreCase(a2) : a1.equals(a2); // an e-mail address is not case sensitive: the application stores it in lower case
            if (!same) {
                changed.add(e.getKey() + ": uploaded \"" + e.getValue() + "\", now \"" + seen + "\"");
            }
        }
        if (!changed.isEmpty()) {
            rep.defect("An onboarded employee no longer has the values that were uploaded for him / her (" + changed.size() + " values differ)", "Every value is the one that was uploaded",
                    String.join("; ", changed));
        }
        return changed.isEmpty() ? "Every checked value is as uploaded." : changed.size() + " values differ: " + String.join("; ", changed);
    }

    /** I01: a row that repeats the company employee id of an existing employee (an accepted test employee): profile before, upload, profile after, every changed field listed. */
    private String updateExisting() {
        AorEmployee target = !spare.isEmpty() ? spare.get(0) : (run != null && run.employees.size() > 2 ? run.employees.get(run.employees.size() - 1) : null); // a spare employee, else the last base employee
        for (AorEmployee e : onboarded) {
            if (target != null) {
                break;
            }
            if (run != null && !run.employees.contains(e)) {
                target = e; // a test employee that the application accepted wrongly: it is not needed as a base employee
            }
        }
        if (target == null) {
            throw new SkipException("Not run: there is no spare test employee whose record may be touched.");
        }
        Map<String, String> before = captureProfile(target.deliveryPartnerId);
        if (before == null) {
            throw new SkipException("Not run: the spare employee " + target.name + " is not in the list any more.");
        }
        AorRun one = AorDataGenerator.newRun(1);
        AorEmployee n = one.employees.get(0);
        Map<String, String> change = m("company employee id", target.companyEmployeeId, "hub name", n.hubLocation + "-" + target.companyEmployeeId);
        needLimit("I01", 1);
        AorWorkbooks.writeEmployeeWorkbookWith(one, bulkFile(), List.of(change));
        AorDialog.Answer a = uploadBulk(bulkFile());
        int created = Math.max(0, AorDialog.numberBefore(a.everything(), "created", "added", "inserted", "onboarded"));
        int updated = Math.max(0, AorDialog.numberBefore(a.everything(), "updated"));
        if (created > 0) {
            book(n, change);
        }
        Map<String, String> after = captureProfile(target.deliveryPartnerId);
        String foundBy = target.deliveryPartnerId;
        if (after == null) {
            after = captureProfile(n.deliveryPartnerId);
            foundBy = n.deliveryPartnerId;
        }
        if (after == null) {
            after = captureProfile(n.name);
            foundBy = n.name;
        }
        List<String> changed = new ArrayList<>();
        if (after != null) {
            for (Map.Entry<String, String> e : before.entrySet()) {
                String v = after.getOrDefault(e.getKey(), "?");
                if (!v.equals(e.getValue())) {
                    changed.add(e.getKey() + ": \"" + e.getValue() + "\" -> \"" + v + "\"");
                }
            }
        }
        String summary = "Upload answer: " + a.describe() + " Employees created " + created + ", updated " + updated + ". Profile found afterwards by \"" + foundBy + "\". Changed values: "
                + (changed.isEmpty() ? "none" : changed.size() + " - " + String.join("; ", changed));
        if (updated > 0 || !changed.isEmpty()) {
            rep.defect("A row that repeats the company employee id of an existing employee changed that employee (" + changed.size() + " values) instead of being refused",
                    "The row is refused and the existing employee stays exactly as it is", summary);
        } else if (created > 0) {
            rep.defect("A row that repeats the company employee id of an existing employee created a second employee", "The row is refused", summary);
        } else if (!AorDialog.tellsOfProblem(a.everything())) {
            rep.defect("The application did not say why the row was not created", "A message that says what is wrong", summary);
        }
        return summary;
    }

    /** M01: valid row, invalid row, valid row in one file. */
    private String mixedRows() {
        needLimit("M01", 2);
        AorRun three = AorDataGenerator.newRun(3);
        Map<String, String> bad = m("personal email id", "plainaddress");
        AorWorkbooks.writeEmployeeWorkbookWith(three, bulkFile(), List.of(new LinkedHashMap<>(), bad, new LinkedHashMap<>()));
        AorDialog.Answer a = uploadBulk(bulkFile());
        String all = a.everything();
        int created = Math.max(0, AorDialog.numberBefore(all, "created", "added", "inserted", "onboarded"));
        Map<Integer, List<String>> issues = parseIssues(all);
        int left = created;
        for (int i = 0; i < 3 && left > 0; i++) {
            if (!issues.containsKey(i + 2)) {
                left--;
                if (i == 1) {
                    book(three.employees.get(1), bad);
                    rep.defect("The invalid row in a file with valid rows was not refused", "The invalid row (row 3) is refused and named", a.describe());
                } else {
                    book(three.employees.get(i), new LinkedHashMap<>(), "base");
                }
            }
        }
        if (issues.containsKey(3) && !named(issues.get(3), "personal email id")) {
            rep.defect("The invalid row was refused without naming the e-mail address", "A message that names the e-mail address", a.describe());
        } else if (!issues.containsKey(3) && created == 0 && !AorDialog.tellsOfProblem(all)) {
            rep.defect("The application did not say why nothing was created", "A message that says what is wrong", a.describe());
        }
        String behaviour = created == 2 ? "The two valid rows were created although row 3 failed (rows are processed one by one)."
                : created == 0 ? "Nothing was created: one failing row made the application refuse the whole file." : "Only " + created + " of the 2 valid rows were created.";
        rep.note("Partial processing: " + behaviour);
        return behaviour + " " + a.describe();
    }

    /** M02: the same row twice. */
    private String identicalRows() {
        needLimit("M02", 2);
        AorRun one = AorDataGenerator.newRun(1);
        AorEmployee e = one.employees.get(0);
        AorWorkbooks.writeEmployeeWorkbook(one, bulkFile());
        AorWorkbooks.duplicateRow(bulkFile(), 1);
        AorDialog.Answer a = uploadBulk(bulkFile());
        int created = Math.max(0, AorDialog.numberBefore(a.everything(), "created", "added", "inserted", "onboarded"));
        if (created >= 1) {
            book(e, new LinkedHashMap<>(), "base");
        }
        if (created >= 2) {
            budget.created(1);
            rep.defect("The same employee was created twice from two identical rows", "Only one employee is created and the second row is refused", a.describe());
        } else if (!AorDialog.tellsOfProblem(a.everything()) && parseIssues(a.everything()).isEmpty()) {
            rep.defect("The second identical row was left out without a message that says why", "A message that names the problem", a.describe());
        }
        return a.describe();
    }

    /** A valid row in a workbook whose columns were changed. */
    private void columnScenario(String id, String title, String alias, java.util.function.Consumer<Path> change, boolean shouldBeRefused) {
        step(id, title, shouldBeRefused ? "The application refuses the file, creates nobody and names the column" : "The application accepts the employee or says clearly what it does with the extra column", () -> {
            needLimit(id, 1);
            AorRun one = AorDataGenerator.newRun(1);
            AorEmployee e = one.employees.get(0);
            AorWorkbooks.writeEmployeeWorkbook(one, bulkFile());
            change.accept(bulkFile());
            AorDialog.Answer a = uploadBulk(bulkFile());
            int created = Math.max(0, AorDialog.numberBefore(a.everything(), "created", "added", "inserted", "onboarded"));
            if (created > 0) {
                book(e, new LinkedHashMap<>(), shouldBeRefused ? "accepted" : "base");
            }
            if (shouldBeRefused && created > 0) {
                rep.defect("The application accepted a file whose columns are wrong (" + title + ")", "The file is refused", "An employee was created (" + budget + "). " + a.describe());
            } else if (shouldBeRefused && !AorDialog.tellsOfProblem(a.everything()) && parseIssues(a.everything()).isEmpty()) {
                rep.defect("The application did not say why the file was not accepted (" + title + ")", "A message that names the column", a.describe());
            } else if (shouldBeRefused && TECHNICAL.matcher(a.everything()).find()) {
                rep.defect("The message shows developer text instead of a plain explanation (" + title + ")", "A message a user can understand", a.everything());
            } else if (!shouldBeRefused && created == 0) {
                rep.defect("A file with an extra column was refused", "The extra column is ignored or explained", a.describe());
            }
            return a.describe();
        });
    }

    /** TC-AOR-EDGE-02 needs employees that really are onboarded with the uploaded ids: the others are left out (and reported). */
    private String keepIntactBase() {
        List<AorEmployee> intact = new ArrayList<>();
        List<String> left = new ArrayList<>();
        for (AorEmployee e : new ArrayList<>(run.employees)) {
            Map<String, String> p = captureProfile(e.deliveryPartnerId);
            if (p != null && e.name.equals(p.get("Header > NAME"))) {
                intact.add(e);
            } else {
                left.add(e.name + " (" + e.deliveryPartnerId + ")");
            }
        }
        if (intact.isEmpty()) {
            throw new SkipException("Not run: none of the base employees is in the application as it was uploaded.");
        }
        run.employees = intact;
        if (!left.isEmpty()) {
            rep.note("Left out of the Payee Sheet cases because the application no longer holds them as uploaded: " + String.join(", ", left));
        }
        return PlainText.count(intact.size(), "base employee", "base employees") + " used: " + intact.stream().map(e -> e.name).toList();
    }

    // ---- U5: duplicates of an onboarded employee

    private static Map<String, String> duplicateOf(AorEmployee x, AorEmployee b, String col) {
        Map<String, String> map = new LinkedHashMap<>();
        switch (col) {
            case "delivery partner id" -> map.put(col, b.deliveryPartnerId);
            case "company employee id" -> {
                map.put(col, b.companyEmployeeId);
                map.put("hub name", x.hubLocation + "-" + b.companyEmployeeId);
            }
            case "aadhar number" -> map.put(col, b.aadhaar);
            case "pan number" -> map.put(col, b.pan);
            case "personal email id" -> map.put(col, b.personalEmail);
            case "contact number" -> map.put(col, b.contactNumber);
            default -> map.put(col, b.accountNumber);
        }
        return map;
    }

    /** All seven values in one valid row first; what the application did not name is then tried alone, while the limit allows (each could create an employee). */
    private String duplicates() {
        if (run == null) {
            throw new SkipException("Not run: there are no base employees (B00).");
        }
        needLimit("U5", 1);
        AorEmployee base = run.employees.get(0);
        StringBuilder out = new StringBuilder();
        AorRun one = AorDataGenerator.newRun(1);
        AorEmployee e = one.employees.get(0);
        Map<String, String> all = new LinkedHashMap<>();
        for (String[] d : DUPLICATES) {
            all.putAll(duplicateOf(e, base, d[0]));
        }
        AorWorkbooks.writeEmployeeWorkbookWith(one, bulkFile(), List.of(all));
        AorDialog.Answer a = uploadBulk(bulkFile());
        out.append("All seven values in one row: ").append(a.describe()).append(" ");
        Map<Integer, List<String>> issues = parseIssues(a.everything());
        int created = Math.max(0, AorDialog.numberBefore(a.everything(), "created", "added", "inserted", "onboarded"));
        if (created > 0) {
            book(e, all);
            rep.defect("The application accepted an employee that repeats seven values of an onboarded employee", "The row is refused and the repeated values are named", a.describe());
            return out.toString();
        }
        List<String> unnamed = new ArrayList<>();
        for (String[] d : DUPLICATES) {
            if (!named(issues.get(2), d[0])) {
                unnamed.add(d[0]);
            }
        }
        for (String col : unnamed) {
            if (!budget.reserve(1)) {
                skippedForLimit.add("U5-" + col);
                out.append("Not tried alone (limit): ").append(labelOf(col)).append(". ");
                continue;
            }
            AorRun single = AorDataGenerator.newRun(1);
            AorEmployee s = single.employees.get(0);
            Map<String, String> ch = duplicateOf(s, base, col);
            AorWorkbooks.writeEmployeeWorkbookWith(single, bulkFile(), List.of(ch));
            AorDialog.Answer sa = uploadBulk(bulkFile());
            int c1 = Math.max(0, AorDialog.numberBefore(sa.everything(), "created", "added", "inserted", "onboarded"));
            String label = labelOf(col);
            int upd = Math.max(0, AorDialog.numberBefore(sa.everything(), "updated"));
            if (c1 > 0) {
                book(s, ch);
                rep.defect("The application accepted an employee whose " + label + " is already used by an onboarded employee", label + " is refused as already used", sa.describe());
            } else if (upd > 0) {
                rep.defect("The application changed an existing employee instead of refusing a row that repeats the " + label + " of that employee",
                        "The row is refused; the existing employee stays as it is", sa.describe());
            } else if (!named(parseIssues(sa.everything()).get(2), col)) {
                rep.defect("The application refused the row but did not name the repeated " + label, "A message that names " + label, sa.describe());
            }
            out.append(label).append(": ").append(sa.describe()).append(" ");
        }
        return out.toString();
    }

    private String sameFile() {
        needLimit("U6", 2);
        AorRun two = AorDataGenerator.newRun(2);
        AorEmployee a = two.employees.get(0);
        AorEmployee b = two.employees.get(1);
        Map<String, String> again = new LinkedHashMap<>();
        again.put("delivery partner id", a.deliveryPartnerId);
        again.put("company employee id", a.companyEmployeeId);
        again.put("hub name", b.hubLocation + "-" + a.companyEmployeeId);
        AorWorkbooks.writeEmployeeWorkbookWith(two, bulkFile(), List.of(new LinkedHashMap<>(), again));
        AorDialog.Answer answer = uploadBulk(bulkFile());
        int created = Math.max(0, AorDialog.numberBefore(answer.everything(), "created", "added", "inserted", "onboarded"));
        Map<Integer, List<String>> issues = parseIssues(answer.everything());
        if (created >= 1 && !issues.containsKey(2)) {
            book(a, new LinkedHashMap<>(), "base"); // a valid employee: it may serve as a base employee
        }
        if (created >= 2) {
            book(b, again);
            rep.defect("The application accepted both rows although the second repeats the ids of the first", "Only the first employee is created", answer.describe());
        } else if (!issues.containsKey(3) && !AorDialog.tellsOfProblem(answer.everything())) {
            rep.defect("The second row was left out without a message that says why", "A message that names the problem", answer.describe());
        }
        return answer.describe();
    }

    // =================================================================================================================================== TC-AOR-EDGE-02

    // TC-AOR-EDGE-02 - the Payee Sheet (payroll calculation) of the base employees: the window checked with missing choices, unusable files, one wrong value in an otherwise valid
    // row, and last a valid baseline sheet. Every wrong sheet must be refused with a clear message; a sheet that is wrongly accepted creates a payroll session, which is limited.
    @Test(priority = 2)
    public void tc04_payeeSheetEdgeCases() throws Exception {
        runCase("TC-AOR-EDGE-02", "AOR Payee Sheet: wrong, missing and duplicate payout rows are refused with a clear message", () -> {
            try {
                loadEarlierEdgeRun();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            if (run == null) {
                loadBaseFromFile();
            }
            if (run == null) {
                borrowFromHappyFlow();
            }
            if (run == null) {
                blockReason = "There are no base employees: run TC-AOR-EDGE-01 first (it onboards or re-uses them).";
            }
        }, this::defineEdge02);
    }

    /** The scenarios of TC-AOR-EDGE-02 in run order (only scenario calls: see defineEdge01). */
    private void defineEdge02() {
            step("S00", "Sign in as the AOR user", "The login succeeds and the start page of the AOR user is shown", this::signIn);
            step("B01", "Only base employees that still exist as uploaded are used (a row of TC-AOR-EDGE-01 may have changed view(run, 1))", "At least view(run, 1) base employee is as uploaded", this::keepIntactBase);

            step("P01", "Upload clicked without choosing a file", "The application should not accept it and should say that a file is needed", () ->
                    judgePayee(uploadPayee(null, "Bleach", "The Substitute Shinigami", LocalDate.now(), LocalDate.now())));
            step("P02", "Payee Sheet uploaded without choosing the client", "The application should not accept it", () -> {
                AorWorkbooks.writePayeeWorkbook(view(run, 1), payFile());
                return judgePayee(uploadPayee(payFile(), null, null, LocalDate.now(), LocalDate.now()));
            });
            step("P03", "Payee Sheet uploaded without choosing the project", "The application should not accept it", () -> {
                AorWorkbooks.writePayeeWorkbook(view(run, 1), payFile());
                return judgePayee(uploadPayee(payFile(), "Bleach", null, LocalDate.now(), LocalDate.now()));
            });
            step("P04", "Pay period that ends before it starts", "The application should not accept it", () -> {
                AorWorkbooks.writePayeeWorkbook(view(run, 1), payFile());
                return judgePayee(uploadPayee(payFile(), "Bleach", "The Substitute Shinigami", LocalDate.now(), LocalDate.now().minusDays(5)));
            });
            step("P05", "Pay period in the future", "The application should not accept a pay period that has not started", () -> {
                AorWorkbooks.writePayeeWorkbook(view(run, 1), payFile());
                return judgePayee(uploadPayee(payFile(), "Bleach", "The Substitute Shinigami", LocalDate.now().plusDays(30), LocalDate.now().plusDays(30)));
            });

            payeeFile("W01", "A Payee Sheet with the column headings only (no row)", () -> {
                AorWorkbooks.writePayeeWorkbook(view(run, 0), payFile());
                return payFile();
            });
            payeeFile("W02", "A file with the extension .xlsx that is really plain text", () -> AorWorkbooks.writePlainText(payFile(), "this is not a workbook"));
            payeeFile("W03", "An empty (0 byte) file with the extension .xlsx", () -> {
                Files.write(payFile(), new byte[0]);
                return payFile();
            });

            payeeRow("Q01", "FHRID of an employee that is not onboarded", m("fhrid", "DPA-000000"));
            payeeRow("Q02", "FHRID left empty", m("fhrid", null));
            payeeRow("Q07", "Final payout left empty", m("final payout", null));
            payeeRow("Q08", "Final payout written as text (abc)", m("final payout", "abc"));
            payeeRow("Q09", "Negative final payout (-500)", m("final payout", "-500"));
            payeeRow("Q10", "Final payout zero", m("final payout", "0"));
            payeeRow("Q11", "Final payout with three decimals (1234.567)", m("final payout", "1234.567"));
            payeeRow("Q12", "Final payout with 11 digits", m("final payout", "99999999999"));
            payeeRow("Q13", "Delivery hub left empty", m("delivery_hub", null));
            payeeRow("Q14", "Delivery hub that is not the hub of the employee", m("delivery_hub", "Nowhere-0000"));
            payeeRow("Q15", "Remark of 1000 characters", m("remark", "R".repeat(1000)));
            step("Q16", "The same employee in two rows of view(run, 1) sheet", "The application should take the employee once and refuse the repeated row, or refuse the sheet, with a message", () -> {
                if (sessions >= sessionLimit) {
                    throw new SkipException("Not run: the limit of " + sessionLimit + " payroll sessions is reached.");
                }
                AorRun v = view(run, 1);
                AorEmployee e = v.employees.get(0);
                Map<String, String> again = m("state", e.state, "fhrid", e.deliveryPartnerId, "wm name", e.name, "final payout", String.valueOf(e.finalPayout + 1), "delivery_hub", e.hubName);
                AorWorkbooks.writePayeeWorkbookWith(v, payFile(), List.of(), List.of(again));
                AorDialog.Answer a = uploadPayee(payFile(), "Bleach", "The Substitute Shinigami", LocalDate.now(), LocalDate.now());
                int created = Math.max(0, AorDialog.numberBefore(a.everything(), "created"));
                if (created > 0) {
                    sessions++;
                }
                if (created > 1) {
                    rep.defect("The same employee was paid twice in view(run, 1) sheet", "The repeated row is refused", a.describe());
                } else if (created == 0 && !AorDialog.tellsOfProblem(a.everything())) {
                    rep.defect("The application did not say why nothing was created", "A message that says what is wrong", a.describe());
                }
                return a.describe();
            });

            payeeRow("Q03", "State left empty", m("state", null));
            payeeRow("Q04", "State that is not the state of the employee", m("state", "Atlantis"));
            payeeRow("Q05", "Wm name left empty", m("wm name", null));
            payeeRow("Q06", "Wm name that is not the name of the employee", m("wm name", "Somebody Else"));

            step("Q00", "Valid Payee Sheet for the base employees", "The application accepts every row and creates the payroll entries", () -> {
                if (sessions >= sessionLimit) {
                    throw new SkipException("The limit of " + sessionLimit + " payroll sessions of this run is reached.");
                }
                AorWorkbooks.writePayeeWorkbook(run, payFile());
                AorDialog.Answer a = uploadPayee(payFile(), "Bleach", "The Substitute Shinigami", LocalDate.now(), LocalDate.now());
                int created = AorDialog.numberBefore(a.everything(), "created");
                int updated = AorDialog.numberBefore(a.everything(), "updated");
                if (created + Math.max(updated, 0) != run.employees.size()) {
                    rep.defect("A valid Payee Sheet was not accepted completely", run.employees.size() + " entries created", a.describe());
                }
                sessions++;
                return a.describe();
            });
            step("R00", "Leave the edge workbooks with the data of the onboarded employees", "The edge files hold the onboarded employees and the valid Payee Sheet that was uploaded", () -> {
                retainBulk();
                boolean paid = retainPayee();
                return "The file \"" + bulkEdge().getFileName() + "\" holds the rows of the onboarded employees as they were uploaded; "
                        + (paid ? "the file \"" + payEdge().getFileName() + "\" holds the valid Payee Sheet of this run." : "the Payee Sheet file was left as it was (the valid sheet was not uploaded in this run).");
            });
    }

    // =================================================================================================================================== scenario plumbing

    /** A map from pairs (header, value); a null value clears the cell. */
    private static Map<String, String> m(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    private static String idSlug(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private String names() {
        return onboarded.stream().map(e -> e.name).toList().toString();
    }

    private boolean selected(String id) {
        if (!tcSelected) {
            return false;
        }
        String only = ConfigManager.get("aor.edge.only"); // -Daor.edge.only=U2,Q05: only these scenarios (sign-in, base employees and the closing step always run)
        return only == null || List.of("S00", "B00").contains(id) || ("R00".equals(id) && "true".equalsIgnoreCase(ConfigManager.get("aor.edge.retain"))) || java.util.Arrays.stream(only.split(",")).anyMatch(x -> x.trim().equalsIgnoreCase(id));
    }

    // ------------------------------------------------------------------ the reporting standard: every scenario is ONE report entry (left panel) with its own verdict and evidence

    @FunctionalInterface
    private interface ScenarioRunner {
        void run(TestReporter r, boolean chosen) throws Throwable;
    }

    private boolean tcSelected = true;
    private boolean planning;
    private final List<String> plannedLabels = new ArrayList<>();
    private String blockReason;

    /** What the application said it created / updated (for the "record impact" line of the scenario). */
    private void countImpact(AorDialog.Answer a) {
        impactCreated += Math.max(0, AorDialog.numberBefore(a.everything(), "created"));
        impactUpdated += Math.max(0, AorDialog.numberBefore(a.everything(), "updated"));
    }
    private boolean notJudged;
    private int impactCreated;
    private int impactUpdated;
    private String impactWhat = "employees";
    private com.aventstack.extentreports.ExtentTest tcSummary;

    private String notRunReason() {
        return tcSelected ? "Not selected for this run (-Daor.edge.only): the scenario is part of the class and runs in a full run."
                : "Not run in this run: -Dcases does not list " + tc + ". The scenario runs in a full run of the suite.";
    }

    /** One scenario = one report entry. A finding is a red entry, the next scenario goes on. */
    private boolean step(String id, String title, String expected, TestReporter.PhaseAction body) {
        return scenario(id, title, expected, (r, chosen) -> r.phase(idSlug(tc) + "_" + idSlug(id), id + " | " + title, expected, chosen && blockReason == null ? body : () -> {
            throw new SkipException(chosen ? "Not run: " + blockReason : notRunReason());
        }));
    }

    /** A scenario that is one table of values (the report shows which values are wrong and which are right). */
    private boolean stepChecks(String id, String title, String expected, TestReporter.CheckAction body) {
        return scenario(id, title, expected, (r, chosen) -> r.phaseChecks(idSlug(tc) + "_" + idSlug(id), id + " | " + title, expected, chosen && blockReason == null ? body : c -> {
            throw new SkipException(chosen ? "Not run: " + blockReason : notRunReason());
        }));
    }

    private boolean scenario(String id, String title, String expected, ScenarioRunner runner) {
        if (planning) {
            plannedLabels.add(tc + " | " + id + " | " + title);
            return true;
        }
        boolean chosen = selected(id);
        if (chosen) {
            ran++;
        }
        currentId = id;
        String label = tc + " | " + id;
        scenarioStarted(label);
        notJudged = false;
        impactCreated = 0;
        impactUpdated = 0;
        impactWhat = tc.endsWith("02") ? "payroll entries" : "employees";
        TestReporter r = TestReporter.startScenario(tc, id, tagsFor(tc, id), title, "What is checked: " + title, "Input of this scenario: " + title + ". The uploaded workbook, if the scenario uploads one, is linked in the evidence block at the end of this entry.",
                expected, driver, holder(), this::headline);
        r.captureFullApi();
        rep = r;
        Throwable error = null;
        SkipException skip = null;
        try {
            runner.run(r, chosen);
        } catch (SkipException e) {
            skip = e;
        } catch (Throwable t) {
            error = t;
            failures.add(id + ": " + String.valueOf(t.getMessage()).split("\\R")[0]);
        }
        ScenarioBoard.Verdict v;
        ScenarioBoard.Kind kind = ScenarioBoard.Kind.NONE;
        List<String> refs = List.of();
        String reason;
        int found = r.defects().size();
        if (skip != null) {
            String msg = String.valueOf(skip.getMessage());
            v = msg.contains("network connection") || msg.contains("limit") || msg.contains("no base employees") || msg.contains("not onboarded") || msg.startsWith("Not run: ")
                    ? ScenarioBoard.Verdict.BLOCKED : ScenarioBoard.Verdict.NOT_RUN;
            reason = msg;
        } else if (error != null) {
            v = ScenarioBoard.Verdict.NOT_JUDGED;
            kind = ScenarioBoard.Kind.AUTOMATION_FAILURE;
            reason = "The test could not carry this scenario out (an automation failure, not an application defect): " + PlainText.oneLine(PlainText.assertion(String.valueOf(error.getMessage())), 300);
        } else if (found > 0) {
            v = ScenarioBoard.Verdict.FAIL;
            kind = ScenarioBoard.Kind.APPLICATION_DEFECT;
            refs = refsFor(tc, id);
            reason = found + (found == 1 ? " problem" : " problems") + " found in the application (see the steps below).";
        } else if (notJudged) {
            v = ScenarioBoard.Verdict.NOT_JUDGED;
            reason = "The application stopped at an earlier problem of every row, so the values of this scenario were not judged.";
        } else {
            v = ScenarioBoard.Verdict.PASS;
            reason = "The expected behaviour was observed.";
        }
        r.impact(impactCreated == 0 && impactUpdated == 0 ? "No " + impactWhat + " were created or updated by this scenario." : impactWhat.substring(0, 1).toUpperCase() + impactWhat.substring(1)
                + " created: " + impactCreated + ", updated: " + impactUpdated + " (as answered by the application)" + (impactCreated > 0 && impactWhat.equals("employees") ? "; edge employees now registered: " + budget : "") + ".");
        r.finishScenario(v, kind, refs, reason, error != null ? error : skip);
        scenarioFinished(label, v == ScenarioBoard.Verdict.PASS ? "p" : v == ScenarioBoard.Verdict.FAIL ? "f" : "s");
        return v == ScenarioBoard.Verdict.PASS;
    }

    /** A test case: its summary entry (created first so that it stands before its scenarios), its scenarios, and the check that no scenario found a defect. */
    private void runCase(String id, String title, Runnable prepare, Runnable define) {
        tc = id;
        tcSelected = isSelected(id);
        blockReason = null;
        tcSummary = ExtentManager.get().createTest("SUMMARY | " + id + " | " + title).assignCategory(ExecutionInfo.module(), id, "Summary");
        if (tcSelected) {
            prepare.run();
        }
        define.run();
        List<ScenarioBoard.Outcome> mine = ScenarioBoard.outcomes(id);
        boolean red = mine.stream().anyMatch(o -> o.verdict == ScenarioBoard.Verdict.FAIL);
        tcSummary.log(red ? com.aventstack.extentreports.Status.FAIL : com.aventstack.extentreports.Status.PASS, ScenarioBoard.summaryHtml(id, id + " - " + title));
        Log.info("AOR edge cases " + id + ": " + budget + ", payroll sessions " + sessions + " of " + sessionLimit + ", scenarios run " + ran);
        long failed = mine.stream().filter(o -> o.verdict == ScenarioBoard.Verdict.FAIL).count();
        org.testng.Assert.assertEquals(failed, 0L, failed + " scenario(s) of " + id + " found problems in the application (see the scenario entries of the report)");
    }

    @Override
    protected List<String> plannedScenarios() {
        registerDefects();
        planning = true;
        plannedLabels.clear();
        try {
            tc = "TC-AOR-EDGE-01";
            defineEdge01();
            tc = "TC-AOR-EDGE-02";
            defineEdge02();
            return new ArrayList<>(plannedLabels);
        } catch (RuntimeException e) {
            Log.warn("The scenarios could not be listed in advance (the progress box counts test cases instead): " + e);
            return null;
        } finally {
            planning = false;
            tc = "";
        }
    }

    // ------------------------------------------------------------------ tags and defect links of the AOR edge scenarios

    private static List<String> tagsFor(String tc, String id) {
        if (tc.endsWith("01")) {
            switch (id) {
                case "S00": case "B00": return List.of("Set-up");
                case "W01": case "W02": case "W03": case "W04": case "W05": return List.of("Unusable Files");
                case "X00": return List.of("Diagnostics");
                case "U1": case "G6": return List.of("Required Values");
                case "G1": case "G2": case "G3": case "G4": case "G5": case "G7": case "G8": case "S11": return List.of("Wrong Formats");
                case "I00": return List.of("Data Integrity");
                case "I01": return List.of("Existing Employee Update", "Data Integrity");
                case "S01": case "S02": case "S03": case "S04": case "S05": case "S06": return List.of("Date Validation", "Business Rules");
                case "S07": case "S08": case "S09": case "S10": return List.of("Business Rules");
                case "S12": case "S13": case "S14": case "S15": return List.of("Duplicates");
                case "M01": return List.of("Multiple Rows");
                case "M02": case "U6": return List.of("Multiple Rows", "Duplicates");
                case "K01": case "K02": case "K03": return List.of("Column Validation");
                case "V01": case "V02": return List.of("Valid Edge Values");
                case "R00": return List.of("Retention");
                default: return List.of("Other");
            }
        }
        switch (id) {
            case "S00": case "B01": return List.of("Set-up");
            case "P01": case "P02": case "P03": case "P04": case "P05": return List.of("Payee Sheet Window Checks");
            case "W01": case "W02": case "W03": return List.of("Unusable Files");
            case "Q00": return List.of("Valid Baseline");
            case "R00": return List.of("Retention");
            default: return List.of("Payee Sheet Row Checks");
        }
    }

    /** The known application defects (one reference each); scenarios that show the same defect link to the same reference and are counted once as a unique defect. */
    private static void registerDefects() {
        ScenarioBoard.defect("AOR-D01", "High", "An existing employee is overwritten by a row that repeats its company employee id",
                "The upload answers \"0 created, 1 updated\" and the list entry of the existing employee (name, delivery partner id, state) is replaced.");
        ScenarioBoard.defect("AOR-D02", "High", "A second employee is created with an already used e-mail, contact number or bank account (Aadhaar and PAN also, seen earlier)", "Only a repeated delivery partner id is refused.");
        ScenarioBoard.defect("AOR-D03", "High", "The Payee Sheet accepts rows whose state, Wm name or hub is empty or wrong", "Only the FHRID is checked against the employee; payroll entries are created.");
        ScenarioBoard.defect("AOR-D04", "Medium", "Business rules are not enforced", "Under 18, joining / onboarding date in the future, hub name rule, state against hub, country other than India, unknown delivery status are accepted.");
        ScenarioBoard.defect("AOR-D05", "Medium", "Field formats are not validated", "Contact number, company employee id, FHRID format, names and free text, IFSC and account number; PAN and Aadhaar only partly.");
        ScenarioBoard.defect("AOR-D06", "Medium", "Required columns are not enforced", "Most columns may be empty, only spaces, or the word null (to be confirmed with the product owner).");
        ScenarioBoard.defect("AOR-D07", "Medium", "The Payee Sheet accepts a pay period in the future", "");
        ScenarioBoard.defect("AOR-D08", "Medium", "The Payee Sheet accepts a decimal payout (1234.567)", "");
        ScenarioBoard.defect("AOR-D09", "Low", "Messages show developer text instead of plain words", "For example \"Input should be a valid string\" and the PAN / Aadhaar verification error with an internal class name.");
        ScenarioBoard.defect("AOR-D10", "Low", "A missing or renamed column is reported as an empty value", "\"Name: Input should be a valid string\" instead of saying that the column is missing.");
    }

    private static List<String> refsFor(String tc, String id) {
        switch (id) {
            case "U1": return List.of("AOR-D06", "AOR-D09");
            case "G1": case "G2": case "G4": case "G5": case "G7": case "G8": case "S11": return List.of("AOR-D05");
            case "G3": return List.of("AOR-D05", "AOR-D09");
            case "G6": return List.of("AOR-D06");
            case "I01": return List.of("AOR-D01");
            case "S02": case "S03": case "S04": case "S05": case "S07": case "S08": case "S09": case "S10": return List.of("AOR-D04");
            case "S12": case "S13": case "S14": return List.of("AOR-D02");
            case "K01": case "K02": return List.of("AOR-D10", "AOR-D09");
            case "P05": return List.of("AOR-D07");
            case "Q11": return List.of("AOR-D08");
            case "Q03": case "Q04": case "Q05": case "Q06": case "Q13": case "Q14": return List.of("AOR-D03");
            default: return List.of();
        }
    }

    private String signIn() {
        login.freshLogin(ConfigManager.baseUrl());
        login.login(ConfigManager.aorEmail(), ConfigManager.aorPassword());
        org.testng.Assert.assertTrue(agents.sidebarVisible(), "After signing in, the start page of the AOR user did not open: the menu with \"Employees\" is not shown.");
        return "Signed in as " + SecretMasker.maskEmail(ConfigManager.aorEmail()) + ".";
    }

    private void needLimit(String id, int n) {
        if (!budget.reserve(n)) {
            skippedForLimit.add(id);
            throw new SkipException("Not run: the limit of " + budget.limit() + " employees is reached (" + budget + "), and this upload could create " + PlainText.count(n, "employee", "employees") + ".");
        }
    }

    private void book(AorEmployee e, Map<String, String> changes) {
        book(e, changes, "accepted");
    }

    private void book(AorEmployee e, Map<String, String> changes, String kind) {
        budget.created(1);
        onboarded.add(e);
        acceptedChanges.put(e, changes);
        AorEdgeRegistry.record(run == null ? "AOR-EDGE" : run.runId, kind, e);
    }

    // ---- rows with several wrong values

    private static final class Spec {
        final String title;
        final Function<AorEmployee, Map<String, String>> change;

        Spec(String title, Function<AorEmployee, Map<String, String>> change) {
            this.title = title;
            this.change = change;
        }
    }

    private static Spec spec(String title, Function<AorEmployee, Map<String, String>> change) {
        return new Spec(title, change);
    }

    private static String labelOf(String col) {
        String t = col.replace("aadhar", "aadhaar").replace("da status", "delivery status").replace("personal email id", "e-mail").replace("pan number", "PAN number");
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The answer of the application as row -> its problems ("Row - 2 : State: Input should be a valid string; Name: ... Row - 3 : ..."; one row can have several, separated by semicolons). */
    private static Map<Integer, List<String>> parseIssues(String text) {
        Map<Integer, List<String>> out = new LinkedHashMap<>();
        String t = text == null ? "" : text;
        int close = t.lastIndexOf(" Close");
        if (close > 0) {
            t = t.substring(0, close);
        }
        Matcher mt = ROW.matcher(t);
        List<int[]> marks = new ArrayList<>();
        while (mt.find()) {
            marks.add(new int[] { Integer.parseInt(mt.group(1)), mt.start(), mt.end() });
        }
        for (int i = 0; i < marks.size(); i++) {
            int end = i + 1 < marks.size() ? marks.get(i + 1)[1] : t.length();
            String segment = t.substring(marks.get(i)[2], end).trim();
            List<String> list = out.computeIfAbsent(marks.get(i)[0], k -> new ArrayList<>());
            for (String piece : segment.split(";\\s+(?=[A-Za-z' ]{2,40}:)")) {
                if (!piece.isBlank()) {
                    list.add(piece.trim());
                }
            }
        }
        return out;
    }

    /** The words by which the application calls a column in its messages (it says "FHRID" for the delivery partner id, "Client '...' not found" for the client ...). */
    private static String alias(String column) {
        return switch (column) {
            case "delivery partner id" -> "delivery partner id|fhrid";
            case "company employee id" -> "company employee id|employee id|already assigned to employee";
            case "pan number" -> "pan number|\\bpan\\b";
            case "aadhar number" -> "aadhar|aadhaar";
            case "account number" -> "account number|bank account";
            case "ifsc code" -> "ifsc";
            case "personal email id" -> "personal email";
            case "client name" -> "client";
            case "project name" -> "project";
            default -> Pattern.quote(column.toLowerCase(Locale.ROOT));
        };
    }

    private static boolean named(List<String> rowIssues, String column) {
        if (rowIssues == null) {
            return false;
        }
        Pattern p = Pattern.compile(alias(column), Pattern.CASE_INSENSITIVE);
        return rowIssues.stream().anyMatch(t -> p.matcher(t).find());
    }

    private static String issueOf(List<String> rowIssues, String column) {
        Pattern p = Pattern.compile(alias(column), Pattern.CASE_INSENSITIVE);
        return rowIssues.stream().filter(t -> p.matcher(t).find()).findFirst().orElse("");
    }

    /** True when the application checked the single values of the row (it named a problem of a column other than the delivery partner id): then a value it did not name was accepted. */
    private static boolean valuesWereChecked(List<String> rowIssues) {
        if (rowIssues == null) {
            return false;
        }
        Pattern anchor = Pattern.compile(alias("delivery partner id"), Pattern.CASE_INSENSITIVE);
        return rowIssues.stream().anyMatch(t -> !anchor.matcher(t).find() && !t.toLowerCase(Locale.ROOT).contains("not found under"));
    }

    private void batch(String id, String title, List<Spec> specs) {
        stepChecks(id, title, "The application refuses every row, creates nobody, names each wrong column and explains it in plain words", c -> {
            AorDataGenerator.bookPairs = false; // rows that cannot become employees do not use up Aadhaar / PAN pairs
            AorRun r;
            try {
                r = AorDataGenerator.newRun(specs.size());
            } finally {
                AorDataGenerator.bookPairs = true;
            }
            List<Map<String, String>> overrides = new ArrayList<>();
            for (int i = 0; i < specs.size(); i++) {
                overrides.add(specs.get(i).change.apply(r.employees.get(i)));
            }
            boolean anchored = overrides.stream().allMatch(o -> o instanceof AnchoredMap || (o.containsKey("delivery partner id") && o.get("delivery partner id") == null));
            if (!anchored) {
                needLimit(id, specs.size()); // a row without the empty Delivery Partner Id could be accepted
            }
            AorWorkbooks.writeEmployeeWorkbookWith(r, bulkFile(), overrides);
            AorDialog.Answer a = uploadBulk(bulkFile());
            String all = a.everything();
            rep.note("What the application answered: " + a.describe());
            Map<Integer, List<String>> issues = parseIssues(all);
            int created = Math.max(0, AorDialog.numberBefore(all, "created", "added", "inserted", "onboarded"));
            int judged = 0;
            int notReached = 0;
            for (int i = 0; i < specs.size(); i++) {
                int row = i + 2;
                List<String> rowIssues = issues.get(row);
                String where = "Row " + row + " - " + specs.get(i).title;
                if (rowIssues == null && created > 0) {
                    created--;
                    book(r.employees.get(i), overrides.get(i));
                    c.check(where, "The row is refused", false, "the row is refused and creates nobody", "An employee was created (" + budget + ")");
                }
                boolean checked = valuesWereChecked(rowIssues);
                if (rowIssues != null) {
                    boolean technical = rowIssues.stream().anyMatch(t -> TECHNICAL.matcher(t).find());
                    c.check(where, "The messages are written in plain words", !technical, "a message that a user can understand",
                            technical ? "developer text such as \"" + shorten(rowIssues.stream().filter(t -> TECHNICAL.matcher(t).find()).findFirst().orElse("")) + "\"" : "plain words");
                }
                for (Map.Entry<String, String> col : overrides.get(i).entrySet()) {
                    String key = col.getKey();
                    if (overrides.get(i) instanceof AnchoredMap && key.equals(((AnchoredMap) overrides.get(i)).anchorKey)) {
                        continue; // the anchor itself is not a case of this row
                    }
                    boolean named = named(rowIssues, key);
                    boolean empty = col.getValue() == null;
                    boolean optional = OPTIONAL.contains(key);
                    String value = empty ? "left empty" : "\"" + shorten(col.getValue()) + "\"";
                    boolean ok;
                    String shown;
                    if (named) {
                        judged++;
                        ok = true;
                        shown = "reported: \"" + shorten(issueOf(rowIssues, key)) + "\"";
                    } else if (optional) {
                        judged++;
                        ok = true;
                        shown = "accepted (this column is optional)";
                    } else if (empty) {
                        judged++;
                        ok = true;
                        shown = "accepted empty - is this column optional? (to be confirmed with the product owner)";
                    } else if (checked) {
                        judged++;
                        ok = false;
                        shown = "NOT reported: the application checked the other values of this row and accepted this one";
                    } else {
                        notReached++;
                        ok = true;
                        shown = "not judged: the application stopped at another problem of this row before it checked this value";
                    }
                    c.check(where, labelOf(key) + " " + value, ok, "the application names \"" + labelOf(key) + "\" and refuses the row", shown);
                }
            }
            if (judged == 0 && notReached > 0) {
                notJudged = true; // the application stopped at an earlier problem of every row: nothing was judged
            }
        });
    }

    private static String shorten(String v) {
        return v.length() > 40 ? v.substring(0, 40) + "... (" + v.length() + " characters)" : v;
    }

    private void fileScenario(String id, String title, java.util.concurrent.Callable<Path> writer) {
        step(id, title, "The application should refuse the file, create nobody and say that the file cannot be used", () -> {
            Path file = writer.call();
            return judgeNoEmployee(uploadBulk(file));
        });
    }

    // ---- judging what the application answered

    /** A file / window that should create no employee. */
    private String judgeNoEmployee(AorDialog.Answer a) {
        String all = a.everything();
        int created = AorDialog.numberBefore(all, "created", "added", "inserted", "onboarded");
        if (created > 0) {
            budget.created(created);
            rep.defect("The application accepted a file that it should have refused", "Nobody is created", "Employees were created (" + budget + "). " + a.describe());
        } else if (!AorDialog.tellsOfProblem(all) && all.length() < 25) {
            rep.defect("The application did not say why nothing happened", "A message that says what is wrong", a.describe());
        } else if (TECHNICAL.matcher(all).find()) {
            rep.defect("The message shows technical text instead of a plain explanation", "A message a user can understand", all);
        }
        return a.describe();
    }

    // ---- bulk upload window

    private AorDialog.Answer uploadBulk(Path file) {
        openAgentsSafely();
        agents.openBulkUpload();
        agents.attach(file);
        rep.evidence("bulk_" + (++shots) + "_chosen", "The upload window with the file \"" + file.getFileName() + "\"");
        rep.addFile("Uploaded workbook", "uploads/" + file.getFileName());
        AorDialog.Answer a = agents.clickUploadAndWait(300);
        countImpact(a);
        rep.evidence("bulk_" + shots + "_answer", "What the application answered");
        agents.dialog().dismiss();
        return a;
    }

    /** Closes any window that is still open (also with Escape) and opens the page; when the menu is still covered the page is loaded again. */
    private void openPayrollSafely() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                payroll.dialog().dismiss();
                new org.openqa.selenium.interactions.Actions(driver).sendKeys(org.openqa.selenium.Keys.ESCAPE).perform();
                payroll.open();
                return;
            } catch (RuntimeException e) {
                driver.navigate().refresh();
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        payroll.open();
    }

    private void openAgentsSafely() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                agents.dialog().dismiss();
                new org.openqa.selenium.interactions.Actions(driver).sendKeys(org.openqa.selenium.Keys.ESCAPE).perform();
                agents.open();
                return;
            } catch (RuntimeException e) {
                driver.navigate().refresh();
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        agents.open();
    }

    private AorDialog.Answer uploadBulkWithoutFile() {
        agents.dialog().dismiss();
        agents.open();
        agents.openBulkUpload();
        rep.evidence("bulk_" + (++shots) + "_no_file", "The upload window without a chosen file");
        AorDialog.Answer a;
        try {
            a = agents.clickUploadAndWait(20);
        } catch (RuntimeException e) {
            a = new AorDialog.Answer();
            a.windowText = "The Upload button cannot be clicked without a file (the application refuses it by keeping the button off).";
        }
        agents.dialog().dismiss();
        return a;
    }

    // ---- Payee Sheet window

    private void payeeFile(String id, String title, java.util.concurrent.Callable<Path> writer) {
        step(id, title, "The application should refuse the file, create no payroll entry and say that the file cannot be used", () -> {
            Path file = writer.call();
            LocalDate d = LocalDate.now();
            return judgePayee(uploadPayee(file, "Bleach", "The Substitute Shinigami", d, d));
        });
    }

    /** One payout row of the first base employee with one value changed. */
    private void payeeRow(String id, String title, Map<String, String> change) {
        step(id, title, "The application should refuse the row, create no payroll entry and say what is wrong", () -> {
            if (sessions >= sessionLimit) {
                throw new SkipException("Not run: the limit of " + sessionLimit + " payroll sessions is reached.");
            }
            AorWorkbooks.writePayeeWorkbookWith(view(run, 1), payFile(), List.of(change), List.of());
            LocalDate d = LocalDate.now();
            return judgePayee(uploadPayee(payFile(), "Bleach", "The Substitute Shinigami", d, d));
        });
    }

    /** The Payee Sheet window: nothing is chosen when the value is null. */
    private AorDialog.Answer uploadPayee(Path file, String client, String project, LocalDate start, LocalDate end) {
        try {
            return uploadPayeeOnce(file, client, project, start, end);
        } catch (org.openqa.selenium.WebDriverException first) {
            driver.navigate().refresh(); // a slow page or a window that stayed open: once more from a clean page
            return uploadPayeeOnce(file, client, project, start, end);
        }
    }

    private AorDialog.Answer uploadPayeeOnce(Path file, String client, String project, LocalDate start, LocalDate end) {
        openPayrollSafely();
        AorDialog d = payroll.openAction("Upload Pay Sheet", "Upload Pay Sheet");
        if (client != null) {
            d.choose("Client", client);
        }
        if (project != null) {
            d.choose("Project", project);
        }
        if (start != null) {
            d.setDate("Period Start", start);
        }
        if (end != null) {
            d.setDate("Period End", end);
        }
        if (file != null) {
            d.attach(file);
        }
        rep.evidence("pay_" + (++shots) + "_filled", "The Upload Pay Sheet window" + (file == null ? " without a file" : " with the file \"" + file.getFileName() + "\""));
        if (file != null) {
            rep.addFile("Uploaded workbook", "uploads/" + file.getFileName());
        }
        AorDialog.Answer a;
        try {
            a = d.clickAndWait("Upload", file == null || client == null || project == null ? 25 : 300); // an incomplete window is answered at once
        } catch (RuntimeException e) {
            a = new AorDialog.Answer();
            a.windowText = "The Upload button cannot be clicked with these choices (the application refuses it by keeping the button off).";
        }
        countImpact(a);
        rep.evidence("pay_" + shots + "_answer", "What the application answered");
        d.dismiss();
        return a;
    }

    /** The verdict of a Payee Sheet that should have been refused. */
    private String judgePayee(AorDialog.Answer a) {
        String all = a.everything();
        int created = AorDialog.numberBefore(all, "created");
        int updated = AorDialog.numberBefore(all, "updated");
        if (created > 0 || updated > 0) {
            sessions++;
            rep.defect("The application accepted a Payee Sheet that it should have refused", "The sheet is refused and no payroll entry is created",
                    "Entries were created (payroll sessions created in this run: " + sessions + "). " + a.describe());
        } else if (!AorDialog.tellsOfProblem(all) && all.length() < 25) {
            rep.defect("The application did not say why nothing was created", "A message that says what is wrong", a.describe());
        } else if (TECHNICAL.matcher(all).find()) {
            rep.defect("The message shows technical text instead of a plain explanation", "A message a user can understand", all);
        }
        return a.describe();
    }

    // ---- the data of the run

    /** The first {@code n} employees of a run (the Payee Sheet writer takes its rows from the employees of the run). */
    private static AorRun view(AorRun r, int n) {
        AorRun v = new AorRun();
        v.runId = r.runId;
        v.runDate = r.runDate;
        v.client = r.client;
        v.project = r.project;
        v.areaManagerName = r.areaManagerName;
        v.supervisorName = r.supervisorName;
        v.employees = new ArrayList<>(r.employees.subList(0, Math.min(n, r.employees.size())));
        return v;
    }

    /** Everybody who is really onboarded: the base employees and whoever the application wrongly accepted. */
    private AorRun keepRun() {
        AorRun keep = view(run, 0);
        keep.employees = new ArrayList<>(new LinkedHashSet<>(onboarded));
        return keep;
    }

    /**
     * The edge workbook keeps the employees that are onboarded, as their rows were really uploaded (also wrong values that the application accepted): the rows already in the file
     * plus the rows of this run's uploads for the employees created in this run. Nothing is rebuilt from objects, and nothing replaces the file before the new one is complete.
     */
    private void retainBulk() {
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        try {
            for (Map<String, String> r : AorWorkbooks.readRows(bulkEdge())) {
                if (!r.getOrDefault("name", "").isBlank() && !r.getOrDefault("delivery partner id", "").isBlank()) {
                    rows.put(r.get("delivery partner id"), r);
                }
            }
        } catch (RuntimeException e) {
            Log.warn("The edge workbook could not be read; only the rows of this run are kept: " + e.getMessage());
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (AorEmployee e : onboarded) {
            wanted.add(e.deliveryPartnerId);
        }
        try {
            Path dir = ExecutionInfo.root().resolve("uploads");
            java.io.File[] files = dir.toFile().listFiles((d, n) -> n.endsWith(".xlsx"));
            if (files != null) {
                java.util.Arrays.sort(files, java.util.Comparator.comparingLong(java.io.File::lastModified)); // oldest first: the first upload of an id is the employee's own row
                for (java.io.File f : files) {
                    try {
                        for (Map<String, String> r : AorWorkbooks.readRows(f.toPath())) {
                            String dp = r.getOrDefault("delivery partner id", "");
                            if (wanted.contains(dp) && !rows.containsKey(dp) && !r.getOrDefault("name", "").isBlank()) {
                                rows.put(dp, r);
                            }
                        }
                    } catch (RuntimeException notAWorkbook) {
                        // the plain-text and empty test files
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.warn("The uploaded workbooks of this run could not be read: " + e.getMessage());
        }
        AorWorkbooks.writeRawRows(bulkEdge(), "Bleach", new ArrayList<>(rows.values()));
        saveRun();
    }

    /** The payroll edge workbook is the valid Payee Sheet that was really uploaded in this run (Q00), copied as it is. */
    private boolean retainPayee() throws IOException {
        Path q00 = ExecutionInfo.root().resolve("uploads").resolve(idSlug("TC-AOR-EDGE-02") + "_q00.xlsx");
        if (!Files.isRegularFile(q00)) {
            return false;
        }
        Files.copy(q00, payEdge(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /** -Daor.edge.run.data=<aor-edge-run-data.json of an earlier run>: TC-AOR-EDGE-02 on the employees of that run. */
    private void loadEarlierEdgeRun() throws IOException {
        String p = ConfigManager.get("aor.edge.run.data");
        if (run == null && p != null) {
            run = new ObjectMapper().readValue(Files.newInputStream(Paths.get(p)), AorRun.class);
            onboarded.addAll(run.employees);
        }
    }

    private void saveRun() {
        try {
            Path f = ExecutionInfo.root().resolve("aor-edge-run-data.json");
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(f.toFile(), keepRun());
        } catch (IOException | RuntimeException e) {
            Log.warn("The data of the edge run could not be saved: " + e.getMessage());
        }
    }
}
