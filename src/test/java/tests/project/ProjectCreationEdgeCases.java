package tests.project;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.openqa.selenium.NoAlertPresentException;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import api.ApiCall;
import base.BaseTest;
import model.ClientData;
import pages.AddProjectPage;
import pages.AdminHomePage;
import pages.LoginPage;
import pages.ProjectsPage;
import reporting.ExecutionInfo;
import reporting.TestReporter;
import utils.ConfigManager;
import utils.FormFeedback.Outcome;
import utils.Log;
import utils.PageProbe;
import utils.PageProbe.Landing;
import utils.ProjectBudget;
import utils.SecretMasker;

/**
 * PROJECT MANAGEMENT - EDGE CASES (negative, boundary and behaviour scenarios of project creation and of the project listings).
 * The user's rules: ALL scenarios of one project type are steps of ONE test case, and each case may create AT MOST 5 projects (5 AOR + 5 EOR per run):
 *
 *   TC-PRJ-03  AOR - every scenario of the AOR "Add Project" form and of the AOR listing
 *   TC-PRJ-04  EOR - the same for EOR (End Client Name, Project Location + End Client Location with a city, no Reference ID)
 *
 * How the scenarios fit into 5 projects ({@link ProjectBudget}: Create Project is never clicked when the 5 are used; the step is then SKIPPED):
 *  - Input the application REFUSES creates nothing, so every refusal scenario is its own step: empty / blank required fields, one character over the maximum
 *    length (name 200, code 50, reference id 50), end date before or equal to the start date, an existing code (V, F, T, D groups).
 *  - Everything that is (or may be) ACCEPTED is folded into 5 planned projects, each checking several things at once:
 *      P01  the baseline: valid data, start date today (AOR: a country without a state), created with a DOUBLE click -> exactly one project
 *      P02  maximum lengths + special characters: name 200 / code 50 / reference id 50 characters with HTML, script, Unicode, emoji, quotes, % _ &amp; # \,
 *           start on a future leap day, far-future end date, India + the first state of the list (EOR: script in the end client and the city)
 *      P03  minimum values: a 1-character name with spaces around it, a 1-character lower-case code (and reference id without the -IND- pattern),
 *           start date 1 Jan 1900, no end date, India without a state (EOR: no end client, both locations India without a state / city)
 *      P04  the same name as P01 for ANOTHER client, start in the past; AOR: end date in the past (must not be Active), EOR: end date one year from today
 *           (EOR refuses a past end date, that is its own refusal step T03); India + Tamil Nadu
 *      P05  duplicates of P01 that must be REFUSED (name in UPPER CASE + a space, code in lower case, the same reference id; EOR: a digits-only city and a
 *           city that is not in its state) - it only becomes a project when the application wrongly accepts it
 *  - Not possible within 5 projects and therefore NOT in this class: a blank (spaces-only) reference id, over-long end client names / cities, and testing
 *    the accepted variants one by one (they are combined in P02 - P05 instead).
 *  - The location is ALWAYS India (user's rule: never the United States or Brazil); a scenario that would need another country is not in this class.
 *  - Everything else creates nothing: the form (asterisks, State disabled until a country, India offered, every Indian state selectable, Cancel, F5, Back,
 *    reopened form), what the listing really holds (Z), search (Q), tabs and client filter (K), page size / pages / sort (G), access without a session (U).
 *
 * A scenario never stops the case: a refused input without a message, an accepted invalid input, raw database text, an HTTP 5xx answer or a script that ran is
 * a DEFECT of that step (red, with its screenshot) and the next scenario goes on. The case FAILS when at least one scenario found a defect.
 *
 * Run:  mvn clean test -Dsuite=testng-project-edge-cases.xml                      (both cases)
 *       mvn clean test -Dsuite=testng-project-edge-cases.xml -Dcases=TC-PRJ-03    (only AOR)
 *       mvn clean test -Dsuite=testng-project-edge-cases.xml -Dcases=AOR-Q01,AOR-K04   (only these scenarios; S00 and P01 always run)
 *       Re-check WITHOUT creating anything (the listing scenarios of an earlier run):
 *       -Dcases=AOR-Q01,... -Dproject.base.name="Edge AOR 081430 01" -Dproject.base.code=EA08143001 -Dproject.base.client="AOR Test Client"
 *       -Dproject.client="Bleach" picks the client, -Dproject.seed=<n> fixes the random client choice.
 */
public class ProjectCreationEdgeCases extends BaseTest {

    /** What the listing must show for a project that was created. */
    private enum Expect { ACTIVE, NOT_ACTIVE, ANY }

    /** All values of one Add Project form; null = left untouched (empty). "#first" as a state = the first state of the dropdown. */
    private static final class V {
        String client;
        String name;
        String code;
        String ref;
        String endClient;
        String start;
        String end;
        String country;
        String state;
        String city;
        String endCountry;
        String endState;
        String endCity;
    }

    /** A project the application accepted: checked against the listing (Z01). */
    private static final class Created {
        final String sid;
        final V v;
        final Expect expect;

        Created(String sid, V v, Expect expect) {
            this.sid = sid;
            this.v = v;
            this.expect = expect;
        }
    }

    private static final DateTimeFormatter LIST_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final String DUPLICATE = "(?i)exist|already|duplicate|unique|taken|in use|same|more than one";
    /** Special characters of P02 (HTML, script, Unicode, an emoji, quotes, % _ & # and a backslash). */
    private static final String SPECIAL = "<b>Bold</b> <script>alert(1)</script> Projekt Übung 日本語 ★ 🚀 \"Quoted\" O'Neil 100% _ & # \\";

    // TC-PRJ-03 - AOR edge cases: every scenario of the AOR Add Project form and the AOR listing, at most 5 AOR projects (see the class comment).
    @Test(priority = 1)
    public void tcPrj03_aorEdgeCases() {
        runCase("TC-PRJ-03", "AOR");
    }

    // TC-PRJ-04 - EOR edge cases: the same groups for the EOR form (End Client Name, two locations with a city) and the EOR listing, at most 5 EOR projects.
    @Test(priority = 2)
    public void tcPrj04_eorEdgeCases() {
        runCase("TC-PRJ-04", "EOR");
    }

    private void runCase(String id, String type) {
        String filter = ConfigManager.get("cases");
        boolean selected = filter == null || Arrays.stream(filter.split(",")).map(String::trim)
                .anyMatch(s -> s.equalsIgnoreCase(id) || s.toUpperCase(Locale.ROOT).startsWith(type + "-"));
        if (!selected) {
            throw new SkipException("Not selected by -Dcases");
        }
        new Run(id, type).execute();
    }

    // =====================================================================================================================================================
    //   one case = one Run
    // =====================================================================================================================================================

    private final class Run {
        final String tc;
        final String type;
        final boolean aor;
        final ProjectsPage projects = new ProjectsPage(driver);
        final AddProjectPage form = new AddProjectPage(driver);
        final LoginPage login = new LoginPage(driver);
        final AdminHomePage home = new AdminHomePage(driver);
        final ProjectBudget budget;
        final String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("ddHHmm"));
        final long seed = ConfigManager.get("project.seed") != null ? Long.parseLong(ConfigManager.get("project.seed")) : System.nanoTime() & 0xFFFFFFFFL;

        TestReporter rep;
        final List<String> failures = new ArrayList<>();
        final List<Created> created = new ArrayList<>();
        final List<Object[]> rejected = new ArrayList<>();
        final List<String> leftovers = new ArrayList<>();
        List<Map<String, String>> fullList = new ArrayList<>();
        int seq;
        int ran;
        boolean signedIn;
        String client;
        List<String> clients = new ArrayList<>();
        V base;
        String alertText;

        Run(String tc, String type) {
            this.tc = tc;
            this.type = type;
            this.aor = "AOR".equals(type);
            this.budget = new ProjectBudget(type + " edge cases", ProjectBudget.EDGE_CASES_LIMIT);
        }

        // ------------------------------------------------------------------------------------------------------------------------------ the case

        void execute() {
            ClientData holder = new ClientData(); // the report's case header needs a client record; the real header line is headline()
            holder.legalName = "(chosen in S00)";
            holder.clientType = type;
            holder.email = ConfigManager.eorEmail();
            rep = TestReporter.start(tc, "Project edge cases " + type, type + " project creation: every negative, boundary and behaviour scenario of the "
                    + "Add Project form and of the " + type + " listing (at most " + budget.limit() + " projects)",
                    "Edge cases of the Project module for " + type + " with at most " + budget.limit() + " new projects: mandatory fields, lengths, special "
                            + "characters, duplicates, dates, locations, Cancel / double click / refresh / Back, what the listing really holds, search, filters, "
                            + "tabs, page size, sort, access without being signed in",
                    "Valid baseline data with ONE thing changed per refused scenario; the accepted variants combined in 5 planned projects (P01 - P05)",
                    "Invalid input is refused with a clear message and creates no project; valid edge input is accepted and listed exactly as entered; "
                            + "no HTTP 5xx, no raw database text, no script runs",
                    driver, holder, this::headline);
            try {
                setup();
                refusals();
                plannedProjects();
                formChecks();
                verifyListing();
                listingSearch();
                listingFilters();
                listingTable();
                access();
            } finally {
                String summary = "Scenarios run: " + ran + " | " + budget + " (limit " + budget.limit() + ") | refused attempts: " + rejected.size()
                        + " | projects accepted although they should have been refused: " + (leftovers.isEmpty() ? "none" : leftovers);
                rep.note(summary);
                Log.info(summary);
                try {
                    List<String> lines = new ArrayList<>();
                    created.forEach(c -> lines.add(c.sid + " | " + c.v.name + " | " + c.v.code + " | " + c.v.client));
                    leftovers.forEach(l -> lines.add("wrongly accepted | " + l));
                    Files.write(ExecutionInfo.root().resolve("created-projects-" + type + ".txt"), lines);
                } catch (java.io.IOException e) {
                    Log.warn("created-projects file not written: " + e.getMessage());
                }
            }
            if (!failures.isEmpty()) {
                Assert.fail(failures.size() + " scenario(s) could not be completed:\n - " + String.join("\n - ", failures));
            }
            assertNoDefects(rep);
        }

        String headline() {
            return "Project type: " + type + " | signed in as " + SecretMasker.maskEmail(ConfigManager.eorEmail()) + " | client used for the scenarios: "
                    + (client == null ? "(chosen in S00)" : client) + " | scenarios run: " + ran + " | " + budget + " (limit " + budget.limit() + ")"
                    + " | seed " + seed;
        }

        // ------------------------------------------------------------------------------------------------------------------------------ scenario plumbing

        /** One scenario = one report step. Returns true when it ended without a defect. A failing scenario is recorded and the next one goes on. */
        boolean scenario(String sid, String title, String expected, TestReporter.PhaseAction body) {
            String id = type + "-" + sid;
            if (!wanted(id)) {
                return true;
            }
            ran++;
            int before = rep.defects().size();
            try {
                rep.phase(slug(tc) + "_" + slug(id), id + " | " + title, expected, () -> {
                    heal();
                    return body.run();
                });
            } catch (SkipException e) {
                if (String.valueOf(e.getMessage()).contains("network connection")) {
                    throw e; // a lost network is not a scenario result: the case is reported as skipped
                }
                return false; // the scenario skipped itself (recorded as SKIP in the report)
            } catch (Throwable t) {
                failures.add(id + ": " + String.valueOf(t.getMessage()).split("\\R")[0]);
                return false;
            }
            return rep.defects().size() == before;
        }

        boolean scenarioChecks(String sid, String title, String expected, TestReporter.CheckAction body) {
            String id = type + "-" + sid;
            if (!wanted(id)) {
                return true;
            }
            ran++;
            int before = rep.defects().size();
            try {
                rep.phaseChecks(slug(tc) + "_" + slug(id), id + " | " + title, expected, c -> {
                    heal();
                    body.run(c);
                });
            } catch (SkipException e) {
                if (String.valueOf(e.getMessage()).contains("network connection")) {
                    throw e;
                }
                return false;
            } catch (Throwable t) {
                failures.add(id + ": " + String.valueOf(t.getMessage()).split("\\R")[0]);
                return false;
            }
            return rep.defects().size() == before;
        }

        /** -Dcases lists the case (everything runs) or this scenario. The set-up S00 and the baseline P01 always run (P01 creates nothing in re-check mode). */
        boolean wanted(String scenarioId) {
            String f = ConfigManager.get("cases");
            if (f == null || scenarioId.endsWith("-S00") || scenarioId.endsWith("-P01")) {
                return true;
            }
            return Arrays.stream(f.split(",")).map(String::trim).anyMatch(s -> s.equalsIgnoreCase(Run.this.tc) || s.equalsIgnoreCase(scenarioId));
        }

        /** Signs in again when the session is gone (the previous scenario may have ended on the login page). */
        void heal() {
            if (signedIn && (driver.getCurrentUrl().contains("/login") || !projects.menuVisibleNow())) {
                Log.warn("The session was gone before a " + type + " scenario: signing in again.");
                signInQuiet();
            }
        }

        void signInQuiet() {
            login.freshLogin(ConfigManager.baseUrl());
            login.login(ConfigManager.eorEmail(), ConfigManager.eorPassword());
            Assert.assertTrue(projects.menuVisible(), "The dashboard is not displayed after the login.");
            signedIn = true;
        }

        void needBase() {
            if (base == null) {
                throw new SkipException("The baseline project (P01) does not exist in this run, so there is nothing to compare with.");
            }
        }

        // ------------------------------------------------------------------------------------------------------------------------------ the form

        void openForm() {
            projects.openListing(type);
            projects.clickAddProject();
            form.waitOpen();
        }

        /** The listing rebuilt from scratch (open the form, Cancel): page 1, default page size, no filter, no search, no sort - whatever the last step did. */
        void freshListing() {
            openForm();
            form.clickCancel();
            projects.headingText(type);
            projects.listSummary();
        }

        /** A valid project with a name / code that nobody used: every refused scenario changes ONE thing of it. */
        V baseline() {
            V v = new V();
            int n = ++seq;
            String tag = String.format("%02d", n);
            v.client = client;
            v.name = "Edge " + type + " " + stamp + " " + tag;
            v.code = "E" + type.charAt(0) + stamp + tag;
            v.start = "2023-06-15";
            v.country = "India";
            v.state = "Karnataka";
            if (aor) {
                v.ref = "EDG-IND-" + stamp.substring(2) + tag;
            } else {
                v.endClient = "Microsoft";
                v.city = "Bengaluru";
                v.endCountry = "India";
                v.endState = "Maharashtra";
                v.endCity = "Mumbai";
            }
            return v;
        }

        void fill(V v) {
            if (aor) {
                client(v);
                text(AddProjectPage.NAME, v.name);
                text(AddProjectPage.CODE, v.code);
                text(AddProjectPage.REFERENCE_ID, v.ref);
            } else {
                text(AddProjectPage.NAME, v.name);
                text(AddProjectPage.CODE, v.code);
                client(v);
                text(AddProjectPage.END_CLIENT, v.endClient);
            }
            date(AddProjectPage.START_DATE, v.start);
            date(AddProjectPage.END_DATE, v.end);
            location(1, v.country, v.state, v.city, AddProjectPage.CITY);
            if (!aor) {
                location(2, v.endCountry, v.endState, v.endCity, AddProjectPage.END_CLIENT_CITY);
            }
        }

        private void client(V v) {
            if (v.client != null) {
                form.selectClient(v.client);
            }
        }

        private void text(String field, String value) {
            if (value != null && !value.isEmpty()) {
                form.setText(field, value);
            }
        }

        private void date(String field, String iso) {
            if (iso != null && !iso.isEmpty()) {
                form.setDate(field, LocalDate.parse(iso));
            }
        }

        private void location(int n, String country, String state, String city, String cityField) {
            if (country != null) {
                form.selectCountry(n, country);
                if (state != null) {
                    form.selectState(n, "#first".equals(state) ? form.stateOptions(n).get(0) : state);
                }
            }
            text(cityField, city);
        }

        /** Fills the form and clicks Create Project (twice in the same instant when asked) - only while the project budget allows one more project. */
        Outcome submit(V v, boolean doubleClick) {
            budget.reserve(v.name);
            openForm();
            fill(v);
            Outcome o = doubleClick ? form.doubleClickCreate() : form.clickCreate();
            if (o.accepted) {
                budget.created(v.name);
            }
            return o;
        }

        // ------------------------------------------------------------------------------------------------------------------------------ verdict helpers

        /** A server error during the scenario is a problem of the application, whatever the form showed. */
        void noServerError() {
            List<ApiCall> bad = rep.observeApi().stream().filter(ApiCall::isServerError).toList();
            if (!bad.isEmpty()) {
                rep.defect("The server answered with a server error", "No server error: a refused entry must be answered with a clear error message",
                        bad.toString());
            }
        }

        /** True (and the alert closed) when a JavaScript alert is open: text that was typed must never run as a script. */
        boolean scriptRan() {
            try {
                org.openqa.selenium.Alert a = driver.switchTo().alert();
                alertText = a.getText();
                a.accept();
                return true;
            } catch (NoAlertPresentException none) {
                return false;
            }
        }

        boolean mentions(Outcome o, String regex) {
            String all = String.join(" | ", o.messages) + " | " + String.join(" | ", o.toasts);
            return Pattern.compile(regex).matcher(all).find();
        }

        String toastText(Outcome o) {
            Set<String> shown = new LinkedHashSet<>();
            for (String t : o.toasts) {
                shown.add(t.length() > 100 ? t.substring(0, 97) + "..." : t);
            }
            return shown.isEmpty() ? "no toast" : String.join(" | ", shown);
        }

        /** The input must be refused: with a message that says what is wrong, without raw database text, without an HTTP 5xx, and without a project. */
        void reject(String sid, String title, String expected, String mustMention, Consumer<V> mod) {
            scenario(sid, title, expected, () -> {
                V v = baseline();
                mod.accept(v);
                Outcome o = submit(v, false);
                noServerError();
                if (o.accepted) {
                    leftovers.add(type + "-" + sid + ": " + abbreviate(v.name));
                    rep.defect("The application accepted an entry that it should have refused", expected, o.describe() + " | the project '" + v.name + "' now exists ("
                            + budget + ")");
                    return o.describe();
                }
                if (!o.hasFeedback()) {
                    rep.defect("The application refused the entry but did not say why: no message was shown to the user", "A clear message that tells the user what is wrong", o.describe());
                } else {
                    if (mustMention != null && !mentions(o, mustMention)) {
                        rep.defect("The message does not say what is wrong", "A message that mentions " + mustMention, o.describe());
                    }
                    rawErrorLeak(rep, o);
                }
                rejected.add(new Object[] { type + "-" + sid, v });
                return o.describe();
            });
        }

        /**
         * One of the planned projects P02 - P04: created and then checked in the listing (Z01). {@code mustCreate}: the values are valid, a refusal is a defect;
         * otherwise (unusual values) a refusal WITH a message that names the field is accepted as the application's rule. A script that runs is always a defect.
         */
        void planned(String sid, String title, String expected, Expect listed, boolean mustCreate, Consumer<V> mod) {
            scenario(sid, title, expected, () -> {
                V v = baseline();
                mod.accept(v);
                Outcome o = submit(v, false);
                noServerError();
                if (scriptRan()) {
                    rep.defect("The text that was typed made the page run a script (a security risk)", "Typed text is shown as plain text and never run", alertText);
                }
                if (o.accepted) {
                    created.add(new Created(type + "-" + sid, v, listed));
                    return "Created (" + budget + "): '" + abbreviate(v.name) + "' (" + toastText(o) + "); Z01 checks that the listing shows it exactly as typed";
                }
                if (!o.hasFeedback()) {
                    rep.defect("The application refused the entry but did not say why: no message was shown to the user", "A clear message that tells the user what is wrong", o.describe());
                } else {
                    rawErrorLeak(rep, o);
                    if (mustCreate) {
                        rep.defect("A valid entry was refused", expected, o.describe());
                    }
                }
                rejected.add(new Object[] { type + "-" + sid, v });
                return o.describe() + " No project was created.";
            });
        }

        void expect(boolean ok, String title, String expected, String actual) {
            if (!ok) {
                rep.defect(title, expected, actual);
            }
        }

        // ------------------------------------------------------------------------------------------------------------------------------ S00 + P01: set-up

        void setup() {
            boolean ok = scenario("S00", "Sign in as the EOR user, open the " + type + " listing, read the Client Name dropdown",
                    "Login succeeds, the listing opens, the dropdown lists at least one existing " + type + " client", () -> {
                        signInQuiet();
                        openForm();
                        clients = form.clientOptions();
                        Assert.assertFalse(clients.isEmpty(), "The Client Name dropdown has no client: there is no existing " + type + " client.");
                        String wanted = ConfigManager.get("project.client");
                        if (wanted != null) {
                            client = clients.stream().filter(c -> c.equalsIgnoreCase(wanted)).findFirst()
                                    .orElseThrow(() -> new AssertionError("-Dproject.client='" + wanted + "' is not in the dropdown: " + clients));
                        } else {
                            client = clients.get(new Random(seed).nextInt(clients.size()));
                        }
                        return "Signed in; clients in the dropdown: " + clients + "; the scenarios use '" + client + "'; at most " + budget.limit()
                                + " projects may be created";
                    });
            if (!ok || client == null) {
                throw new AssertionError("The set-up failed (sign-in or the client list): the scenarios cannot run. See the first step of the report.");
            }
            scenario("P01", "Project 1 of " + budget.limit() + " - the baseline: valid data, start date today" + (aor ? ", a country without a state" : "")
                    + ", Create Project DOUBLE-clicked",
                    "Exactly ONE project is created (the second click neither creates a second project nor causes a server error); the duplicate and listing "
                            + "scenarios use it", () -> {
                                if (ConfigManager.get("project.base.name") != null) {
                                    base = reusedBase();
                                    client = base.client;
                                    return "Re-check mode: the existing project '" + base.name + "' (" + base.code + ", " + base.client
                                            + ") is used; no project is created";
                                }
                                V v = baseline();
                                v.start = LocalDate.now().toString();
                                if (aor) {
                                    v.state = null;
                                }
                                Outcome o = submit(v, true);
                                noServerError();
                                if (!o.accepted) {
                                    throw new AssertionError("The baseline project was not created: " + o.describe());
                                }
                                base = v;
                                created.add(new Created(type + "-P01", v, Expect.ACTIVE));
                                int count = listedCount(v);
                                if (count > 1) {
                                    for (int i = 1; i < count; i++) {
                                        budget.created(v.name + " (copy " + i + ")");
                                    }
                                    leftovers.add(type + "-P01: " + (count - 1) + " extra copy/copies of " + v.name);
                                    rep.defect("The double click created " + count + " projects", "Exactly one project",
                                            count + " rows with the name, code and client of the baseline");
                                } else if (count == 0) {
                                    rep.defect("The baseline project is not in the listing after it was created", "One row", "no row");
                                }
                                return "Created with a double click (" + toastText(o) + "); rows with its name, code and client: " + count + " (" + budget + ")";
                            });
        }

        /** -Dproject.base.name / .code / .client (/ .ref / .start): an existing project used instead of creating P01 (re-check mode). */
        V reusedBase() {
            V v = new V();
            v.name = ConfigManager.get("project.base.name");
            v.code = ConfigManager.get("project.base.code");
            v.client = ConfigManager.get("project.base.client");
            v.ref = ConfigManager.get("project.base.ref");
            v.start = ConfigManager.get("project.base.start", LocalDate.now().toString());
            if (v.code == null || v.client == null) {
                throw new AssertionError("Re-check mode needs -Dproject.base.name, -Dproject.base.code and -Dproject.base.client (an existing " + type + " project).");
            }
            return v;
        }

        /** Rows of the listing with exactly this name, code (ignoring case) and client. */
        int listedCount(V v) {
            freshListing();
            projects.searchProject(v.name);
            return (int) projects.rows().stream().filter(r -> same(r, v)).count();
        }

        boolean same(Map<String, String> r, V v) {
            return norm(col(r, "PROJECT NAME")).equals(norm(v.name)) && col(r, "PROJECT CODE").equalsIgnoreCase(norm(v.code)) && col(r, "CLIENT").equals(v.client);
        }

        // ------------------------------------------------------------------------------------------------------------------------------ V, F, T, D: refused input

        void refusals() {
            scenario("V01", "All fields empty, Create Project clicked",
                    "Not submitted; a message names every required field (client, project name, project code" + (aor ? ", reference id" : "") + ", start date)", () -> {
                        budget.reserve("(empty form)");
                        openForm();
                        Outcome o = form.clickCreate();
                        noServerError();
                        if (o.accepted) {
                            budget.created("(empty form)");
                            leftovers.add(type + "-V01: (empty form)");
                            rep.defect("The application accepted an empty form", "Nothing is created", o.describe());
                            return o.describe();
                        }
                        if (!o.hasFeedback()) {
                            rep.defect("The empty form was refused but no message was shown", "A message for every required field", o.describe());
                        } else {
                            List<String[]> required = new ArrayList<>(List.of(new String[] { "Client", "(?i)client" }, new String[] { "Project name", "(?i)project name" },
                                    new String[] { "Project code", "(?i)project code" }, new String[] { "Project start date", "(?i)start" }));
                            if (aor) {
                                required.add(3, new String[] { "Reference ID", "(?i)reference" });
                            }
                            for (String[] r : required) {
                                if (!mentions(o, r[1])) {
                                    rep.defect("No message names the required field '" + r[0] + "'", "A message that names '" + r[0] + "'", o.describe());
                                }
                            }
                            rawErrorLeak(rep, o);
                        }
                        return o.describe();
                    });
            reject("V02", "Client not selected", "Refused with a message about the client", "(?i)client", v -> v.client = null);
            reject("V03", "Project name empty", "Refused with a message about the project name", "(?i)name", v -> v.name = null);
            reject("V04", "Project code empty", "Refused with a message about the project code", "(?i)code", v -> v.code = null);
            if (aor) {
                reject("V05", "Reference ID empty", "Refused with a message about the reference id", "(?i)reference", v -> v.ref = null);
            }
            reject("V06", "Project start date empty", "Refused with a message that names the start date", "(?i)start", v -> v.start = null);
            reject("V07", "Project name = 3 spaces", "Refused (a blank name is no name) with a message about the name", "(?i)name", v -> v.name = "   ");
            reject("V08", "Project code = 3 spaces", "Refused (a blank code is no code) with a message about the code", "(?i)code", v -> v.code = "   ");

            reject("F01", "Project name with 201 characters (the maximum is 200)", "Refused with a message about the length of the name (no HTTP 500)",
                    "(?i)name|200|character|long|max", v -> v.name = toLength(v.name + " ", 201, 'A'));
            reject("F02", "Project code with 51 characters (the maximum is 50)", "Refused with a message about the length of the code (no HTTP 500)",
                    "(?i)code|50|character|long|max", v -> v.code = toLength(v.code, 51, 'C'));
            if (aor) {
                reject("F03", "Reference ID with 51 characters (the maximum is 50)", "Refused with a message about the length of the reference id (no HTTP 500)",
                        "(?i)ref|50|character|long|max", v -> v.ref = toLength(v.ref, 51, 'R'));
            }

            reject("T01", "End date BEFORE the start date", "Refused with a message about the end date", "(?i)end|date|before|after|earlier|later|greater|less", v -> {
                v.start = "2023-06-15";
                v.end = "2023-06-14";
            });
            reject("T02", "End date = start date", "Refused: the application's rule is that the end date must be AFTER the start date", "(?i)end|date|after", v -> {
                v.start = "2023-06-15";
                v.end = "2023-06-15";
            });

            if (!aor) {
                reject("T03", "End date in the past (31 Dec 2022)", "Refused: the end date cannot be before today", "(?i)end|date|before|today", v -> {
                    v.start = "2022-01-10";
                    v.end = "2022-12-31";
                });
            }
            reject("D01", "The same project code as project 1 (different name)", "Refused with a message that says the code is already used", DUPLICATE, v -> {
                needBase();
                v.code = base.code;
            });
        }

        // ------------------------------------------------------------------------------------------------------------------------------ P02 - P05: the planned projects

        void plannedProjects() {
            String n = String.valueOf(budget.limit());
            planned("P02", "Project 2 of " + n + " - maximum lengths and special characters: name 200 characters (HTML, script, Unicode, emoji, quotes, % _ & # \\), "
                    + "code 50 characters (spaces, & / %)" + (aor ? ", reference id 50 characters (an <img onerror> tag)" : ", end client and city with a script tag")
                    + "; start 29 Feb 2096 (a future leap day), end 31 Dec 2099; India + the first state of the list",
                    "Created and listed exactly as typed, or refused with a message that names the field; no script runs, no HTTP 5xx", Expect.ANY, false, v -> {
                        v.name = toLength(SPECIAL + " " + stamp + " ", 200, 'A');
                        v.code = toLength("A&B/ " + stamp + seq + " 100% _#", 50, 'C');
                        v.start = "2096-02-29";
                        v.end = "2099-12-31";
                        v.country = "India";
                        v.state = "#first";
                        if (aor) {
                            v.ref = toLength("<img src=x onerror=alert(1)>" + stamp + seq, 50, 'R');
                        } else {
                            v.endClient = "<script>alert(2)</script> Étude & Co " + stamp;
                            v.city = "<script>alert(3)</script>";
                            v.endCountry = "India";
                            v.endState = "West Bengal";
                            v.endCity = "Kolkata";
                        }
                    });
            planned("P03", "Project 3 of " + n + " - minimum values: a 1-character name with spaces around it ('  Z  '), a 1-character lower-case code"
                    + (aor ? ", a 1-character lower-case reference id without the -IND- pattern" : ", no end client") + "; start 1 Jan 1900, no end date, "
                    + (aor ? "India without a state" : "both locations India without a state / city"),
                    "Created and listed (the name trimmed or as typed), or refused with a message that names the field", Expect.ANY, false, v -> {
                        v.name = "  Z  ";
                        v.code = freeSingleCode();
                        v.start = "1900-01-01";
                        v.country = "India";
                        v.state = null;
                        if (aor) {
                            v.ref = "r";
                        } else {
                            v.endClient = null;
                            v.city = null;
                            v.endCountry = "India";
                            v.endState = null;
                            v.endCity = null;
                        }
                    });
            planned("P04", "Project 4 of " + n + " - the SAME name as project 1 for ANOTHER client, start 10 Jan 2022, "
                    + (aor ? "end 31 Dec 2022 (in the past), India + Tamil Nadu" : "end one year from today, India + Tamil Nadu (city Chennai), end client location India "
                            + "without a state"),
                    aor ? "Created; listed with a status that is NOT Active (its end date is over; the Expired tab exists)" : "Created and listed as Active",
                    aor ? Expect.NOT_ACTIVE : Expect.ACTIVE, true, v -> {
                        needBase();
                        v.client = clients.stream().filter(c -> !c.equals(base.client)).findFirst().orElse(base.client);
                        v.name = base.name;
                        v.start = "2022-01-10";
                        v.end = aor ? "2022-12-31" : LocalDate.now().plusYears(1).toString(); // EOR refuses an end date before today
                        v.country = "India";
                        v.state = "Tamil Nadu";
                        if (!aor) {
                            v.endClient = "Amazon";
                            v.city = "Chennai";
                            v.endCountry = "India";
                            v.endState = null;
                            v.endCity = null;
                        }
                    });
            reject("P05", "Project 5 of " + n + " (only if the application wrongly accepts it) - duplicates of project 1: the same name in UPPER CASE with a space "
                    + "at the end, the same code in lower case" + (aor ? ", the same reference id" : "; plus a digits-only city (12345) and a city that is not in its "
                            + "state (Kerala + Mumbai)"),
                    "Refused with a message (the project / code already exists" + (aor ? ", the reference id is already used" : ", the city is not valid") + ")",
                    aor ? DUPLICATE : DUPLICATE + "|city", v -> {
                        needBase();
                        v.name = base.name.toUpperCase(Locale.ROOT) + " ";
                        v.code = base.code.toLowerCase(Locale.ROOT);
                        if (aor) {
                            v.ref = base.ref != null ? base.ref : v.ref;
                        } else {
                            v.city = "12345";
                            v.endState = "Kerala";
                            v.endCity = "Mumbai";
                        }
                    });
        }

        /** A 1-character lower-case code that no project of the listing uses (the code check is case-sensitive). */
        String freeSingleCode() {
            projects.openListing(type);
            Set<String> used = new HashSet<>(projects.allNamesAndCodes().values());
            for (char ch : "zyxwvutsrqponmlkjihgfedcba0123456789".toCharArray()) {
                if (!used.contains(String.valueOf(ch))) {
                    return String.valueOf(ch);
                }
            }
            throw new SkipException("Every 1-character code is used by an earlier run.");
        }

        // ------------------------------------------------------------------------------------------------------------------------------ the form without submitting

        void formChecks() {
            scenario("V10", "The required fields are marked with an asterisk, the optional ones are not",
                    "Client Name, Project Name, Project Code" + (aor ? ", Reference ID Format" : "") + " and Project Start Date are marked; the end date and the "
                            + "location fields are not",
                    () -> {
                        openForm();
                        List<String> required = new ArrayList<>(List.of("Client Name", "Project Name", "Project Code", "Project Start Date"));
                        if (aor) {
                            required.add("Reference ID Format");
                        }
                        List<String> wrong = new ArrayList<>();
                        for (String l : required) {
                            if (!form.isMarkedRequired(l)) {
                                wrong.add(l + " is not marked");
                            }
                        }
                        for (String l : new String[] { "Project End Date", "Country", "State" }) {
                            if (form.isMarkedRequired(l)) {
                                wrong.add(l + " is marked required");
                            }
                        }
                        expect(wrong.isEmpty(), "The asterisks do not match the required fields", "Only the required fields carry an asterisk", wrong.toString());
                        return wrong.isEmpty() ? "Asterisks are on exactly the required fields" : wrong.toString();
                    });
            scenario("L01", "The State dropdown is disabled until a country is chosen", "State is disabled and reads 'Select a country first'", () -> {
                openForm();
                boolean enabled = form.stateEnabled(1);
                String shown = form.stateShown(1);
                expect(!enabled, "The State dropdown is usable before a country is chosen", "Disabled until a country is chosen", "enabled, shows '" + shown + "'");
                return "State enabled: " + enabled + "; shows '" + shown + "'";
            });
            scenario("L02", "The Country dropdown offers India and India can be chosen",
                    "India is in the list; once chosen the dropdown shows it and State becomes usable", () -> {
                        openForm();
                        List<String> options = form.countryOptions(1);
                        form.selectCountry(1, "India");
                        boolean stateUsable = form.stateEnabled(1);
                        String shown = form.countryShown(1);
                        expect(options.contains("India") && "India".equals(shown) && stateUsable, "India cannot be chosen as the country",
                                "India offered, chosen, State usable", "options " + options + ", shown '" + shown + "', State usable: " + stateUsable);
                        return "Country options " + options + "; India chosen; State usable: " + stateUsable;
                    });
            scenario("L03", "Every Indian state of the dropdown can be selected and is shown", "Each state is displayed after it is chosen (nothing is submitted)", () -> {
                openForm();
                form.selectCountry(1, "India");
                List<String> states = form.stateOptions(1);
                List<String> bad = new ArrayList<>();
                for (String s : states) {
                    try {
                        form.selectState(1, s);
                    } catch (RuntimeException | AssertionError e) {
                        bad.add(s + " (" + String.valueOf(e.getMessage()).split("\\R")[0] + ")");
                    }
                }
                expect(states.size() >= 28 && bad.isEmpty(), "A state of the dropdown cannot be selected or the list is too short",
                        "At least 28 states, each selectable", states.size() + " states; problems: " + bad);
                return states.size() + " states offered; problems: " + (bad.isEmpty() ? "none" : bad);
            });
            scenario("B01", "Cancel after filling the form returns to the listing and creates nothing", "The " + type + " listing is shown; no project is created (Z02)",
                    () -> {
                        V v = baseline();
                        openForm();
                        fill(v);
                        form.clickCancel();
                        projects.headingText(type);
                        expect(!form.isOpen(), "Cancel did not leave the form", "The listing is shown", driver.getCurrentUrl());
                        rejected.add(new Object[] { type + "-B01 (Cancel)", v });
                        return "Back on " + driver.getCurrentUrl();
                    });
            scenario("B02", "F5 on the Add Project form keeps the user signed in on the form", "Still signed in; the form (or the listing) is shown, not the login page",
                    () -> {
                        openForm();
                        driver.navigate().refresh();
                        Landing l = PageProbe.settle(driver, "pressing F5 on the Add Project form", 10);
                        expect(!l.loginForm && !l.blank, "Pressing F5 on the form signs the user out or shows an empty page", "A readable page of the application", l.describe());
                        expect(l.path.startsWith("/projects"), "Pressing F5 on the form takes the user out of the Projects area", "The form or the listing again", "landed on " + l.path);
                        return l.describe();
                    });
            scenario("B03", "Browser Back from the Add Project form shows a readable page", "A page of the console (the listing), not the login page", () -> {
                openForm();
                driver.navigate().back();
                Landing l = PageProbe.settle(driver, "the browser's Back button on the Add Project form", 10);
                expect(!l.loginForm && !l.blank, "The browser's Back button on the form signs the user out or shows an empty page", "A readable page of the application", l.describe());
                return l.describe();
            });
            scenario("B04", "Reopening Add Project shows an empty form (no values of the last visit)", "The name field is empty", () -> {
                openForm();
                form.setText(AddProjectPage.NAME, "left over text " + stamp);
                openForm();
                String shown = form.textOf(AddProjectPage.NAME);
                expect(shown.isEmpty(), "The form keeps a value from the last visit", "An empty form", "Project Name = '" + shown + "'");
                return "Project Name on the reopened form: '" + shown + "'";
            });
        }

        // ------------------------------------------------------------------------------------------------------------------------------ Z: what the listing really holds

        /** Every row of the listing (all pages), read once after the projects were created; the listing scenarios compare with it. */
        List<Map<String, String>> full() {
            if (fullList.isEmpty()) {
                freshListing();
                fullList = projects.allRows();
            }
            return fullList;
        }

        void verifyListing() {
            scenarioChecks("Z01", "Every project created by this run is listed exactly as entered (name, code, client, start date) with the expected status",
                    "One row per project, equal to what was typed; status Active (not Active when the end date is over)", c -> {
                        fullList = new ArrayList<>();
                        List<Map<String, String>> rows = full();
                        c.check("Listing", "Projects in the " + type + " listing (all pages)", !rows.isEmpty(), "the projects of the listing", rows.size() + " row(s)");
                        c.check("Budget", "Projects created by this run", budget.used() <= budget.limit(), "at most " + budget.limit(), String.valueOf(budget.used()));
                        for (Created cr : created) {
                            String where = cr.sid + " " + abbreviate(cr.v.name);
                            List<Map<String, String>> m = rows.stream().filter(r -> same(r, cr.v)).toList();
                            if (m.size() != 1) {
                                c.check(where, "Listed once", false, "exactly one row with this name, code and client", m.isEmpty() ? "not in the listing" : m.size() + " rows");
                                continue;
                            }
                            Map<String, String> r = m.get(0);
                            String expectedStart = LocalDate.parse(cr.v.start).format(LIST_DATE);
                            c.eq(where, "Name | code | client | start date", norm(cr.v.name) + " | " + norm(cr.v.code).toUpperCase(Locale.ROOT) + " | " + cr.v.client
                                    + " | " + expectedStart, col(r, "PROJECT NAME") + " | " + col(r, "PROJECT CODE").toUpperCase(Locale.ROOT) + " | " + col(r, "CLIENT")
                                    + " | " + col(r, "START DATE"));
                            String status = col(r, "STATUS");
                            if (cr.expect == Expect.ACTIVE) {
                                c.eq(where, "Status", "Active", status);
                            } else if (cr.expect == Expect.NOT_ACTIVE) {
                                c.check(where, "Status", !"Active".equals(status) && !status.isEmpty(), "not Active (the end date is over)", status);
                            } else {
                                c.check(where, "Status (shown, not judged: a start date far in the past / future)", !status.isEmpty(), "a status", status);
                            }
                        }
                        if (scriptRan()) {
                            rep.defect("A script ran while the list of projects was shown (a security risk)", "Names are shown as plain text", alertText);
                        }
                    });
            scenarioChecks("Z02", "Refused and cancelled attempts created no project", "No row of the listing has the name + code + client of an attempt that was refused (or cancelled)", c -> {
                List<Map<String, String>> rows = full();
                for (Object[] rj : rejected) {
                    V v = (V) rj[1];
                    if (v.name == null || v.code == null || norm(v.name).isEmpty() || norm(v.code).isEmpty()) {
                        continue; // an attempt without a name or a code cannot be looked up
                    }
                    long count = rows.stream().filter(r -> norm(col(r, "PROJECT NAME")).equals(norm(v.name)) && col(r, "PROJECT CODE").equalsIgnoreCase(norm(v.code))
                            && (v.client == null || col(r, "CLIENT").equals(v.client))).count();
                    c.check((String) rj[0], "No project '" + abbreviate(v.name) + "' with code '" + abbreviate(v.code) + "'", count == 0, "not in the listing",
                            count == 0 ? "not in the listing" : "IS in the listing (" + count + " row(s))");
                }
                c.check("Listing", "Attempts checked", true, "all refused attempts that have a name and a code", rejected.size() + " attempt(s)");
            });
        }

        // ------------------------------------------------------------------------------------------------------------------------------ Q: listing search

        /** The rows whose project name or code contains the text (ignoring case and the spaces around the text). */
        List<Map<String, String>> matching(String term) {
            String t = term.trim().toLowerCase(Locale.ROOT);
            return full().stream().filter(r -> (col(r, "PROJECT NAME") + " " + col(r, "PROJECT CODE")).toLowerCase(Locale.ROOT).contains(t)).toList();
        }

        /**
         * Searches from a fresh listing and compares with the full list: the total ("of N") equals the number of projects that contain the text, every row shown
         * is one of them, and no match gives the empty-state message. No script runs, no HTTP 5xx.
         */
        String searchMustFind(String term, String typed) {
            List<Map<String, String>> want = matching(term);
            Set<String> wantKeys = new HashSet<>();
            want.forEach(r -> wantKeys.add(norm(col(r, "PROJECT NAME")) + "|" + col(r, "PROJECT CODE")));
            freshListing();
            projects.searchProject(typed);
            List<Map<String, String>> got = projects.rows();
            noServerError();
            if (scriptRan()) {
                rep.defect("The text typed into the search made the page run a script (a security risk)", "The search text is handled as plain text", alertText);
            }
            int total = got.isEmpty() ? 0 : projects.totalCount();
            List<String> strangers = got.stream().filter(r -> !wantKeys.contains(norm(col(r, "PROJECT NAME")) + "|" + col(r, "PROJECT CODE")))
                    .map(r -> col(r, "PROJECT NAME")).toList();
            expect(projects.searchApplied(), "The search result did not settle", "The list shows the result of the search", projects.listSummary());
            expect(total == want.size() && strangers.isEmpty(), "The search returns other projects than the ones that contain the text",
                    want.size() + " project(s) contain '" + abbreviate(term) + "'", total + " project(s) found" + (strangers.isEmpty() ? "" : ", not matching: "
                            + strangers.subList(0, Math.min(3, strangers.size()))));
            if (want.isEmpty()) {
                expect(projects.emptyStateShown(), "No project matches but no 'nothing found' message is shown", "'No projects found'", projects.listSummary());
            }
            return "Search '" + abbreviate(typed) + "': " + total + " found, " + want.size() + " expected; " + projects.listSummary();
        }

        void listingSearch() {
            if (base == null) {
                return;
            }
            String last8 = base.name.substring(Math.max(0, base.name.length() - 8));
            scenario("Q01", "Search by the full project name", "Every project with that name (P01 and the same name for another client) is found",
                    () -> searchMustFind(base.name, base.name));
            scenario("Q02", "Search by the project code", "Every project with that code is found", () -> searchMustFind(base.code, base.code));
            scenario("Q03", "Search ignores upper / lower case", "The same projects are found with the name in UPPER CASE",
                    () -> searchMustFind(base.name, base.name.toUpperCase(Locale.ROOT)));
            scenario("Q04", "Spaces before and after the search text are ignored", "The same projects are found with 3 spaces before and after",
                    () -> searchMustFind(base.name, "   " + base.name + "   "));
            scenario("Q05", "Search by the last characters of the name", "Every project whose name contains them is found", () -> searchMustFind(last8, last8));
            scenario("Q06", "Search with no match shows an empty-state message", "No rows and the message 'No projects found'",
                    () -> searchMustFind("zzz-no-such-project-" + stamp, "zzz-no-such-project-" + stamp));
            String[][] safe = { { "<script>alert(1)</script>", "Q07" }, { "%", "Q08" }, { "'", "Q09" }, { "\"; DROP TABLE projects;--", "Q10" }, { "\\", "Q11" },
                    { "_", "Q12" }, { "A".repeat(300), "Q13" } };
            for (String[] s : safe) {
                scenario(s[1], "Search text '" + abbreviate(s[0]) + "' is handled safely",
                        "No script runs, no HTTP 5xx; exactly the projects that contain the text are found (a wildcard character is not a wildcard)",
                        () -> searchMustFind(s[0], s[0]));
            }
            scenario("Q14", "Clearing the search restores the full list", "The same number of projects as before the search", () -> {
                int all = full().size();
                freshListing();
                projects.searchProject(base.name);
                projects.clearSearch();
                int total = projects.totalCount();
                expect(total == all, "The list after clearing the search differs from the full list", all + " projects", total + " (" + projects.listSummary() + ")");
                return "Full list: " + all + "; after clearing the search: " + projects.listSummary();
            });
            scenario("Q15", "A search typed while page 2 is shown finds the project (the list goes back to page 1)", "The project is listed; 'Showing 1-...'", () -> {
                if (full().size() <= 10) {
                    throw new SkipException("Only " + full().size() + " project(s): there is no page 2 at 10 per page.");
                }
                freshListing();
                projects.clickNext();
                String onPage2 = projects.listSummary();
                projects.searchProject(base.name);
                boolean listed = projects.rows().stream().anyMatch(r -> same(r, base));
                expect(listed && projects.listSummary().matches("Showing\\s+1\\D.*"), "A search started on page 2 does not show its result",
                        "The project on page 1 of the result", projects.listSummary() + (listed ? "" : " (the project is not shown)"));
                return "Before: " + onPage2 + "; after the search: " + projects.listSummary();
            });
        }

        // ------------------------------------------------------------------------------------------------------------------------------ K: tabs and client filter

        String statusOf(Map<String, String> r) {
            return col(r, "STATUS");
        }

        void listingFilters() {
            String other = aor ? "Expired" : "Inactive";
            scenario("K01", "Tab 'Active' lists exactly the Active projects", "Every row is Active and the count equals the number of Active projects", () -> {
                long expected = full().stream().filter(r -> "Active".equals(statusOf(r))).count();
                freshListing();
                projects.clickTab("Active");
                List<Map<String, String>> got = projects.allRows();
                expect(got.stream().allMatch(r -> "Active".equals(statusOf(r))) && got.size() == expected, "The Active tab shows other projects than the Active ones",
                        expected + " Active project(s)", got.size() + " row(s) with status " + got.stream().map(this::statusOf).distinct().toList());
                return "Active tab: " + got.size() + " row(s), expected " + expected;
            });
            scenario("K02", "Tab '" + other + "' lists exactly the " + other + " projects", "Every row is " + other + "; none: 'No projects found'", () -> {
                long expected = full().stream().filter(r -> other.equals(statusOf(r))).count();
                freshListing();
                projects.clickTab(other);
                List<Map<String, String>> got = projects.allRows();
                expect(got.stream().allMatch(r -> other.equals(statusOf(r))) && got.size() == expected, "The " + other + " tab shows other projects than the " + other
                        + " ones", expected + " " + other + " project(s)", got.size() + " row(s) with status " + got.stream().map(this::statusOf).distinct().toList());
                if (expected == 0) {
                    expect(projects.emptyStateShown(), "No project is " + other + " but no 'nothing found' message is shown", "'No projects found'", projects.listSummary());
                }
                return other + " tab: " + got.size() + " row(s), expected " + expected;
            });
            scenario("K03", "Every project of the list has a status the tabs know", "Each status is Active or " + other, () -> {
                List<String> unknown = full().stream().map(this::statusOf).filter(s -> !s.equals("Active") && !s.equals(other)).distinct().toList();
                expect(unknown.isEmpty(), "A project has a status that no tab lists", "Active or " + other, unknown.toString());
                return "Statuses in the list: " + full().stream().map(this::statusOf).distinct().toList();
            });
            scenario("K04", "Client filter lists exactly the projects of that client", "Every row has that client and the count equals the number of its projects", () -> {
                long expected = full().stream().filter(r -> client.equals(col(r, "CLIENT"))).count();
                freshListing();
                projects.filterByClient(client);
                List<Map<String, String>> got = projects.allRows();
                expect(got.stream().allMatch(r -> client.equals(col(r, "CLIENT"))) && got.size() == expected, "The client filter shows other projects",
                        expected + " project(s) of " + client, got.size() + " row(s) of " + got.stream().map(r -> col(r, "CLIENT")).distinct().toList());
                return "Client filter '" + client + "': " + got.size() + " row(s), expected " + expected;
            });
            scenario("K05", "'Clear all' resets the client filter", "The full list is shown again", () -> {
                int all = full().size();
                freshListing();
                projects.filterByClient(client);
                projects.clearAll();
                int total = projects.totalCount();
                expect(total == all, "'Clear all' did not restore the full list", all + " projects", projects.listSummary());
                return "After 'Clear all': " + projects.listSummary();
            });
            scenario("K06", "A client filter and the search combine (AND)", "Another client + the name of project 1: not listed; its own client + the name: listed", () -> {
                needBase();
                String another = clients.stream().filter(c -> !c.equals(base.client)).findFirst().orElse(null);
                if (another == null) {
                    throw new SkipException("Only one " + type + " client exists.");
                }
                freshListing();
                projects.filterByClient(another);
                projects.searchProject(base.name);
                boolean none = projects.rows().stream().noneMatch(r -> same(r, base));
                boolean onlyAnother = projects.rows().stream().allMatch(r -> another.equals(col(r, "CLIENT")));
                expect(none && onlyAnother, "Project 1 (or a project of another client) is listed under the filter of '" + another + "'",
                        "Only projects of " + another, projects.listSummary());
                freshListing();
                projects.filterByClient(base.client);
                projects.searchProject(base.name);
                boolean listed = projects.rows().stream().anyMatch(r -> same(r, base));
                expect(listed, "Project 1 is missing under its own client's filter", "Project 1 is listed", projects.listSummary());
                return "Filter '" + another + "': " + (none ? "project 1 not listed" : "PROJECT 1 LISTED") + "; own client: " + (listed ? "listed" : "MISSING");
            });
            scenario("K07", "Choosing a tab while page 2 is shown goes back to page 1", "'Showing 1-...' with rows (not an empty page 2)", () -> {
                long active = full().stream().filter(r -> "Active".equals(statusOf(r))).count();
                if (full().size() <= 10 || active == 0) {
                    throw new SkipException("There is no page 2 at 10 per page, or no Active project.");
                }
                freshListing();
                projects.clickNext();
                String onPage2 = projects.listSummary();
                projects.clickTab("Active");
                boolean firstPage = projects.listSummary().matches("Showing\\s+1\\D.*") && !projects.rows().isEmpty();
                expect(firstPage, "After choosing a tab on page 2 the list does not start at page 1", "Showing 1-... with rows",
                        projects.listSummary() + " with " + projects.rows().size() + " row(s)");
                return "Before: " + onPage2 + "; after the tab: " + projects.listSummary();
            });
        }

        // ------------------------------------------------------------------------------------------------------------------------------ G: page size, pages, sort

        void listingTable() {
            int[] sizes = { 5, 10, 20, 50 };
            for (int k = 0; k < sizes.length; k++) {
                int size = sizes[k];
                scenario("G0" + (k + 1), "Page size " + size + " shows " + size + " projects per page",
                        "min(" + size + ", N) rows and 'Showing 1-" + size + " of N projects'", () -> {
                            int n = full().size();
                            freshListing();
                            projects.setPageSize(size);
                            int rows = projects.rows().size();
                            int total = projects.totalCount();
                            boolean firstPage = projects.listSummary().matches("Showing\\s+1\\D.*");
                            expect(rows == Math.min(size, n) && total == n && firstPage, "The page size is not respected",
                                    "Showing 1-" + Math.min(size, n) + " of " + n + " (" + Math.min(size, n) + " rows)", projects.listSummary() + " with " + rows + " rows");
                            return rows + " row(s) shown; " + projects.listSummary();
                        });
            }
            scenario("G05", "Previous / Next are enabled only where there is a page to go to",
                    "Previous is disabled on page 1; Next is disabled on the last page; the number of pages fits N", () -> {
                        int n = full().size();
                        if (n <= 10) {
                            throw new SkipException("Only " + n + " project(s): a single page of 10.");
                        }
                        freshListing();
                        expect(!projects.prevEnabled(), "Previous is enabled on page 1", "Previous disabled", "enabled");
                        int pages = 1;
                        while (projects.nextEnabled() && pages < 40) {
                            projects.clickNext();
                            pages++;
                        }
                        int expectedPages = (int) Math.ceil(n / 10.0);
                        expect(!projects.nextEnabled(), "Next is still enabled on the last page", "Next disabled on the last page", "enabled after " + pages + " page(s)");
                        expect(projects.prevEnabled(), "Previous is disabled on the last page", "Previous enabled", "disabled");
                        expect(pages == expectedPages, "The number of pages is wrong", expectedPages + " pages for " + n + " projects", pages + " pages");
                        return pages + " pages for " + n + " projects; last page: " + projects.listSummary();
                    });
            scenario("G06", "Sorting by Project Name: ascending, then descending", "1st click ascending, 2nd click descending (names of plain letters / digits compared)",
                    () -> {
                        freshListing();
                        projects.setPageSize(50);
                        projects.clickHeader("Project Name");
                        List<String> asc = cleanNames(projects.rows());
                        projects.clickHeader("Project Name");
                        List<String> desc = cleanNames(projects.rows());
                        boolean ascOk = isSorted(asc, false);
                        boolean descOk = isSorted(desc, true);
                        expect(ascOk, "The first click does not sort ascending", "Ascending order (case-sensitive or not)", abbreviate(String.valueOf(asc)));
                        expect(descOk, "The second click does not sort descending", "Descending order (case-sensitive or not)", abbreviate(String.valueOf(desc)));
                        if (ascOk && !isSortedIgnoringCase(asc)) {
                            rep.note("Observation: the sort is case-sensitive (capital letters before lower case: '" + asc.get(0) + "' before '"
                                    + asc.stream().filter(n -> !n.equals(asc.get(0))).findFirst().orElse("") + "').");
                        }
                        return "ascending " + abbreviate(String.valueOf(asc)) + "; descending " + abbreviate(String.valueOf(desc));
                    });
            scenario("G07", "Sorting by Project Name orders ALL projects, not only the rows of the current page",
                    "Read through every page (10 per page), the names (plain letters / digits) are in ascending order", () -> {
                        int n = full().size();
                        if (n <= 10) {
                            throw new SkipException("Only " + n + " project(s): a single page.");
                        }
                        freshListing();
                        projects.clickHeader("Project Name");
                        List<String> seen = new ArrayList<>(cleanNames(projects.rows()));
                        int pages = 1;
                        while (projects.nextEnabled() && pages < 40) {
                            projects.clickNext();
                            seen.addAll(cleanNames(projects.rows()));
                            pages++;
                        }
                        List<String> sorted = new ArrayList<>(seen);
                        sorted.sort(Comparator.comparing(x -> x.toLowerCase(Locale.ROOT)));
                        boolean inOrder = isSorted(seen, false);
                        int firstWrong = -1;
                        for (int i = 0; i < seen.size() && !inOrder && firstWrong < 0; i++) {
                            if (!seen.get(i).equalsIgnoreCase(sorted.get(i))) {
                                firstWrong = i;
                            }
                        }
                        expect(inOrder, "The sort only re-orders the rows of the current page", "Ascending across all " + pages + " pages",
                                firstWrong < 0 ? "in order" : "position " + (firstWrong + 1) + " (page " + (firstWrong / 10 + 1) + ") shows '" + seen.get(firstWrong)
                                        + "' where '" + sorted.get(firstWrong) + "' belongs");
                        return pages + " pages read; " + seen.size() + " plain names are " + (inOrder ? "in order" : "NOT in order");
                    });
            scenario("G08", "'Showing a-b of N projects' matches the rows shown", "The numbers fit the rows on page 1", () -> {
                int n = full().size();
                freshListing();
                int rows = projects.rows().size();
                java.util.regex.Matcher m = Pattern.compile("(\\d+)\\D+(\\d+)\\s+of\\s+(\\d+)").matcher(projects.listSummary());
                boolean ok = m.find() && Integer.parseInt(m.group(3)) == n && Integer.parseInt(m.group(2)) - Integer.parseInt(m.group(1)) + 1 == rows;
                expect(ok, "The 'Showing' text does not match the rows", "Showing 1-" + Math.min(10, n) + " of " + n, projects.listSummary() + " with " + rows + " rows");
                return projects.listSummary() + " | " + rows + " rows";
            });
        }

        /** True when the names are in ascending (or descending) order, either case-sensitive (capitals first, as the application sorts) or ignoring case. */
        boolean isSorted(List<String> names, boolean descending) {
            Comparator<String> natural = descending ? Comparator.<String>naturalOrder().reversed() : Comparator.<String>naturalOrder();
            Comparator<String> ignoring = descending ? String.CASE_INSENSITIVE_ORDER.reversed() : String.CASE_INSENSITIVE_ORDER;
            return ordered(names, natural) || ordered(names, ignoring);
        }

        boolean isSortedIgnoringCase(List<String> names) {
            return ordered(names, String.CASE_INSENSITIVE_ORDER);
        }

        boolean ordered(List<String> names, Comparator<String> by) {
            for (int i = 1; i < names.size(); i++) {
                if (by.compare(names.get(i - 1), names.get(i)) > 0) {
                    return false;
                }
            }
            return true;
        }

        /** Names made of plain letters, digits, spaces and hyphens only (the order of names with symbols depends on the collation of the application). */
        List<String> cleanNames(List<Map<String, String>> rows) {
            List<String> out = new ArrayList<>();
            for (Map<String, String> r : rows) {
                String n = norm(col(r, "PROJECT NAME"));
                if (n.matches("[A-Za-z0-9 \\-]+")) {
                    out.add(n);
                }
            }
            return out;
        }

        // ------------------------------------------------------------------------------------------------------------------------------ U: access without a session (last)

        void access() {
            String[] paths = { "/projects/" + type.toLowerCase(Locale.ROOT), "/projects/" + type.toLowerCase(Locale.ROOT) + "/add" };
            for (int i = 0; i < paths.length; i++) {
                String path = paths[i];
                scenario("U0" + (i + 1), "Opening " + path + " without being signed in shows the login page", "The login page; no project data is shown", () -> {
                    login.freshLogin(ConfigManager.baseUrl());
                    signedIn = false;
                    Landing l = PageProbe.open(driver, ConfigManager.baseUrl() + path, 10);
                    expect(l.loginForm && !l.adminContent, "A protected Project page can be opened without being signed in", "The login page", l.describe());
                    return l.describe();
                });
            }
            scenario("U03", "After signing out, browser Back does not show the project listing", "The login page (or a signed-out state); the listing is not shown", () -> {
                signInQuiet();
                projects.openListing(type);
                if (!home.signOut()) {
                    throw new SkipException("Could not sign out: no 'Log out' control or the login page did not appear");
                }
                signedIn = false;
                driver.navigate().back();
                Landing l = PageProbe.settle(driver, "the browser's Back button after signing out", 8);
                expect(!l.adminContent && !projects.listingOpen(type), "Protected project content is shown after sign out + Back", "The login page", l.describe());
                return l.describe();
            });
        }

        // ------------------------------------------------------------------------------------------------------------------------------ small helpers

        String norm(String s) {
            return s == null ? "" : s.replaceAll("\\s+", " ").trim();
        }

        String col(Map<String, String> r, String header) {
            return ProjectsPage.column(r, header);
        }

        String abbreviate(String s) {
            String t = norm(s);
            return t.length() > 60 ? t.substring(0, 57) + "..." : t;
        }

        /** The text cut or padded (with {@code pad}) to exactly {@code length} characters (code points: an emoji counts as one, like the server counts). */
        String toLength(String s, int length, char pad) {
            int cps = s.codePointCount(0, s.length());
            if (cps >= length) {
                return s.substring(0, s.offsetByCodePoints(0, length));
            }
            return s + String.valueOf(pad).repeat(length - cps);
        }
    }
}
