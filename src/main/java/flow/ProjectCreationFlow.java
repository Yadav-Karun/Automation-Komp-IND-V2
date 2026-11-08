package flow;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.openqa.selenium.WebDriver;
import org.testng.Assert;
import org.testng.SkipException;

import model.ProjectData;
import pages.AddProjectPage;
import pages.LoginPage;
import pages.ProjectsPage;
import reporting.TestReporter;
import reporting.ValueChecks;
import utils.ConfigManager;
import utils.FormFeedback;
import utils.Log;
import utils.ProjectBudget;
import utils.ProjectDataGenerator;
import utils.SecretMasker;

/**
 * The project happy flow (Project Management), one method per report phase. The steps carry the ids of the test brief (1..7, AOR-1..AOR-9, EOR-1..EOR-13):
 * a step that fails is reported with its id, so "which step broke" is the first line of the failure.
 * The same methods serve AOR and EOR; only the branch-specific phase ({@link #fillAor}, {@link #fillEor}) and the step ids differ.
 */
public class ProjectCreationFlow {

    /** A step that can throw. */
    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private static final LocalDate END_OF_2024 = LocalDate.of(2024, 12, 31);

    private final WebDriver driver;
    private final ProjectData data;
    private final ProjectDataGenerator gen;
    private final LoginPage login;
    private final ProjectsPage projects;
    private final AddProjectPage form;
    /** The happy flow creates ONE project per type and run (user's rule): Create Project is never clicked a second time. */
    private final ProjectBudget budget;

    /** Project name -> code of the projects that were in the listing before this one was created. */
    private Map<String, String> existing = new LinkedHashMap<>();
    /** The listing row of the new project (header -> text), set by {@link #searchInListing()}. */
    private Map<String, String> listedRow;

    public ProjectCreationFlow(WebDriver driver, ProjectDataGenerator gen) {
        this.driver = driver;
        this.gen = gen;
        this.data = gen.data();
        login = new LoginPage(driver);
        projects = new ProjectsPage(driver);
        form = new AddProjectPage(driver);
        budget = new ProjectBudget(data.type + " happy flow", ProjectBudget.HAPPY_FLOW_LIMIT);
    }

    public ProjectData data() {
        return data;
    }

    /** "AOR-4" for AOR, "EOR-4" for EOR. */
    private String id(int n) {
        return data.type + "-" + n;
    }

    /** Runs one step; a failure is rethrown with the step id in front (the original exception stays as the cause, so its stack trace is in the report). */
    private static void step(String id, Step s) {
        try {
            s.run();
        } catch (SkipException e) {
            throw e;
        } catch (Exception | AssertionError e) {
            String why = String.valueOf(e.getMessage()).split("\\R")[0];
            throw new AssertionError("Step " + id + " failed: " + why, e);
        }
    }

    // ------------------------------------------------------------------ step 1

    /** Step 1 - sign in as the EOR user; the dashboard (sidebar with the Projects tab) is displayed. */
    public String signIn() {
        login.freshLogin(ConfigManager.baseUrl());
        login.login(ConfigManager.eorEmail(), ConfigManager.eorPassword());
        Assert.assertTrue(projects.menuVisible(), "Step 1 failed: the dashboard is not displayed after the login (sidebar tab 'Projects' is missing).");
        return "Login accepted for " + SecretMasker.maskEmail(ConfigManager.eorEmail()) + "; the dashboard is displayed (sidebar tab 'Projects' visible) at "
                + driver.getCurrentUrl();
    }

    // ------------------------------------------------------------------ steps 2 and 3

    /** Steps 2-3 - open the Project tab and the AOR or EOR listing; the names and codes already in the listing are read (a new project must not repeat one). */
    public String openProjectTab() {
        step("2", projects::openProjectTab);
        step("3", () -> projects.openListing(data.type));
        Assert.assertEquals(projects.headingText(data.type), data.type + " Projects", "Step 3 failed: the " + data.type + " listing is not shown.");
        existing = projects.allNamesAndCodes();
        Log.info("The " + data.type + " listing holds " + existing.size() + " project(s) before the test: " + existing.keySet());
        return "Project tab opened; the '" + data.type + " Projects' listing is shown (" + projects.listSummary() + "); " + existing.size() + " project(s) existed before";
    }

    // ------------------------------------------------------------------ step 4

    /** Step 4 - click Add Project: the form of the selected branch opens, with the fields of that branch only. */
    public void clickAddProject(ValueChecks c) {
        step("4", () -> {
            projects.clickAddProject();
            form.waitOpen();
        });
        c.check("Step 4", "Add Project form opens", form.isOpen(), "the Add Project form (URL ends with /add)", driver.getCurrentUrl());
        c.contains("Step 4", "The form is the " + data.type + " form", data.type + " project details", form.description());
        if (data.isAor()) {
            c.check("Step 4", "Field 'Reference ID Format' is there (AOR only)", form.hasField(AddProjectPage.REFERENCE_ID), "the field exists",
                    form.hasField(AddProjectPage.REFERENCE_ID) ? "the field exists" : "missing");
            c.check("Step 4", "No 'End Client Name' field (AOR rule)", !form.hasField(AddProjectPage.END_CLIENT), "the AOR form has no End Client Name",
                    form.hasField(AddProjectPage.END_CLIENT) ? "the field is there" : "no such field");
            boolean city = form.hasField(AddProjectPage.CITY) || form.labelCount("City") > 0;
            c.check("Step 4", "No 'City' field (AOR rule)", !city, "the AOR form has no City", city ? "a City field is there" : "no such field");
        } else {
            c.check("Step 4", "Field 'End Client Name' is there (EOR only)", form.hasField(AddProjectPage.END_CLIENT), "the field exists",
                    form.hasField(AddProjectPage.END_CLIENT) ? "the field exists" : "missing");
            boolean twoLocations = form.labelCount("Country") == 2 && form.labelCount("State") == 2;
            c.check("Step 4", "Project Location and End Client Location (Country + State each)", twoLocations, "2 x Country and 2 x State",
                    form.labelCount("Country") + " x Country, " + form.labelCount("State") + " x State");
            boolean cities = form.hasField(AddProjectPage.CITY) && form.hasField(AddProjectPage.END_CLIENT_CITY);
            c.check("Step 4", "City fields of both locations", cities, "a City field in each location", cities ? "both are there" : "missing");
            boolean ref = form.hasField(AddProjectPage.REFERENCE_ID) || form.labelCount("Reference ID") > 0;
            c.check("Step 4", "No 'Reference ID' field (EOR rule)", !ref, "the EOR form has no Reference ID", ref ? "a Reference ID field is there" : "no such field");
        }
    }

    // ------------------------------------------------------------------ steps 5 - 7

    /** Steps 5-7 - select an existing client, generate the project name from it, and the code from the name. */
    public void selectClientAndGenerateName(ValueChecks c) {
        List<String> options = form.clientOptions();
        c.check("Step 5", "Client Name dropdown lists existing clients", !options.isEmpty(), "at least one existing " + data.type + " client",
                options.isEmpty() ? "(no client)" : options.size() + " client(s): " + options);
        step("5", () -> {
            String client = gen.pickClient(options);
            form.selectClient(client);
            c.eq("Step 5", "Selected client is displayed", client, form.clientShown());
        });
        step("6", () -> {
            gen.generateNameAndCode(existing.keySet(), new HashSet<>(existing.values()));
            form.setText(AddProjectPage.NAME, data.projectName);
            c.eq("Step 6", "Project Name (client - topic, not in the listing yet)", data.projectName, form.textOf(AddProjectPage.NAME));
        });
        step("7", () -> {
            form.setText(AddProjectPage.CODE, data.projectCode);
            c.eq("Step 7", "Project Code (first letter of each word of the name, no number)", ProjectDataGenerator.codeOf(data.projectName),
                    form.textOf(AddProjectPage.CODE));
        });
    }

    // ------------------------------------------------------------------ AOR-1 .. AOR-6

    /** AOR-1..AOR-6 - Reference ID, start date before 2025, empty end date, country India, a state; no city. */
    public void fillAor(ValueChecks c) {
        step("AOR-1", () -> {
            String ref = gen.generateReferenceId();
            form.setText(AddProjectPage.REFERENCE_ID, ref);
            c.eq("AOR-1", "Reference ID (client prefix + -IND- + number)", ref, form.textOf(AddProjectPage.REFERENCE_ID));
            c.check("AOR-1", "Reference ID format <PREFIX>-IND-<digits>", Pattern.matches("[A-Z0-9]+-IND-\\d+", ref), "e.g. SDFC-IND-0001", ref);
        });
        startDate("AOR-2", c);
        endDateEmpty("AOR-3", c);
        step("AOR-4", () -> countryIndia("AOR-4", 1, c));
        step("AOR-5", () -> data.state = randomState("AOR-5", 1, false, c));
        step("AOR-6", () -> {
            boolean city = form.hasField(AddProjectPage.CITY) || form.labelCount("City") > 0;
            c.check("AOR-6", "No city is entered or selected (the AOR form has no City field)", !city, "no City field, no city value required",
                    city ? "a City field is on the form" : "no City field");
        });
    }

    // ------------------------------------------------------------------ EOR-1 .. EOR-10

    /** EOR-1..EOR-10 - end client, start date before 2025, empty end date, project location and end client location (India, state, matching city); no reference id. */
    public void fillEor(ValueChecks c) {
        step("EOR-1", () -> {
            String end = gen.generateEndClient();
            form.setText(AddProjectPage.END_CLIENT, end);
            c.eq("EOR-1", "End Client Name (a real company)", end, form.textOf(AddProjectPage.END_CLIENT));
        });
        startDate("EOR-2", c);
        endDateEmpty("EOR-3", c);
        step("EOR-4", () -> countryIndia("EOR-4", 1, c));
        step("EOR-5", () -> data.state = randomState("EOR-5", 1, true, c));
        step("EOR-6", () -> {
            data.city = ProjectDataGenerator.cityOf(data.state);
            form.setText(AddProjectPage.CITY, data.city);
            c.eq("EOR-6", "Project Location city (a city of " + data.state + ")", data.city, form.textOf(AddProjectPage.CITY));
        });
        step("EOR-7", () -> {
            countryIndia("EOR-7", 2, c);
            data.endClientCountry = "India";
        });
        step("EOR-8", () -> data.endClientState = randomState("EOR-8", 2, true, c));
        step("EOR-9", () -> {
            data.endClientCity = ProjectDataGenerator.cityOf(data.endClientState);
            form.setText(AddProjectPage.END_CLIENT_CITY, data.endClientCity);
            c.eq("EOR-9", "End Client Location city (a city of " + data.endClientState + ")", data.endClientCity, form.textOf(AddProjectPage.END_CLIENT_CITY));
        });
        step("EOR-10", () -> {
            boolean ref = form.hasField(AddProjectPage.REFERENCE_ID) || form.labelCount("Reference ID") > 0;
            c.check("EOR-10", "No Reference ID is entered (not part of the EOR form)", !ref, "no Reference ID field", ref ? "a Reference ID field is on the form" : "no such field");
        });
    }

    private void startDate(String stepId, ValueChecks c) {
        step(stepId, () -> {
            LocalDate d = gen.generateStartDate();
            form.setDate(AddProjectPage.START_DATE, d);
            c.eq(stepId, "Project Start Date (before 1 January 2025)", d.toString(), form.dateValue(AddProjectPage.START_DATE));
            c.check(stepId, "The date is before 1 January 2025", !d.isAfter(END_OF_2024), "a date up to 31 December 2024", d.toString());
        });
    }

    private void endDateEmpty(String stepId, ValueChecks c) {
        step(stepId, () -> c.eq(stepId, "Project End Date stays empty", "", form.dateValue(AddProjectPage.END_DATE)));
    }

    private void countryIndia(String stepId, int occurrence, ValueChecks c) {
        form.selectCountry(occurrence, "India");
        c.eq(stepId, (occurrence == 1 ? "Project Location" : "End Client Location") + " - Country", "India", form.countryShown(occurrence));
    }

    /** Opens the State dropdown of a location, picks a random state (EOR: one that has a known city), checks it is displayed, returns it. */
    private String randomState(String stepId, int occurrence, boolean needCity, ValueChecks c) throws Exception {
        List<String> options = form.stateOptions(occurrence);
        c.check(stepId, "The State dropdown lists Indian states", options.size() > 1, "several states after India was chosen", options.size() + " state(s)");
        String state = needCity ? gen.pickStateWithCity(options) : gen.pickState(options);
        form.selectState(occurrence, state);
        c.eq(stepId, (occurrence == 1 ? "Project Location" : "End Client Location") + " - State (random)", state, form.stateShown(occurrence));
        return state;
    }

    // ------------------------------------------------------------------ AOR-7 / EOR-11

    /** AOR-7 / EOR-11 - click Create Project: the application accepts the form (the listing opens or a confirmation is shown). */
    public String createProject() {
        String stepId = id(data.isAor() ? 7 : 11);
        budget.reserve(data.projectName);
        FormFeedback.Outcome outcome = form.clickCreate();
        if (!outcome.accepted) {
            throw new AssertionError("Step " + stepId + " failed: the project was not created. What the application showed: " + outcome.describe());
        }
        budget.created(data.projectName);
        // the toast library repeats its text (screen-reader copies): show every different message once, shortened
        java.util.Set<String> shown = new java.util.LinkedHashSet<>();
        for (String t : outcome.toasts) {
            String firstSentences = t.length() > 120 ? t.substring(0, 117) + "..." : t;
            shown.add(firstSentences);
        }
        return "Create Project clicked; the application accepted the project. Confirmation: "
                + (shown.isEmpty() ? "(no toast captured)" : String.join(" | ", shown)) + " | now at " + driver.getCurrentUrl();
    }

    // ------------------------------------------------------------------ AOR-8 / EOR-12

    /** AOR-8 / EOR-12 - back in the listing of the same type, search by the EXACT project name (not the client name): the project is listed. */
    public String searchInListing() {
        String stepId = id(data.isAor() ? 8 : 12);
        projects.openListing(data.type);
        listedRow = projects.findProject(data.projectName, 3);
        if (listedRow == null) {
            throw new AssertionError("Step " + stepId + " failed: the project '" + data.projectName + "' is not in the " + data.type
                    + " listing after searching for its exact name (" + projects.listSummary() + ").");
        }
        return "Searched the " + data.type + " listing for the exact project name '" + data.projectName + "': " + projects.listSummary() + " | row: "
                + String.join(" | ", listedRow.values());
    }

    // ------------------------------------------------------------------ AOR-9 / EOR-13

    /** AOR-9 / EOR-13 - the matching row shows the project and the status Active. Every value of the row is one line of the table. */
    public void verifyActive(ValueChecks c) {
        String stepId = id(data.isAor() ? 9 : 13);
        if (listedRow == null) {
            throw new AssertionError("Step " + stepId + " failed: the project was not found in the listing, so its status cannot be checked.");
        }
        c.check("Listing (" + data.type + ")", "Search result is on the screen", projects.searchApplied(), "the list is narrowed to the searched project",
                projects.listSummary());
        c.eq("Listing row", "Project Name (exact)", data.projectName, ProjectsPage.column(listedRow, "PROJECT NAME"));
        c.eq("Listing row", "Project Code", data.projectCode, ProjectsPage.column(listedRow, "PROJECT CODE"));
        c.eq("Listing row", "Client", data.clientName, ProjectsPage.column(listedRow, "CLIENT"));
        c.eq("Listing row", "Project Start Date", data.startDateInList(), ProjectsPage.column(listedRow, "START DATE"));
        if (!data.isAor()) {
            String where = ProjectsPage.column(listedRow, "WORK LOCATION");
            c.check("Listing row", "Work location (shown by the application, not judged)", !where.isEmpty(),
                    "a location (entered: " + data.city + ", " + data.state + ", " + data.country + ")", where.isEmpty() ? "(empty)" : where);
        }
        String status = ProjectsPage.column(listedRow, "STATUS");
        data.listedStatus = status;
        c.eq("Listing row", "Status", "Active", status);
    }

    // ------------------------------------------------------------------ report header

    /** The line under the case title: the type, the signed-in user (no password) and the data generated so far. */
    public static String headline(ProjectData d) {
        StringBuilder sb = new StringBuilder("Project type: ").append(d.type).append(" | signed in as ").append(SecretMasker.maskEmail(ConfigManager.eorEmail()));
        if (d.clientName == null) {
            return sb.append(" | test data is generated in step 5-7 (seed ").append(d.seed).append(")").toString();
        }
        sb.append(" | client: ").append(d.clientName).append(" | project name: ").append(d.projectName).append(" | project code: ").append(d.projectCode);
        if (d.referenceId != null) {
            sb.append(" | reference id: ").append(d.referenceId);
        }
        if (d.endClientName != null) {
            sb.append(" | end client: ").append(d.endClientName);
        }
        sb.append(" | start date: ").append(d.startDate).append(" | end date: (empty) | location: ").append(d.country);
        if (d.state != null) {
            sb.append(" / ").append(d.state);
        }
        if (d.city != null) {
            sb.append(" / ").append(d.city);
        }
        if (d.endClientState != null) {
            sb.append(" | end client location: ").append(d.endClientCountry).append(" / ").append(d.endClientState).append(" / ").append(d.endClientCity);
        }
        sb.append(" | seed ").append(d.seed);
        if (d.listedStatus != null) {
            sb.append(" | status in the listing: ").append(d.listedStatus);
        }
        return sb.toString();
    }
}
