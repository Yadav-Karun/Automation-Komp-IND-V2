package tests.project;

import org.testng.annotations.Test;

import base.BaseTest;
import flow.ProjectCreationFlow;
import model.ClientData;
import model.ProjectData;
import reporting.ExecutionInfo;
import reporting.TestReporter;
import utils.ConfigManager;
import utils.ProjectDataGenerator;

/**
 * PROJECT MANAGEMENT - HAPPY FLOW (regression, positive path only). The EOR user creates a project for an EXISTING client and checks that it is listed as Active.
 * Two separate test cases, one per project type (no random choice between them - each type is always tested):
 *
 *   TC-PRJ-01  AOR: Projects > AOR > Add Project (Reference ID, start date before 2025, India + a state, no city) -> found by its exact name -> Active
 *   TC-PRJ-02  EOR: Projects > EOR > Add Project (End Client Name, India + state + city for the project and for the end client, no Reference ID) -> found -> Active
 *
 * No client is created (the 2-client rule of the client module is not touched): the client is picked from the Client Name dropdown. Every run creates one new
 * project per case; its name is "&lt;client&gt; - &lt;topic&gt;" with the first topic that is not in the listing yet, so a re-run never repeats a name. The random
 * choices (client, date, state, city, end client) come from one seed that is printed in the report: -Dproject.seed=<n> repeats them,
 * -Dproject.client="Bleach" picks the client.
 *
 * Run:   mvn clean test -Dsuite=testng-project-happy-flow.xml                (both cases)
 *        mvn clean test -Dsuite=testng-project-happy-flow.xml -Dcases=TC-PRJ-02   (only the EOR case)
 * Out of scope here (they belong to the edge-case class): invalid input, mandatory fields, duplicates, editing / deleting projects.
 */
public class ProjectCreationHappyFlow extends BaseTest {

    private static final String T_AOR = "AOR project creation: a project for an existing client is created and listed as Active";
    private static final String T_EOR = "EOR project creation: a project for an existing client is created and listed as Active";

    // TC-PRJ-01 - AOR happy flow. 8 phases: sign in (step 1) -> Project tab + AOR listing (2-3) -> Add Project (4) -> client, project name, code (5-7) ->
    // AOR fields: Reference ID, start date before 2025, empty end date, India, a state, no city (AOR-1..6) -> Create Project (AOR-7) -> search the AOR listing by
    // the exact project name (AOR-8) -> status Active (AOR-9). Expected: the project is found in the AOR listing and its status is Active.
    @Test(priority = 1)
    public void tcPrj01_aorProject() {
        run("TC-PRJ-01", "AOR", T_AOR);
    }

    // TC-PRJ-02 - EOR happy flow. Same phases with the EOR fields: End Client Name (a real company), start date before 2025, empty end date, Project Location and
    // End Client Location (India, a random state and a city of that state each), no Reference ID (EOR-1..10) -> Create Project (EOR-11) -> search the EOR listing
    // by the exact project name (EOR-12) -> status Active (EOR-13).
    @Test(priority = 2)
    public void tcPrj02_eorProject() {
        run("TC-PRJ-02", "EOR", T_EOR);
    }

    private void run(String id, String type, String title) {
        onlyIfSelected(id);
        boolean aor = "AOR".equals(type);
        String pre = slug(id) + "_"; // screenshot file names: one set per case (both cases of the class write into the same folder)
        ProjectDataGenerator gen = new ProjectDataGenerator(type);
        ProjectData d = gen.data();
        ProjectCreationFlow flow = new ProjectCreationFlow(driver, gen);

        ClientData holder = new ClientData(); // the report's case header needs a client record; the real header line is ProjectCreationFlow.headline
        holder.legalName = "(selected in step 5)";
        holder.clientType = type;
        holder.email = ConfigManager.adminEmail();

        TestReporter rep = TestReporter.start(id, "Project happy flow " + type, title,
                "Positive regression test: the EOR user creates an " + type + " project for an existing client and finds it, Active, in the " + type + " listing",
                "An existing " + type + " client (random); generated project name and code; "
                        + (aor ? "Reference ID, start date before 1 Jan 2025, end date empty, India + a state, no city"
                                : "End Client Name, start date before 1 Jan 2025, end date empty, India + state + city for the project and for the end client, no Reference ID"),
                "The project is created, found by its exact name in the " + type + " listing, and its status is Active",
                driver, holder, () -> ProjectCreationFlow.headline(d));
        try {
            // Step 1 - the login page loads, the EOR user is accepted, the dashboard is displayed. (Screenshot 1: login and dashboard.)
            rep.phase(pre + "p1_login", "Step 1 | Sign in as the EOR user: the dashboard is displayed",
                    "Login succeeds and the dashboard is displayed", flow::signIn);
            // Steps 2-3 - sidebar tab Projects, then AOR or EOR: the listing of that type is displayed.
            rep.phase(pre + "p2_open_listing", "Steps 2-3 | Open the Project tab and the " + type + " listing",
                    "The Project module opens and the " + type + " listing is displayed", flow::openProjectTab);
            // Step 4 - Add Project: the form of the type opens and has the fields of that type only. (Screenshot 2: selected type and opened form.)
            rep.phaseChecks(pre + "p3_add_project_form", "Step 4 | Click Add Project: the " + type + " form opens",
                    "The " + type + " project creation form opens", flow::clickAddProject);
            // Steps 5-7 - an existing client is selected, the project name is built from it, the code from the name. (Screenshot 3.)
            rep.phaseChecks(pre + "p4_client_name_code", "Steps 5-7 | Select an existing client, generate the Project Name and the Project Code",
                    "The client is displayed; the generated name and code are entered", flow::selectClientAndGenerateName);
            // AOR-1..6 / EOR-1..10 - the branch-specific fields, one table row per step. (Screenshot 4: the completed form before Create Project.)
            rep.phaseChecks(pre + "p5_fill_form",
                    aor ? "Steps AOR-1 to AOR-6 | Fill the AOR fields" : "Steps EOR-1 to EOR-10 | Fill the EOR fields",
                    aor ? "Reference ID, start date, empty end date, India and a state are accepted; no city is needed"
                            : "End client, start date, empty end date and both locations (India, state, matching city) are accepted; no Reference ID is needed",
                    aor ? flow::fillAor : flow::fillEor);
            rep.note("Generated test data: " + ProjectCreationFlow.headline(d));
            // AOR-7 / EOR-11 - Create Project. (Screenshot 5: confirmation or the updated listing.)
            rep.phase(pre + "p6_create_project", "Step " + type + "-" + (aor ? 7 : 11) + " | Click Create Project",
                    "The project is created successfully", flow::createProject);
            // AOR-8 / EOR-12 - back in the listing of the type: search by the exact project name (not the client name).
            rep.phase(pre + "p7_search_listing", "Step " + type + "-" + (aor ? 8 : 12) + " | Search the " + type + " listing by the exact Project Name",
                    "The newly created project appears in the " + type + " listing", flow::searchInListing);
            // AOR-9 / EOR-13 - the matching row: exact name, code, client, start date and the status Active. (Screenshot 6: final search result.)
            rep.phaseChecks(pre + "p8_status_active", "Step " + type + "-" + (aor ? 9 : 13) + " | Verify the project status is Active",
                    "The project status is Active", flow::verifyActive);
        } finally {
            ProjectDataGenerator.save(d, ExecutionInfo.root().resolve("project-data-" + type + ".json"));
        }
        assertNoDefects(rep);
    }
}
