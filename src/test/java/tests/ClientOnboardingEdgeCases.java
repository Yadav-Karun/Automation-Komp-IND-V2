package tests;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.Collator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.By;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import api.ApiCall;
import base.BaseTest;
import flow.ClientOnboardingFlow;
import model.ClientData;
import pages.AdminHomePage;
import pages.ClientPortalHomePage;
import pages.ClientsPage;
import pages.EorMsaPage;
import pages.LoginPage;
import pages.OrganizationDetailsPage;
import pages.PasswordResetPage;
import pages.PointOfContactPage;
import pages.RegisterClientPage;
import pages.ReviewInformationPage;
import reporting.ExecutionInfo;
import reporting.TcCatalog;
import reporting.TestReporter;
import utils.ClientNames;
import utils.ConfigManager;
import utils.FormFeedback;
import utils.FormFeedback.Outcome;
import utils.Log;
import utils.PageProbe;
import utils.PageProbe.Landing;
import utils.SecretMasker;
import utils.TestDataGenerator;
import utils.ToastWatcher;

/**
 * CLIENT ONBOARDING - E2E NEGATIVE AND EDGE CASES.
 *
 * ONE class, ONE report, ONE client. Every negative / edge case of the client module is here; the case ids keep their prefixes:
 *   L login | B1 B2 B3 B5 direct URLs, role boundary, session, EOR status | R register-client form | P first-login password
 *   O organization details | D documents | C points of contact | B4 wizard flow (refresh, Back, re-login, review page, back-and-edit)
 *   X Clients list (search, filters, pagination, sort, columns) + MSA list search + API health
 *
 * THE 2-CLIENT RULE: the whole module uses at most 2 clients - one in the happy-flow class, ONE here. This class registers
 * the edge client with R-60 (a double click on 'Register Client': it must still create exactly one client) and then walks that
 * client through the wizard: enter details, move next, go back, re-enter different values ... The wizard is NEVER confirmed
 * ('Confirm &amp; Continue' starts the MSA) and no client is ever approved, so the edge client stays In-Progress.
 * Cases that would create an EXTRA client when the app accepts their input (R-31, R-50..R-52) are skipped in a full run and
 * only run when named in -Dcases.
 *
 * ORDER OF A FULL RUN: login negatives -> unauthenticated URLs -> register form negatives (admin) -> R-60 registers the client
 * -> welcome mail -> first-login password rules -> client on admin URLs / session -> Organization Details + documents
 * -> Points of Contact -> wizard flow -> admin-side checks -> Clients list -> MSA search -> sign-out case.
 *
 * SET-UP ON DEMAND: each lifecycle step (P-00 register + mail, P-02 first login, P-30 permanent password, O-00 / C-00 wizard
 * baselines) runs once, as its own report case, the first time a case needs it. So a single case can be re-run:
 *     mvn clean test -Dsuite=testng-client-edge-cases.xml -Dcases=O-10        (registers the client first, then runs O-10)
 * and before every case the state is checked and healed (signed in? on the right wizard step? baseline values?), so one
 * failing case never breaks the next one. A browser closed by hand is replaced automatically.
 *
 * Options:  -Dcases=R-14,O-02            only these case ids
 *           -Dedge.client.email=<yopmail> reuse an existing client instead of registering (password: env KOMP_EDGE_CLIENT_PASSWORD,
 *                                          default the common client password); first-login cases need a never-logged-in client and are not run
 *           -Dedge.client.fresh=true -Dedge.client.name="<legal name>"   with the above: the client NEVER logged in (e.g. a Pending client left
 *                                          by an earlier run): its welcome mail is read and the first-login password cases run on it too
 *           -Deor.active.client=<name>     ACTIVE client for B-40 / B-41 / X-70 (default: the first ACTIVE client of the list)
 */
public class ClientOnboardingEdgeCases extends BaseTest {

    // ---- case groups (kept from the earlier suites: they drive the Excel summary, the Jira bugs and the TC catalog)
    private static final String G_LOGIN = "A2 Login";
    private static final String G_REG = "A1 Register client";
    private static final String G_SETUP = "A3 Client setup";
    private static final String G_PWD = "A3 First-login password";
    private static final String G_ORG = "A4 Organization details";
    private static final String G_DOC = "A5 Documents";
    private static final String G_POC = "A6 Points of contact";
    private static final String G_URL = "B1 Direct URL (no session)";
    private static final String G_ROLE = "B2 Role boundary (client session)";
    private static final String G_SESSION = "B3 Session and reload";
    private static final String G_WIZARD = "B4 Wizard flow";
    private static final String G_EOR = "B5 EOR status of ACTIVE client";
    private static final String G_SEARCH = "C1 Clients search";
    private static final String G_FILTER = "C2 Clients filters";
    private static final String G_TABLE = "C3 Clients table controls";
    private static final String G_MSA = "C4 MSA list search";
    private static final String G_API = "C5 API health (read-only)";

    private static final String WRONG = "Wrong#Pass123";
    private static final String[] ORG_FIELDS = { "years_in_industry", "website_link", "product_or_service_description",
            "registered_address.pincode", "registered_address.address", "email", "mobile_number", "company_registration_number", "gst_number" };

    private enum Who { NONE, ADMIN, CLIENT }

    // ---- page objects (created again when the browser is restarted)
    private LoginPage loginPage;
    private ClientsPage clientsPage;
    private RegisterClientPage registerPage;
    private PasswordResetPage pwdPage;
    private ClientPortalHomePage homePage;
    private OrganizationDetailsPage orgPage;
    private PointOfContactPage pocPage;
    private ReviewInformationPage reviewPage;

    // ---- THE edge client (one per run) and how far its lifecycle has come
    private ClientData data;
    /** -Dedge.client.email was given: an existing client is used, nothing is registered. */
    private boolean existingGiven;
    /** ... and it never logged in (-Dedge.client.fresh=true): its welcome mail is read and the first-login cases run on it. */
    private boolean fresh;
    /** ... and it already logged in before: the first-login cases do not apply, the known password is used. */
    private boolean reuse;
    private String tempPassword;
    private String portalLink;
    private String activePassword;
    private boolean registered;
    private boolean mailRead;
    private boolean atDialog;
    private boolean passwordChanged;     // a weak / over-long password was wrongly accepted by the dialog
    private boolean passwordSet;
    private boolean orgBaselineDone;
    private boolean pocBaselineDone;
    private final Map<String, String> setupFailed = new LinkedHashMap<>();
    private Who session = Who.NONE;

    // ---- Clients list fixture (read-only cases) and misc
    private ClientsPage.Row ref;
    private List<ClientsPage.Row> allRows;
    private int total;
    private TestReporter current;
    private String activeClientName;
    private Outcome lastOutcome;
    private Path files;

    @Override
    protected void browserReady() {
        loginPage = new LoginPage(driver);
        clientsPage = new ClientsPage(driver);
        registerPage = new RegisterClientPage(driver);
        pwdPage = new PasswordResetPage(driver);
        homePage = new ClientPortalHomePage(driver);
        orgPage = new OrganizationDetailsPage(driver);
        pocPage = new PointOfContactPage(driver);
        reviewPage = new ReviewInformationPage(driver);
        session = Who.NONE; // a new browser has no sessions
    }

    /** After a network loss the browser was sent back to the login page: no session is left. */
    @Override
    protected void browserHealed() {
        session = Who.NONE;
    }

    @BeforeClass(dependsOnMethods = "startClass")
    public void prepareEdgeClient() throws IOException {
        data = TestDataGenerator.generateEdgeCase();
        String existing = ConfigManager.get("edge.client.email");
        existingGiven = existing != null;
        fresh = existingGiven && ConfigManager.getBool("edge.client.fresh", false);
        reuse = existingGiven && !fresh;
        String name = null;
        if (existingGiven) {
            data.email = existing;
            registered = true;
            name = ConfigManager.get("edge.client.name"); // needed when the welcome mail has to be read (fresh client)
            if (name == null) {
                name = ClientNames.nameForEmail(existing); // else the registry (testdata/existing-clients.txt) knows the client by its e-mail
            }
            if (name != null) {
                data.legalName = name;
            } else {
                Log.warn("The client of " + existing + " is not in the client names file: pass -Dedge.client.name=\"<legal name>\"; "
                        + "the report shows a placeholder name '" + data.legalName + "' instead");
            }
        }
        if (fresh && name == null) {
            throw new IllegalStateException("-Dedge.client.fresh=true needs the legal name of that client: pass -Dedge.client.name=\"<legal name>\" "
                    + "or list the client in the client names file (testdata/existing-clients.txt) - the welcome e-mail is checked for it");
        }
        if (reuse) {
            mailRead = true;
            String pw = ConfigManager.get("edge.client.password");
            activePassword = pw != null ? pw : ConfigManager.clientPassword();
            SecretMasker.register(activePassword);
            data.password = activePassword;
        }
        files = Paths.get("target", "negative-files");
        Files.createDirectories(files);
    }

    // ================================================================== infrastructure: sessions

    private String base() {
        return ConfigManager.baseUrl();
    }

    /** Signs in (the old session is ended first). A rate limit that outlasts the wait budget skips the case: it is not a product result. */
    private void signIn(String user, String password, Who who) {
        try {
            loginPage.freshLogin(base());
            loginPage.login(user, password);
        } catch (AssertionError e) {
            if (LoginPage.isRateLimited(List.of(String.valueOf(e.getMessage())))) {
                throw new SkipException("Sign-in is rate limited by the application ('Too many sign-in attempts') and the wait budget "
                        + "(login.ratelimit.max.wait.seconds) is used up: run this case again in a few minutes");
            }
            throw e;
        }
        session = who;
    }

    private boolean stillSignedIn(String path) {
        try {
            return !PageProbe.open(driver, base() + path, 8).loginForm;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Quick check without a page load: a page of the given area is open and it is not the login form. */
    private boolean onPageOf(boolean adminArea) {
        try {
            String url = driver.getCurrentUrl();
            boolean portal = url.contains("/portal");
            return url.startsWith(base()) && !url.contains("/login") && portal != adminArea && driver.findElements(By.id("password")).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void adminSession() {
        if (session == Who.ADMIN && (onPageOf(true) || stillSignedIn("/"))) {
            return;
        }
        signIn(ConfigManager.adminEmail(), ConfigManager.adminPassword(), Who.ADMIN);
        Assert.assertTrue(new AdminHomePage(driver).isLoaded(), "Admin dashboard not displayed after login.");
    }

    /** Signed in as the edge client (the lifecycle up to the permanent password runs first when it has not yet). */
    private void clientSession() {
        ensurePassword();
        if (session == Who.CLIENT && (onPageOf(false) || stillSignedIn("/portal"))) {
            return;
        }
        signIn(data.email, activePassword, Who.CLIENT);
        Assert.assertTrue(driver.getCurrentUrl().contains("/portal"), "The edge client did not reach the client portal: " + driver.getCurrentUrl());
    }

    private void signedOut() {
        loginPage.freshLogin(base());
        session = Who.NONE;
    }

    // ================================================================== infrastructure: case runner

    /**
     * Runs one read-only style case: {@code needs} is the session the case runs in, {@code prerequisite} an optional extra
     * set-up. Both run BEFORE the report case starts, so a rate-limited sign-in or a failed prerequisite is a SKIP / FAIL row
     * with its reason and not a half-written case.
     */
    private void runCase(String id, String group, String title, String input, String expected, Who needs, Runnable prerequisite,
            Callable<String> body) {
        onlyIfSelected(id);
        prepare(id, group, title, () -> {
            if (needs == Who.ADMIN) {
                adminSession();
            } else if (needs == Who.CLIENT) {
                clientSession();
            }
            if (prerequisite != null) {
                prerequisite.run();
            }
        });
        TestReporter rep = TestReporter.start(id, group, title, group + " negative test: " + title, input, expected, driver, data);
        current = rep;
        rep.phase(slug(id), title, expected, body::call);
        assertNoDefects(rep);
    }

    private void runCase(String id, String group, String title, String input, String expected, Who needs, Callable<String> body) {
        runCase(id, group, title, input, expected, needs, null, body);
    }

    /**
     * Brings the application into the state a case needs. A SkipException (prerequisite failed earlier / not applicable) is
     * written as a SKIP row with its reason; any other problem is written as a FAIL row with a screenshot, so nothing disappears.
     */
    private void prepare(String id, String group, String title, Runnable prerequisite) {
        try {
            prerequisite.run();
        } catch (SkipException e) {
            skipRow(id, group, title, e.getMessage());
            throw e;
        } catch (RuntimeException | Error t) {
            if (utils.Connectivity.isNetworkFailure(driver, t)) {
                // the connection was lost while the state was prepared: not an application result
                String reason = "The network connection to the application was lost while this case was being prepared ("
                        + utils.Connectivity.describe(t) + "). This is not an application result: run the case again (-Dcases=" + id + ").";
                skipRow(id, group, title, reason);
                throw new SkipException(reason);
            }
            TestReporter rep = TestReporter.start(id, group, title, group + " negative test: " + title, "-",
                    "The session / wizard step this case needs can be prepared", driver, data);
            rep.phase("prepare", "Prepare the state this case needs", "The session / wizard step is ready", () -> {
                if (t instanceof Error) {
                    throw (Error) t;
                }
                throw (RuntimeException) t;
            });
            throw t;
        }
    }

    private void skipRow(String id, String group, String title, String reason) {
        TestReporter rep = TestReporter.start(id, group, title, group + " negative test: " + title, "-", "-", driver, data);
        rep.finish("SKIP", new SkipException(reason));
    }

    /** A case that creates an EXTRA client when the app accepts its input only runs when it is named in -Dcases (2-client rule). */
    private void skipUnlessNamed(String id, String group, String title) {
        if (TcCatalog.SIDE_EFFECT.contains(id) && !casesFilterActive()) {
            String reason = "Not run in a full run: if the application accepts this input it creates an EXTRA client, and the module is limited to "
                    + "2 clients. Run it on purpose with -Dcases=" + id;
            skipRow(id, group, title, reason);
            throw new SkipException(reason);
        }
    }

    // ================================================================== infrastructure: set-up chain (each step once, on demand)

    private void failedBefore(String step) {
        String why = setupFailed.get(step);
        if (why != null) {
            throw new SkipException("Prerequisite " + step + " failed earlier, so this case cannot run: " + why);
        }
    }

    private ClientOnboardingFlow flow(TestReporter rep) {
        ClientOnboardingFlow f = new ClientOnboardingFlow(driver, rep, data);
        f.restoreMailState(tempPassword, portalLink);
        return f;
    }

    /** Keeps the data of the edge client (no passwords) in the report folder, so a later run can reuse exactly this client. */
    private void saveClientData() {
        ClientNames.record(data.legalName, data.email); // the client exists now: its name is never picked again (nothing happens if already listed)
        TestDataGenerator.save(data, ExecutionInfo.root().resolve("test-data.json"));
        Log.info("Edge client: " + data.legalName + " | " + data.email + " (its password is the common client password; if the app accepted "
                + "case P-23 it is 'Aa1!' followed by 296 x). Reuse it with -Dedge.client.email=" + data.email);
    }

    private static String firstLine(Throwable t) {
        String m = String.valueOf(t.getMessage());
        int nl = m.indexOf('\n');
        return nl < 0 ? m : m.substring(0, nl);
    }

    private SkipException setupFailure(String step, TestReporter rep, Throwable t) {
        setupFailed.put(step, firstLine(t));
        rep.finish("FAIL", t);
        return new SkipException("Prerequisite " + step + " failed (" + firstLine(t) + "): the cases that need it are skipped");
    }

    /** P-00: the edge client is registered (single click, unless R-60 already did it with a double click) and its welcome mail is read. */
    private void ensureMailRead() {
        if (mailRead) {
            return;
        }
        failedBefore("P-00");
        TestReporter rep = TestReporter.start("P-00", G_SETUP, "Setup: register the edge client and read its welcome e-mail",
                "Creates the ONE client used by every lifecycle case of this class (2-client rule)",
                "Valid unique data, all yopmail addresses (" + data.email + ")",
                "Client registered, welcome mail with the temporary password received", driver, data);
        ClientOnboardingFlow f = flow(rep);
        try {
            if (!registered) {
                rep.phase("admin_login", "Admin login", "Dashboard visible", () -> {
                    String r = f.adminLoginFresh();
                    session = Who.ADMIN;
                    return r;
                });
                rep.phase("client_registration", "Register client", "Client registered and listed as Pending", () -> {
                    String r = f.registerClient();
                    registered = true;
                    saveClientData();
                    return r;
                });
            }
            rep.phase("welcome_email", "Welcome email (Yopmail)", "Welcome mail with the temporary password received", f::verifyWelcomeEmail);
            f.closeMailTab();
            tempPassword = f.temporaryPassword();
            data.temporaryPassword = tempPassword;
            portalLink = f.portalLink();
            mailRead = true;
            rep.finish("PASS", null);
        } catch (Throwable t) {
            throw setupFailure("P-00", rep, t);
        }
    }

    /** P-02: first login with the temporary password, the 'Set a new password' dialog is open. */
    private void ensureDialog() {
        if (reuse) {
            throw new SkipException("Existing client (-Dedge.client.email): the first-login password cases need a client that never logged in");
        }
        ensureMailRead();
        if (passwordChanged || passwordSet) {
            throw new SkipException("The first login was already completed (the password was changed), the dialog is gone");
        }
        if (atDialog) {
            if (pwdPage.isOpenNow()) {
                return;
            }
            loginPage.freshLogin(base()); // the dialog was lost (reload / navigation): sign in with the temporary password again
            loginPage.login(data.email, tempPassword);
            if (!pwdPage.isDisplayed()) {
                atDialog = false;
                throw new SkipException("The 'Set a new password' dialog is no longer reachable");
            }
            session = Who.NONE;
            return;
        }
        failedBefore("P-02");
        TestReporter rep = TestReporter.start("P-02", G_SETUP, "Setup: first login with the temporary password",
                "Reaches the 'Set a new password' dialog", "Temporary password from the welcome e-mail: " + SecretMasker.reveal(tempPassword),
                "'Set a new password' dialog shown", driver, data);
        try {
            rep.phase("client_portal_login", "Client portal login with temporary password", "Set a new password dialog displayed",
                    flow(rep)::clientPortalLogin);
            atDialog = true;
            session = Who.NONE;
            rep.finish("PASS", null);
        } catch (Throwable t) {
            throw setupFailure("P-02", rep, t);
        }
    }

    /** P-30: the client has its permanent password and is signed in at the portal home. */
    private void ensurePassword() {
        if (passwordSet) {
            return;
        }
        failedBefore("P-30");
        if (reuse) {
            TestReporter rep = TestReporter.start("P-30", G_SETUP, "Setup: log in as the existing client",
                    "Uses an existing yopmail client; nothing is registered", "Existing client " + SecretMasker.maskEmail(data.email) + " with its known password: " + SecretMasker.reveal(activePassword),
                    "Login reaches the client portal", driver, data);
            try {
                rep.phase("existing_client_login", "Log in as the existing client", "Client portal displayed", () -> {
                    signIn(data.email, activePassword, Who.CLIENT);
                    Assert.assertTrue(driver.getCurrentUrl().contains("/portal"), "Existing client did not reach the client portal: " + driver.getCurrentUrl());
                    return "Logged in; at " + driver.getCurrentUrl();
                });
                passwordSet = true;
                rep.finish("PASS", null);
            } catch (Throwable t) {
                throw setupFailure("P-30", rep, t);
            }
            return;
        }
        if (!passwordChanged) {
            ensureDialog();
        }
        TestReporter rep = TestReporter.start("P-30", G_SETUP, "Setup: set the permanent password and log in",
                "Completes the password step", "Valid common client password: " + SecretMasker.reveal(ConfigManager.clientPassword()), "Login with the new password reaches the portal home",
                driver, data);
        try {
            if (passwordChanged) {
                rep.phase("login_after_accepted_change", "Login with the password that was wrongly accepted", "Portal home reached", () -> {
                    Assert.assertTrue(loginPage.isDisplayed(), "Login page not shown after the dialog closed.");
                    loginPage.login(data.email, activePassword);
                    Assert.assertTrue(homePage.isStartVisible(), "Portal home (Start) not shown.");
                    return "Logged in with the password the dialog accepted";
                });
            } else {
                rep.phase("password_reset", "Reset client password", "Password updated; login with it succeeds", flow(rep)::resetPassword);
                activePassword = ConfigManager.clientPassword();
            }
            data.password = activePassword;
            passwordSet = true;
            session = Who.CLIENT;
            rep.finish("PASS", null);
        } catch (Throwable t) {
            throw setupFailure("P-30", rep, t);
        }
    }

    /** O-00 on the first call; afterwards a silent self-heal: signed in, wizard on Organization Details, baseline values, both documents. */
    private void ensureOrgStep() {
        if (!orgBaselineDone) {
            failedBefore("O-00");
            clientSession();
            TestReporter rep = TestReporter.start("O-00", G_ORG, "Setup: open Organization Details and fill valid data + documents",
                    "Valid baseline for the single-field negative cases", "Valid organization data and the CIN / GST documents",
                    "Form accepts the values and both documents show", driver, data);
            try {
                rep.phase("organization_details", "Open and fill organization details", "Form filled", () -> {
                    gotoOrgStep();
                    orgPage.fill(data);
                    return "Organization form filled with valid data";
                });
                rep.phase("documents", "Upload CIN and GST documents", "Both documents uploaded", () -> {
                    orgPage.removeAllDocuments(); // an existing client may already have documents attached
                    orgPage.uploadCin(data.cinDocPath, fileName(data.cinDocPath));
                    orgPage.uploadTaxId(data.taxDocPath, fileName(data.taxDocPath));
                    return "CIN and GST documents uploaded";
                });
                orgBaselineDone = true;
                rep.finish("PASS", null);
            } catch (Throwable t) {
                throw setupFailure("O-00", rep, t);
            }
            return;
        }
        if (!(session == Who.CLIENT && orgPage.isOpen())) {
            clientSession();
        }
        if (!orgPage.isOpen()) {
            gotoOrgStep();
            orgPage.fill(data); // the wizard had moved on (an accepted bad value): everything is entered again
        } else {
            orgPage.ensureValues(data);
        }
        ensureDocuments();
    }

    /** C-00 on the first call; afterwards a silent self-heal: the wizard is on Points of Contact with the valid contacts. */
    private void ensurePocStep() {
        if (!pocBaselineDone) {
            failedBefore("C-00");
            ensureOrgStep();
            TestReporter rep = TestReporter.start("C-00", G_POC, "Setup: reach Points of Contact with valid data",
                    "Valid baseline for the single-field negative cases", "Valid organization data + documents, valid three contacts",
                    "Wizard reaches step 2 and accepts the valid contacts", driver, data);
            try {
                rep.phase("to_points_of_contact", "Continue to Points of Contact", "Step 2 open", () -> {
                    orgPage.clickContinue();
                    pocPage.dismissContinueSetupPrompt();
                    pocPage.waitUntilOpen();
                    pocPage.fill(data);
                    return "Step 2 open; valid contacts entered";
                });
                pocBaselineDone = true;
                rep.finish("PASS", null);
            } catch (Throwable t) {
                throw setupFailure("C-00", rep, t);
            }
            return;
        }
        if (!(session == Who.CLIENT && pocPage.isOpen())) {
            clientSession();
        }
        if (pocPage.isOpen()) {
            if (!pocMatchesBaseline()) {
                pocPage.fill(data);
            }
            return;
        }
        if ("Review of Information".equals(wizardStep())) {
            Assert.assertTrue(pocPage.clickBack(), "No Back button on the Review step");
        } else {
            ensureOrgStep();
            orgPage.clickContinue();
            pocPage.dismissContinueSetupPrompt();
        }
        pocPage.waitUntilOpen();
        pocPage.fill(data);
    }

    /** The wizard is on the Review step (valid organization data and contacts were entered on the way). */
    private void ensureReviewStep() {
        if ("Review of Information".equals(wizardStep()) && session == Who.CLIENT) {
            return;
        }
        ensurePocStep();
        pocPage.clickContinue();
        reviewPage.waitUntilOpen();
    }

    // ================================================================== infrastructure: wizard navigation

    private String wizardStep() {
        if (orgPage.isOpen()) {
            return "Organization Details";
        }
        if (pocPage.isOpen()) {
            return "Points of Contact";
        }
        return driver.findElements(By.xpath("//button[normalize-space()='Confirm & Continue']")).isEmpty() ? "other" : "Review of Information";
    }

    private String waitStep() {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(10)).until(d -> !wizardStep().equals("other"));
        } catch (org.openqa.selenium.TimeoutException ignored) {
            // report whatever is there
        }
        return wizardStep();
    }

    /** Opens the wizard wherever the client left it. */
    private String openWizard() {
        if (!driver.getCurrentUrl().contains("/portal/onboarding")) {
            if (!driver.getCurrentUrl().contains("/portal")) {
                driver.get(base() + "/portal");
            }
            enterWizardFromHome();
        }
        pocPage.dismissContinueSetupPrompt();
        return waitStep();
    }

    /**
     * From the portal home: 'Start' (new client) / 'Continue' (wizard used). A client with UNFINISHED onboarding first gets a
     * 'Finish setting up your company' dialog ('Continue Setup') that covers the page, so a click on Start / Continue is intercepted:
     * the dialog is used when it is there. Nothing is changed, the wizard is only opened.
     */
    private void enterWizardFromHome() {
        By prompt = By.xpath("//button[normalize-space()='Continue Setup']");
        By go = By.xpath("//button[normalize-space()='Start' or normalize-space()='Continue']");
        for (int attempt = 0; attempt < 3 && !driver.getCurrentUrl().contains("/portal/onboarding"); attempt++) {
            new WebDriverWait(driver, Duration.ofSeconds(20))
                    .ignoring(ElementClickInterceptedException.class, StaleElementReferenceException.class).until(d -> {
                        List<WebElement> p = d.findElements(prompt);
                        if (!p.isEmpty() && p.get(0).isDisplayed()) {
                            p.get(0).click();
                            return true;
                        }
                        List<WebElement> g = d.findElements(go);
                        if (!g.isEmpty() && g.get(0).isDisplayed() && g.get(0).isEnabled()) {
                            g.get(0).click(); // intercepted when the dialog appears just now: the next poll uses the dialog
                            return true;
                        }
                        return false;
                    });
            try {
                new WebDriverWait(driver, Duration.ofSeconds(6)).until(ExpectedConditions.urlContains("/portal/onboarding"));
            } catch (org.openqa.selenium.TimeoutException dialogOnlyClosed) {
                // the dialog only closed: click Start / Continue on the next round
            }
        }
        if (!driver.getCurrentUrl().contains("/portal/onboarding")) {
            throw new AssertionError("Could not open the onboarding wizard from the portal home (at " + driver.getCurrentUrl() + ")");
        }
    }

    /** Opens the wizard and walks Back until Organization Details is open. */
    private void gotoOrgStep() {
        openWizard();
        for (int guard = 0; guard < 5 && !orgPage.isOpen(); guard++) {
            if (pocPage.clickBack()) {
                waitStep();
            } else if (!pocPage.dismissContinueSetupPrompt()) {
                break; // neither a Back button nor the 'Continue Setup' prompt: this client cannot go back
            }
        }
        if (!orgPage.isOpen()) {
            driver.get(base() + "/portal/onboarding");
            pocPage.dismissContinueSetupPrompt();
            waitStep();
        }
        if (!orgPage.isOpen()) {
            throw new SkipException("Could not reach Organization Details (the wizard is on '" + wizardStep() + "'; a client that already "
                    + "reached MSA signing cannot go back)");
        }
    }

    private void ensureDocuments() {
        String cin = fileName(data.cinDocPath);
        String gst = fileName(data.taxDocPath);
        if (!orgPage.isFileShown(cin)) {
            orgPage.uploadCin(data.cinDocPath, cin);
        }
        if (!orgPage.isFileShown(gst)) {
            orgPage.uploadTaxId(data.taxDocPath, gst);
        }
    }

    private boolean pocMatchesBaseline() {
        for (String prefix : new String[] { "business", "finance", "hr" }) {
            ClientData.Contact c = contactOf(prefix);
            if (!c.name.equals(pocPage.read(prefix, "name")) || !c.designation.equals(pocPage.read(prefix, "designation"))
                    || !c.email.equals(pocPage.read(prefix, "email")) || !c.phone.equals(pocPage.read(prefix, "contact_no"))) {
                return false;
            }
        }
        return true;
    }

    private ClientData.Contact contactOf(String prefix) {
        return prefix.equals("business") ? data.business : prefix.equals("finance") ? data.finance : data.hr;
    }

    private Map<String, String> orgValues() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String f : ORG_FIELDS) {
            m.put(f, orgPage.fieldValue(f));
        }
        return m;
    }

    private static String fileName(String path) {
        return Paths.get(path).getFileName().toString();
    }

    private static String abbreviate(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "... (" + s.length() + " chars)" : s;
    }

    private static void cmp(List<String> bad, String field, String expected, String actual) {
        if (!java.util.Objects.equals(expected == null ? null : expected.trim(), actual == null ? null : actual.trim())
                && !(expected != null && actual != null && expected.equalsIgnoreCase(actual) && field.contains("mail"))) {
            bad.add(field + ": expected '" + expected + "' but was '" + actual + "'");
        }
    }

    private static void contactCmp(List<String> bad, String name, ClientData.Contact c, Map<String, String> shown) {
        cmp(bad, name + " POC name", c.name, shown.get("Name"));
        cmp(bad, name + " POC designation", c.designation, shown.get("Designation"));
        cmp(bad, name + " POC email", c.email, shown.get("Email"));
        cmp(bad, name + " POC phone", c.phone, shown.get("Contact Number"));
    }

    private void assertNoAlert() {
        try {
            String text = driver.switchTo().alert().getText();
            driver.switchTo().alert().accept();
            Assert.fail("A JavaScript alert ran from user-supplied text (possible XSS): '" + text + "'");
        } catch (NoAlertPresentException ok) {
            // nothing executed
        }
    }

    // ================================================================== L - login (signed out; every case is a rejected sign-in, nothing is created)
    // To avoid locking the shared admin / EOR accounts a wrong password is NEVER sent for them: only non-existent yopmail users
    // here, and one wrong password for the edge client itself (L-09, near the end).

    private static String ghost() {
        return "no.such.user." + TestDataGenerator.phone() + "@yopmail.com";
    }

    @DataProvider(name = "badLogins")
    public Object[][] badLogins() {
        return new Object[][] {
            { "L-01", "Both fields empty", "Email and password empty, Sign in clicked", null, null },
            { "L-02", "Password empty", "Valid-format yopmail address, password empty", ghost(), null },
            { "L-03", "Email empty", "Email empty, password entered", null, WRONG },
            { "L-04", "Email without @", "Email = plainaddress, password entered", "plainaddress", WRONG },
            { "L-05", "Non-existent user", "Unknown yopmail address with a password", ghost(), WRONG },
            { "L-06", "SQL-injection style input", "Email = admin' OR '1'='1, password = ' OR '1'='1", "admin' OR '1'='1", "' OR '1'='1" },
            { "L-07", "Very long email (300+ chars)", "Email = 300 x 'a' + @yopmail.com, password entered", "a".repeat(300) + "@yopmail.com", WRONG },
            { "L-08", "Whitespace-only password", "Unknown yopmail address, password = 3 spaces", ghost(), "   " },
        };
    }

    // L-01..L-08 - Login (signed out) with bad input: both fields empty, password empty, e-mail empty, e-mail without @, unknown user, SQL-injection style text,
    // 300-character e-mail, whitespace-only password. Expected: the sign-in is refused, the user stays on the login page and a message explains why. Nothing is created.
    @Test(priority = 100, dataProvider = "badLogins")
    public void invalidLoginIsRejected(String id, String title, String input, String user, String pass) {
        onlyIfSelected(id);
        mustReject(id, G_LOGIN, title, input, "Sign-in is refused, the user stays on the login page, and a message explains the problem",
                data, new String[0], () -> {
                    signedOut();
                    return rejectedLogin(user, pass);
                });
    }

    /** One sign-in attempt; a rate-limit answer that outlasts the wait budget skips the case instead of counting as a 'rejection'. */
    private Outcome rejectedLogin(String user, String pass) {
        Outcome o = loginPage.attempt(user, pass);
        List<String> all = new ArrayList<>(o.messages);
        all.addAll(o.toasts);
        if (!o.accepted && LoginPage.isRateLimited(all)) {
            throw new SkipException("Sign-in is rate limited by the application ('Too many sign-in attempts'): run this case again in a few minutes");
        }
        return o;
    }

    // L-20 - The password box on the login page hides what is typed (input type = password).
    @Test(priority = 110)
    public void passwordFieldIsMasked() {
        runCase("L-20", G_LOGIN, "Password field is masked", "Text typed into the password field", "Input type is 'password' (characters hidden)",
                Who.NONE, () -> {
                    signedOut();
                    loginPage.typeIntoPasswordOnly("Visible#Check1");
                    String type = loginPage.passwordFieldType();
                    Assert.assertEquals(type, "password", "Password field type is '" + type + "', so the typed password is readable on screen.");
                    return "Password input type = " + type;
                });
    }

    // ================================================================== B1 - direct URLs without a session

    @DataProvider(name = "protectedUrls")
    public Object[][] protectedUrls() {
        return new Object[][] {
            { "B-01", "/clients" }, { "B-02", "/clients/register" }, { "B-03", "/contracts" }, { "B-04", "/portal" },
            { "B-05", "/portal/onboarding" }, { "B-06", "/documents/management" }, { "B-07", "/candidates" }, { "B-08", "/appraisal" },
        };
    }

    // B-01..B-08 - A protected page opened by URL with NO session (/clients, /clients/register, /contracts, /portal, /portal/onboarding, /documents/management,
    // /candidates, /appraisal). Expected: the login page, never the page content.
    @Test(priority = 200, dataProvider = "protectedUrls")
    public void protectedUrlRequiresLogin(String id, String path) {
        runCase(id, G_URL, "Opening " + path + " without a session shows the login page",
                "Fresh browser session (no cookies / storage), open " + base() + path,
                "The user is sent to the login page and no protected content is shown", Who.NONE, () -> {
                    signedOut();
                    Landing l = PageProbe.open(driver, base() + path, 10);
                    Assert.assertFalse(l.adminContent, "Protected admin content is visible without a session. " + l.describe());
                    Assert.assertTrue(l.loginForm && l.path.contains("/login"), "Not redirected to the login page. " + l.describe());
                    return l.describe();
                });
    }

    // B-09 - An unknown URL without a session shows the login page or a readable "not found" message, never a blank page.
    @Test(priority = 210)
    public void unknownRouteIsNotBlank() {
        runCase("B-09", G_URL, "Unknown URL shows a login or a friendly 'not found' page, not a blank page",
                "Fresh session, open " + base() + "/no-such-page-xyz", "Login page or a readable not-found message; never a blank page or raw error",
                Who.NONE, () -> {
                    signedOut();
                    Landing l = PageProbe.open(driver, base() + "/no-such-page-xyz", 10);
                    Assert.assertFalse(l.blank, "Blank page for an unknown route. " + l.describe());
                    Assert.assertTrue(l.loginForm || l.denied, "Unknown route shows neither the login page nor a not-found message. " + l.describe());
                    return l.describe();
                });
    }

    // ================================================================== R - Register Client form (admin side)
    // A rejected case creates nothing. R-31 and R-50..R-52 would create an EXTRA client if the app accepts the input: they only
    // run when named in -Dcases. R-60 registers THE edge client.

    private void openRegisterForm() {
        clientsPage.open();
        clientsPage.clickRegisterClient();
        registerPage.waitUntilOpen();
    }

    private Outcome registerAttempt(ClientData d) {
        openRegisterForm();
        registerPage.fillPartial(d);
        return FormFeedback.submit(driver, 8, registerPage::submit, () -> !registerPage.isOpen());
    }

    /** An existing yopmail client: the fixture of the duplicate checks and of the Clients list cases. */
    private ClientsPage.Row existingClient() {
        if (ref == null) {
            adminSession();
            clientsPage.open();
            ref = clientsPage.firstYopmailClient();
        }
        return ref;
    }

    @DataProvider(name = "rejectedRegistrations")
    public Object[][] rejectedRegistrations() {
        String[] none = {};
        return new Object[][] {
            // id, title, input description, mutation, keywords that the message must contain
            { "R-01", "All fields empty", "Nothing entered, Register Client clicked",
                (Consumer<ClientData>) d -> { d.legalName = null; d.clientType = null; d.country = null; d.pocName = null; d.email = null; d.mobile = null; },
                new String[] { "legal", "client type", "country", "contact", "email", "mobile" } },
            { "R-02", "Legal entity name empty", "Valid data, Legal Entity Name left empty",
                (Consumer<ClientData>) d -> d.legalName = null, new String[] { "legal" } },
            { "R-03", "Client type not selected", "Valid data, Client Type not selected",
                (Consumer<ClientData>) d -> d.clientType = null, new String[] { "client type" } },
            { "R-04", "Country not selected", "Valid data, Country not selected",
                (Consumer<ClientData>) d -> d.country = null, new String[] { "country" } },
            { "R-05", "Point of contact name empty", "Valid data, POC name left empty",
                (Consumer<ClientData>) d -> d.pocName = null, new String[] { "contact" } },
            { "R-06", "Email empty", "Valid data, Email left empty",
                (Consumer<ClientData>) d -> d.email = null, new String[] { "email" } },
            { "R-07", "Contact number empty", "Valid data, Contact Number left empty",
                (Consumer<ClientData>) d -> d.mobile = null, new String[] { "mobile" } },
            { "R-08", "Legal entity name whitespace only", "Legal Entity Name = 3 spaces",
                (Consumer<ClientData>) d -> d.legalName = "   ", new String[] { "legal" } },
            { "R-09", "Email without @", "Email = plainaddress",
                (Consumer<ClientData>) d -> d.email = "plainaddress", new String[] { "email" } },
            { "R-10", "Email without domain", "Email = user@",
                (Consumer<ClientData>) d -> d.email = "user@", new String[] { "email" } },
            { "R-11", "Email without local part", "Email = @yopmail.com",
                (Consumer<ClientData>) d -> d.email = "@yopmail.com", new String[] { "email" } },
            { "R-12", "Email with two @", "Email = user@@yopmail.com",
                (Consumer<ClientData>) d -> d.email = "user@@yopmail.com", new String[] { "email" } },
            { "R-13", "Email containing a space", "Email = 'us er@yopmail.com'",
                (Consumer<ClientData>) d -> d.email = "us er@yopmail.com", new String[] { "email" } },
            { "R-14", "Email longer than 254 characters", "Email = 250-char local part + @yopmail.com",
                (Consumer<ClientData>) d -> d.email = "a".repeat(250) + "@yopmail.com", new String[] { "email" } },
            { "R-15", "Contact number with letters", "Contact Number = abcdefghij (field is expected to refuse letters)",
                (Consumer<ClientData>) d -> d.mobile = "abcdefghij", new String[] { "mobile" } },
            { "R-16", "Contact number too short (5 digits)", "Contact Number = 12345",
                (Consumer<ClientData>) d -> d.mobile = "12345", new String[] { "10 digits" } },
            { "R-17", "Contact number too short (9 digits)", "Contact Number = 987654321",
                (Consumer<ClientData>) d -> d.mobile = "987654321", new String[] { "10 digits" } },
            { "R-18", "Contact number with symbols", "Contact Number = +91-98765 (symbols are expected to be refused)",
                (Consumer<ClientData>) d -> d.mobile = "+91-98765", new String[] { "mobile" } },
            { "R-19", "Legal entity name 300 characters", "Legal Entity Name = 300 x 'A' (max length unknown; 300 is far beyond a sane limit)",
                (Consumer<ClientData>) d -> d.legalName = "A".repeat(300), none },
            { "R-20", "Point of contact name whitespace only", "POC name = 3 spaces (client-side check was seen to let this pass)",
                (Consumer<ClientData>) d -> d.pocName = "   ", new String[] { "contact" } },
        };
    }

    // R-01..R-20 - Register Client form with bad input: empty fields, whitespace-only names, bad e-mails (no @, no domain, two @, space, 254+ characters), bad contact
    // numbers (letters, too short, symbols), 300-character legal name. Expected: not submitted, no client created, a clear message. A wrongly accepted case is a bug.
    @Test(priority = 300, dataProvider = "rejectedRegistrations")
    public void invalidRegistrationIsRejected(String id, String title, String input, Consumer<ClientData> mutate, String[] keywords) {
        onlyIfSelected(id);
        prepare(id, G_REG, title, this::adminSession);
        ClientData d = newData();
        mutate.accept(d);
        mustReject(id, G_REG, title, input,
                "Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown",
                d, keywords, () -> registerAttempt(d));
    }

    @DataProvider(name = "duplicates")
    public Object[][] duplicates() {
        return new Object[][] {
            { "R-30", "Duplicate legal entity name", "Legal Entity Name = name of an existing client (same case)", "name" },
            { "R-31", "Duplicate legal entity name, different case + trailing space", "Existing client name in UPPER CASE + trailing space", "name" },
            { "R-32", "Duplicate email", "Email = e-mail of an existing client", "email" },
            { "R-33", "Duplicate email, different case", "Email = existing client e-mail in UPPER CASE", "email" },
        };
    }

    // R-30..R-33 - Register a client whose legal name or e-mail already exists (same case, and UPPER CASE + trailing space). Expected: refused with a duplicate message.
    // R-31 is skipped in a full run (it creates an EXTRA client if the app accepts it, which is the known bug): run it on purpose with -Dcases=R-31.
    @Test(priority = 310, dataProvider = "duplicates")
    public void duplicateRegistrationIsRejected(String id, String title, String input, String keyword) {
        onlyIfSelected(id);
        skipUnlessNamed(id, G_REG, title);
        prepare(id, G_REG, title, () -> {
            adminSession();
            existingClient();
        });
        ClientsPage.Row existing = existingClient();
        ClientData d = newData();
        switch (id) {
            case "R-30" -> d.legalName = existing.company;
            case "R-31" -> d.legalName = existing.company.toUpperCase() + " ";
            case "R-32" -> d.email = existing.email;
            default -> d.email = existing.email.toUpperCase();
        }
        mustReject(id, G_REG, title, input + " (existing: " + existing.company + " / " + existing.email + ")",
                "Registration is refused with a duplicate message; no second client is created", d, new String[] { keyword }, () -> registerAttempt(d));
    }

    // R-40 - The Contact Number box keeps at most 10 characters (typing 11 digits; nothing is submitted).
    @Test(priority = 320)
    public void mobileFieldCapsAtTenCharacters() {
        onlyIfSelected("R-40");
        prepare("R-40", G_REG, "Contact number field limits length", this::adminSession);
        ClientData d = newData();
        TestReporter rep = TestReporter.start("R-40", G_REG, "Contact number field limits length",
                "Register client form: field-level length cap", "Contact Number typed = 98765432101 (11 digits); nothing is submitted",
                "Field keeps at most 10 characters", driver, d);
        rep.phase("r_40", "Contact number length cap", "Field keeps at most 10 characters", () -> {
            openRegisterForm();
            ClientData only = new ClientData();
            only.mobile = "98765432101";
            registerPage.fillPartial(only);
            String v = registerPage.mobileValue();
            Assert.assertTrue(v.length() <= 10, "Contact Number field accepted " + v.length() + " characters: '" + v + "'");
            return "Field value is '" + v + "' (" + v.length() + " characters)";
        });
        assertNoDefects(rep);
    }

    @DataProvider(name = "specialNames")
    public Object[][] specialNames() {
        return new Object[][] {
            { "R-50", "HTML/script in legal entity name", "<b>Bold</b> <script>alert(1)</script> Ltd" },
            { "R-51", "Unicode and emoji in legal entity name", "Ünïcode 株式会社 😀 Ltd" },
            { "R-52", "SQL-like text in legal entity name", "Robert'); DROP TABLE clients;-- Ltd" },
        };
    }

    // R-50..R-52 - Special text in the legal name: HTML + script, Unicode + emoji, SQL-like text. Expected: rejected, or stored and shown literally with no script running.
    // Skipped in a full run (it creates an EXTRA client if accepted): run on purpose with -Dcases=R-50.
    /**
     * Names are free text, so these may be legitimately accepted: the check is that the text is stored and shown literally and that
     * no script runs. If accepted, ONE extra client is created - therefore only when named in -Dcases.
     */
    @Test(priority = 330, dataProvider = "specialNames")
    public void specialCharactersAreHandledSafely(String id, String title, String namePart) {
        onlyIfSelected(id);
        skipUnlessNamed(id, G_REG, title);
        prepare(id, G_REG, title, this::adminSession);
        ClientData d = newData();
        d.legalName = namePart + " " + d.runId;
        TestReporter rep = TestReporter.start(id, G_REG, title, "Register client form: special characters in free text",
                "Legal Entity Name = " + d.legalName,
                "Either rejected with a message, or accepted and shown literally in the Clients list with no script executed", driver, d);
        rep.phase(slug(id), title, "Rejected with a message, or stored and displayed literally; no script execution", () -> {
            Outcome o = registerAttempt(d);
            assertNoAlert();
            if (!o.accepted) {
                if (!o.hasFeedback()) {
                    rep.defect("Input rejected silently: no validation message", "A visible message", o.describe());
                }
                return "Rejected. " + o.describe();
            }
            ClientsPage.Row row = clientsPage.findClient(d.legalName);
            assertNoAlert();
            Assert.assertEquals(row.company, d.legalName, "Stored/displayed legal name differs from what was entered");
            rawErrorLeak(rep, o);
            return "Accepted and displayed literally: '" + row.company + "'. One extra client was created (" + row.status + ").";
        });
        assertNoDefects(rep);
    }

    // R-60 - A double click on "Register Client" must create exactly one client. THIS REGISTERS THE ONE EDGE CLIENT of the run (2-client rule); its welcome mail is read next.
    // Expected: one row in the Clients list and no raw database error shown (known bug: a raw error is shown).
    /**
     * R-60 REGISTERS THE EDGE CLIENT: a double click on 'Register Client' must still create exactly one client. The client
     * exists whatever the verdict (a defect here does not stop the lifecycle).
     */
    @Test(priority = 400)
    public void doubleClickRegistersTheEdgeClient() {
        onlyIfSelected("R-60");
        String title = "Double-click on Register Client creates one client";
        if (existingGiven || registered) {
            String why = existingGiven ? "Existing client (-Dedge.client.email): nothing is registered"
                    : "The edge client was already registered by a set-up step (single click)";
            skipRow("R-60", G_REG, title, why);
            throw new SkipException(why);
        }
        prepare("R-60", G_REG, title, this::adminSession);
        TestReporter rep = TestReporter.start("R-60", G_REG, title,
                "Register client form: duplicate submission. This registration IS the ONE edge client of the run (2-client rule)",
                "Valid data (" + data.legalName + "); Register Client clicked twice in the same instant", "Exactly one client is created", driver, data);
        rep.phase("r_60", "Double submit", "Exactly one row for the new client in the Clients list", () -> {
            openRegisterForm();
            for (int attempt = 1; attempt <= 5; attempt++) {
                registerPage.fillPartial(data);
                ToastWatcher.clear(driver);
                registerPage.submitTwiceImmediately();
                if (ToastWatcher.waitFor(driver, "registered", 10) != null
                        || ToastWatcher.all(driver).stream().noneMatch(ClientNames::isDuplicateMessage)) {
                    break;
                }
                // the anime was not in testdata/existing-clients.txt but the application says the name or an e-mail exists: list it, use the next anime
                ClientNames.markUsed(data.legalName, "the application says this name or e-mail already exists");
                TestDataGenerator.applyPick(data, ClientNames.pick());
            }
            long toastCount = ToastWatcher.all(driver).stream().filter(t -> t.toLowerCase().contains("registered")).count();
            clientsPage.open();
            int rows = clientsPage.countRows(data.legalName);
            List<String> toasts = ToastWatcher.all(driver);
            registered = rows >= 1; // the client exists even when this case finds a defect
            if (registered) {
                saveClientData();
            }
            Assert.assertEquals(rows, 1, "Double-click created " + rows + " clients named '" + data.legalName + "' (toasts: " + toasts + ")");
            rawErrorLeak(rep, new Outcome(true, List.of(), toasts));
            return "One client created (success toasts seen: " + toastCount + ", rows in list: " + rows + ")";
        });
        assertNoDefects(rep);
    }

    // ================================================================== P - first login of the edge client (needs a client that never logged in)

    // P-00 (set-up) - Reads the welcome e-mail of the edge client in Yopmail (temporary password). Registers the client first only if R-60 did not.
    @Test(priority = 410)
    public void p00_welcomeEmail() {
        onlyIfSelected("P-00");
        if (!reuse) {
            ensureMailRead(); // writes the P-00 case
        }
    }

    // P-01 - Sign in as the new client with a WRONG temporary password (single attempt). Expected: refused with a message.
    @Test(priority = 420)
    public void p01_wrongTemporaryPassword() {
        onlyIfSelected("P-01");
        String title = "Login with a wrong temporary password";
        if (reuse) {
            String why = "Existing client (-Dedge.client.email): needs a client that never logged in";
            skipRow("P-01", G_PWD, title, why);
            throw new SkipException(why);
        }
        prepare("P-01", G_PWD, title, this::ensureMailRead);
        mustReject("P-01", G_PWD, title, "The edge client's e-mail + a wrong password (single attempt)",
                "Sign-in refused with a message; the user stays on the login page", data, new String[0], () -> {
                    signedOut();
                    return rejectedLogin(data.email, WRONG);
                });
    }

    // P-02 (set-up) - First login with the temporary password. Expected: the "Set a new password" dialog appears.
    @Test(priority = 430)
    public void p02_firstLoginShowsPasswordDialog() {
        onlyIfSelected("P-02");
        if (!reuse) {
            ensureDialog(); // writes the P-02 case
        }
    }

    @DataProvider(name = "passwordRules")
    public Object[][] passwordRules() {
        if (reuse) {
            return new Object[0][];
        }
        String ok = ConfigManager.clientPassword();
        return new Object[][] {
            // id, title, input, current ("TEMP" = the real temporary password), new, confirm
            { "P-10", "All password fields empty", "Nothing entered", null, null, null },
            { "P-11", "Current password empty", "Valid new password + confirmation, current empty", null, ok, ok },
            { "P-12", "Wrong current password", "Wrong current password with a valid new password (single attempt)", WRONG + "1", ok, ok },
            { "P-13", "New password too short", "New = Ab1! (4 chars)", "TEMP", "Ab1!", "Ab1!" },
            { "P-14", "Confirmation does not match", "New and confirmation differ", "TEMP", ok, ok + "x" },
            { "P-15", "Confirmation empty", "New password entered, confirmation empty", "TEMP", ok, null },
            { "P-16", "New password whitespace only", "New = 8 spaces", "TEMP", "        ", "        " },
            { "P-17", "New password equals the temporary password", "New = the temporary password", "TEMP", "TEMP", "TEMP" },
            // the dialog states the policy: 8+ characters with an uppercase, lowercase, number, and symbol
            { "P-18", "New password digits only", "New = 12345678 (policy: upper, lower, number, symbol)", "TEMP", "12345678", "12345678" },
            { "P-19", "New password without a digit", "New = Abcdefgh! (policy requires a number)", "TEMP", "Abcdefgh!", "Abcdefgh!" },
            { "P-20", "New password without a symbol", "New = Abcdefg12 (policy requires a symbol)", "TEMP", "Abcdefg12", "Abcdefg12" },
            { "P-21", "New password without an uppercase letter", "New = abcdefg1! (policy requires an uppercase letter)", "TEMP", "abcdefg1!", "abcdefg1!" },
            { "P-22", "New password without a lowercase letter", "New = ABCDEFG1! (policy requires a lowercase letter)", "TEMP", "ABCDEFG1!", "ABCDEFG1!" },
            // last on purpose: if the app accepts it the password is changed and the dialog is gone
            { "P-23", "New password 300 characters (no maximum length)", "New = Aa1! + 296 x 'x'", "TEMP", "Aa1!" + "x".repeat(296), "Aa1!" + "x".repeat(296) },
        };
    }

    // P-10..P-23 - The "Set a new password" dialog with bad input: all empty, current password empty / wrong, too short, confirmation mismatch or empty, whitespace only,
    // same as the temporary password, no digit / symbol / upper case / lower case, 300 characters. Expected: refused, the dialog stays open.
    // P-23 is last on purpose: if the app accepts it the password is changed (known bug: no maximum length).
    @Test(priority = 440, dataProvider = "passwordRules")
    public void weakOrInvalidPasswordIsRejected(String id, String title, String input, String cur, String neu, String conf) {
        onlyIfSelected(id);
        prepare(id, G_PWD, title, this::ensureDialog);
        String current = "TEMP".equals(cur) ? tempPassword : cur;
        String newPw = "TEMP".equals(neu) ? tempPassword : neu;
        String confirm = "TEMP".equals(conf) ? tempPassword : conf;
        mustReject(id, G_PWD, title, input, "Password change refused with a message; the dialog stays open and the password is unchanged",
                data, new String[0], () -> {
                    pwdPage.clearAll();
                    Outcome o = pwdPage.attempt(current, newPw, confirm);
                    if (o.accepted) {
                        passwordChanged = true;
                        activePassword = newPw;
                        data.password = newPw;
                        SecretMasker.register(newPw);
                    }
                    return o;
                });
    }

    // P-30 (set-up) - Sets the permanent password and signs in. Expected: the portal home opens. (If P-23 was accepted, that long password is the one used.)
    @Test(priority = 450)
    public void p30_setPermanentPassword() {
        onlyIfSelected("P-30");
        ensurePassword(); // writes the P-30 case
    }

    // ================================================================== B2 / B3 - the edge client on admin URLs, session and reload

    @DataProvider(name = "adminUrls")
    public Object[][] adminUrls() {
        return new Object[][] {
            { "B-10", "/clients" }, { "B-11", "/clients/register" }, { "B-12", "/contracts" }, { "B-13", "/documents/templates" },
            { "B-14", "/documents/management" }, { "B-15", "/candidates" }, { "B-16", "/appraisal" }, { "B-17", "/financials/eor/attendance" },
        };
    }

    // B-10..B-17 - The signed-in client opens admin URLs (/clients, /clients/register, /contracts, /documents/templates, /documents/management, /candidates, /appraisal,
    // /financials/eor/attendance). Expected: redirected away or told "no access"; no admin content; no blank page (known bug: /candidates stays blank).
    @Test(priority = 500, dataProvider = "adminUrls")
    public void clientCannotOpenAdminPage(String id, String path) {
        runCase(id, G_ROLE, "Client cannot open admin page " + path,
                "Signed in as the edge client (" + SecretMasker.maskEmail(data.email) + " / " + SecretMasker.reveal(activePassword) + "), open " + base() + path,
                "The client is redirected away or told access is denied; no admin content is shown", Who.CLIENT, () -> {
                    Landing l = PageProbe.open(driver, base() + path, 15);
                    Assert.assertFalse(l.adminContent, "A CLIENT can see admin console content. " + l.describe());
                    Assert.assertFalse(l.blank, "The client is left on an empty / stuck-loading page (no redirect, no message). " + l.describe());
                    boolean blocked = !l.path.startsWith(path) || l.denied;
                    Assert.assertTrue(blocked, "Client stayed on the admin URL with no redirect and no access-denied message. " + l.describe());
                    return l.describe();
                });
    }

    // B-18 - The client opens the root URL "/": must land in the client portal, not in the admin dashboard.
    @Test(priority = 510)
    public void clientOnAdminRootSeesPortal() {
        runCase("B-18", G_ROLE, "Client opening the root URL / lands in the client portal",
                "Signed in as the edge client, open " + base() + "/", "Client portal home (/portal), not the admin dashboard", Who.CLIENT, () -> {
                    Landing l = PageProbe.open(driver, base() + "/", 10);
                    Assert.assertFalse(l.adminContent, "A CLIENT can see admin console content at /. " + l.describe());
                    return l.describe();
                });
    }

    // B-19 - A signed-in client opens /login: expected a redirect to the portal instead of the login form (known bug: the form is shown again).
    @Test(priority = 520)
    public void loginPageWhenAlreadySignedInClient() {
        runCase("B-19", G_ROLE, "Signed-in client opening /login is sent back to the portal",
                "Signed in as the edge client, open " + base() + "/login", "Redirect to the portal (a signed-in user is not shown the login form)", Who.CLIENT, () -> {
                    Landing l = PageProbe.open(driver, base() + "/login", 8);
                    Assert.assertFalse(l.loginForm, "A signed-in client is shown the login form again. " + l.describe());
                    return l.describe();
                });
    }

    // B-20 - An unknown URL as a signed-in client shows a readable page, never a blank one.
    @Test(priority = 530)
    public void unknownRouteAsClient() {
        runCase("B-20", G_ROLE, "Unknown URL as a signed-in client shows a readable page",
                "Signed in as the edge client, open " + base() + "/no-such-page-xyz", "Not-found message or redirect to the portal; never a blank page",
                Who.CLIENT, () -> {
                    Landing l = PageProbe.open(driver, base() + "/no-such-page-xyz", 10);
                    Assert.assertFalse(l.blank, "Blank page for an unknown route (signed in). " + l.describe());
                    return l.describe();
                });
    }

    // B-21 - A full page reload of /portal keeps the client signed in.
    @Test(priority = 540)
    public void clientSessionSurvivesReload() {
        runCase("B-21", G_SESSION, "Client session survives a reload of the portal URL",
                "Signed in as the edge client, open " + base() + "/portal again (full page load)", "Still signed in on the portal; no login prompt", Who.CLIENT, () -> {
                    Landing l = PageProbe.open(driver, base() + "/portal", 10);
                    Assert.assertFalse(l.loginForm, "Reloading /portal logged the client out. " + l.describe());
                    return l.describe();
                });
    }

    // B-26 - F5 on the client portal keeps the client signed in and shows the page.
    @Test(priority = 550)
    public void refreshOnPortalKeepsClientSignedIn() {
        runCase("B-26", G_SESSION, "Refresh (F5) on the client portal keeps the client signed in",
                "Signed in as the edge client on /portal, press F5", "The portal home is shown again, no login prompt", Who.CLIENT, () -> {
                    driver.navigate().refresh();
                    Landing l = PageProbe.settle(driver, "F5 on /portal", 10);
                    Assert.assertFalse(l.loginForm, "F5 logged the client out. " + l.describe());
                    Assert.assertFalse(l.blank, "F5 left a blank page. " + l.describe());
                    return l.describe();
                });
    }

    // ================================================================== O - Organization Details (wizard step 1)

    // O-00 (set-up) - Opens Organization Details and enters valid data plus the CIN and GST documents: the valid baseline for the single-field cases below.
    @Test(priority = 600)
    public void o00_organizationBaseline() {
        onlyIfSelected("O-00");
        ensureOrgStep(); // writes the O-00 case
    }

    @DataProvider(name = "orgFields")
    public Object[][] orgFields() {
        String y = "years_in_industry", w = "website_link", d = "product_or_service_description", p = "registered_address.pincode",
                a = "registered_address.address", e = "email", m = "mobile_number", c = "company_registration_number", g = "gst_number";
        return new Object[][] {
            { "O-01", "Years in industry empty", y, "" }, { "O-02", "Years in industry negative (-5)", y, "-5" },
            { "O-03", "Years in industry text (abc)", y, "abc" }, { "O-04", "Years in industry 1000", y, "1000" },
            { "O-05", "Website empty", w, "" }, { "O-06", "Website is not a URL (not a url)", w, "not a url" },
            { "O-07", "Website with script (javascript:alert(1))", w, "javascript:alert(1)" },
            { "O-08", "Description empty", d, "" },
            { "O-09", "Pincode empty", p, "" }, { "O-10", "Pincode 5 digits", p, "12345" }, { "O-11", "Pincode 7 digits", p, "1234567" },
            { "O-12", "Pincode letters", p, "abcdef" }, { "O-13", "Pincode 000000", p, "000000" },
            { "O-14", "Address empty", a, "" },
            { "O-15", "Registered email empty", e, "" }, { "O-16", "Registered email invalid (plainaddress)", e, "plainaddress" },
            { "O-17", "Registered mobile empty", m, "" }, { "O-18", "Registered mobile too short (12345)", m, "12345" },
            { "O-19", "Registered mobile letters", m, "abcdefghij" },
            { "O-20", "CIN empty", c, "" }, { "O-21", "CIN invalid format (INVALID123)", c, "INVALID123" },
            { "O-22", "CIN too long (40 chars)", c, "U12345MH2020PTC123456".concat("X".repeat(19)) },
            { "O-23", "GSTIN empty", g, "" }, { "O-24", "GSTIN invalid format (INVALIDGST)", g, "INVALIDGST" },
            { "O-25", "GSTIN too long (30 chars)", g, "27ABCDE1234F1Z5".concat("9".repeat(15)) },
        };
    }

    // O-01..O-25 - Organization Details with ONE bad field on an otherwise valid form: years in industry (empty, -5, abc, 1000), website (empty, not a URL, javascript:),
    // description empty, pincode (empty, 5 digits, 7 digits, letters, 000000), address empty, registered e-mail / mobile (read-only fields: skipped), CIN and GSTIN (empty,
    // bad format, too long). Expected: Continue is refused with a message and the wizard stays on step 1. Many of these are known bugs (no format validation).
    @Test(priority = 610, dataProvider = "orgFields")
    public void invalidOrganizationFieldIsRejected(String id, String title, String field, String bad) {
        onlyIfSelected(id);
        prepare(id, G_ORG, title, this::ensureOrgStep);
        if (!orgPage.isEditable(field)) {
            String why = "Field '" + field + "' is read-only (pre-filled from registration): nothing to type";
            skipRow(id, G_ORG, title, why);
            throw new SkipException(why);
        }
        String valid = validOrgValue(field);
        lastOutcome = null;
        // a rejected website must say so: the empty website already gets 'Website is required'
        String[] keywords = field.equals("website_link") && !bad.isEmpty() ? new String[] { "website" } : new String[0];
        try {
            mustReject(id, G_ORG, title, "Valid form, only '" + field + "' set to '" + abbreviate(bad) + "'",
                    "Continue is refused with a message; the wizard stays on Organization Details", data, keywords, () -> {
                        orgPage.setField(field, bad);
                        lastOutcome = orgPage.continueAttempt();
                        return lastOutcome;
                    });
        } finally {
            // restore AFTER the phase screenshot so the evidence still shows the rejected state; when the app wrongly accepted the
            // value the wizard moved on and the next case walks back (ensureOrgStep)
            if (!(lastOutcome != null && lastOutcome.accepted) && orgPage.isOpen()) {
                try {
                    restoreOrg(field, valid);
                } catch (RuntimeException e) {
                    orgPage.ensureValues(data);
                }
            }
        }
    }

    private String validOrgValue(String field) {
        return switch (field) {
            case "years_in_industry" -> data.yearsInIndustry;
            case "website_link" -> data.website;
            case "product_or_service_description" -> data.description;
            case "registered_address.pincode" -> data.pincode;
            case "registered_address.address" -> data.address;
            case "email" -> data.email;
            case "mobile_number" -> data.mobile;
            case "company_registration_number" -> data.cin;
            default -> data.gst;
        };
    }

    private void restoreOrg(String field, String valid) {
        if (field.equals("registered_address.pincode")) {
            orgPage.restorePincode(valid, data.city);
        } else {
            orgPage.setField(field, valid);
        }
    }

    // ================================================================== D - documents (CIN / GST uploads on step 1)

    @DataProvider(name = "badDocuments")
    public Object[][] badDocuments() {
        return new Object[][] {
            { "D-01", "Text file (.txt) as CIN document", "not_a_document.txt", true },
            { "D-02", "Executable (.exe) as CIN document", "fake_program.exe", true },
            { "D-03", "Empty (0 byte) PDF as CIN document", "empty.pdf", true },
            { "D-04", "Text content renamed to .pdf as CIN document", "text_renamed.pdf", true },
            { "D-05", "Oversized (21 MB) PDF as CIN document", "oversize_21MB.pdf", true },
            { "D-06", "PDF with a 180-character file name as CIN document", "LONG", true },
            { "D-07", "Text file (.txt) as GST document", "not_a_document.txt", false },
            { "D-08", "Executable (.exe) as GST document", "fake_program.exe", false },
        };
    }

    /** The case needs Organization Details with EMPTY document slots. */
    private void emptyDocumentSlots() {
        ensureOrgStep();
        orgPage.removeAllDocuments();
        Assert.assertEquals(orgPage.documentCount(), 0, "Could not empty the document slots before the case");
    }

    // D-01..D-08 - Bad uploads in the CIN / GST slots: .txt, .exe, empty (0 byte) PDF, text renamed to .pdf, 21 MB PDF, 180-character file name.
    // Expected: refused with a message and nothing attached (known bug: all are accepted).
    @Test(priority = 700, dataProvider = "badDocuments")
    public void invalidDocumentIsRejected(String id, String title, String fileKey, boolean cinSlot) throws IOException {
        onlyIfSelected(id);
        prepare(id, G_DOC, title, this::emptyDocumentSlots);
        Path file = testFile(fileKey);
        String name = file.getFileName().toString();
        try {
            mustReject(id, G_DOC, title, (cinSlot ? "CIN" : "GST") + " slot, file '" + abbreviate(name) + "' (" + Files.size(file) + " bytes)",
                    "Upload is refused with a message and no document is attached", data, new String[0],
                    () -> orgPage.uploadAttempt(cinSlot, file.toString(), name));
        } finally {
            // an accepted bad file must not stay attached (the evidence was already captured by the phase)
            if (orgPage.isOpen() && orgPage.documentCount() > 0) {
                orgPage.removeAllDocuments();
            }
        }
    }

    // D-10 - A valid PDF whose name has spaces and symbols ("cin & test (1) #final.pdf") is accepted.
    @Test(priority = 710)
    public void specialCharacterFileNameIsAccepted() throws IOException {
        onlyIfSelected("D-10");
        prepare("D-10", G_DOC, "Valid PDF with spaces and symbols in its name is accepted", this::emptyDocumentSlots);
        TestReporter rep = TestReporter.start("D-10", G_DOC, "Valid PDF with spaces and symbols in its name is accepted",
                "Document upload: file-name edge case", "Copy of the CIN pdf named 'cin & test (1) #final.pdf'",
                "Upload accepted and shown with a Remove control", driver, data);
        Path f = copyOf(data.cinDocPath, "cin & test (1) #final.pdf");
        rep.phase("d_10", "Upload special-character file name", "Accepted and listed", () -> {
            Outcome o = orgPage.uploadAttempt(true, f.toString(), f.getFileName().toString());
            Assert.assertTrue(o.accepted, "Valid PDF with special characters in the name was refused: " + o.describe());
            orgPage.removeAllDocuments();
            return "Accepted and shown; removed again";
        });
    }

    // D-11 - Upload a document, Remove it, upload it again: every step works.
    @Test(priority = 720)
    public void removeThenReuploadWorks() {
        onlyIfSelected("D-11");
        prepare("D-11", G_DOC, "Remove a document, then upload it again", this::emptyDocumentSlots);
        TestReporter rep = TestReporter.start("D-11", G_DOC, "Remove a document, then upload it again",
                "Document upload lifecycle", "Valid CIN pdf uploaded, removed, uploaded again",
                "File disappears after Remove and can be uploaded again", driver, data);
        String name = fileName(data.cinDocPath);
        rep.phase("d_11", "Upload, remove, re-upload", "Upload shown; Remove clears it; re-upload shown", () -> {
            orgPage.uploadCin(data.cinDocPath, name);
            orgPage.removeFirstDocument();
            Assert.assertEquals(orgPage.documentCount(), 0, "Document still attached after Remove");
            orgPage.uploadCin(data.cinDocPath, name);
            Assert.assertEquals(orgPage.documentCount(), 1, "Re-upload after Remove did not attach the document");
            return "Upload ok, Remove ok, re-upload ok";
        });
    }

    // D-20 - Continue with no CIN and no GST document. Expected: refused with a message (assumes the documents are mandatory - to be confirmed with the product team).
    @Test(priority = 730)
    public void continueWithoutDocumentsIsBlocked() {
        onlyIfSelected("D-20");
        prepare("D-20", G_DOC, "Continue with no CIN and no GST document", this::emptyDocumentSlots);
        mustReject("D-20", G_DOC, "Continue with no CIN and no GST document",
                "Valid organization data, both documents removed (assumes documents are mandatory - confirm with product)",
                "Continue is refused with a message asking for the documents", data, new String[0], () -> orgPage.continueAttempt());
    }

    // ================================================================== C - Points of Contact (wizard step 2)

    // C-00 (set-up) - Continues to Points of Contact and enters three valid contacts: the baseline for the single-field cases below.
    @Test(priority = 800)
    public void c00_pointsOfContactBaseline() {
        onlyIfSelected("C-00");
        ensurePocStep(); // writes the C-00 case
    }

    @DataProvider(name = "pocFields")
    public Object[][] pocFields() {
        List<Object[]> rows = new ArrayList<>();
        int n = 1;
        for (String prefix : new String[] { "business", "finance", "hr" }) {
            String label = prefix.substring(0, 1).toUpperCase() + prefix.substring(1);
            rows.add(new Object[] { String.format("C-%02d", n++), label + " contact name empty", prefix, "name", "" });
            rows.add(new Object[] { String.format("C-%02d", n++), label + " contact designation empty", prefix, "designation", "" });
            rows.add(new Object[] { String.format("C-%02d", n++), label + " contact email empty", prefix, "email", "" });
            rows.add(new Object[] { String.format("C-%02d", n++), label + " contact email invalid (abc@)", prefix, "email", "abc@" });
            rows.add(new Object[] { String.format("C-%02d", n++), label + " contact phone empty", prefix, "contact_no", "" });
            rows.add(new Object[] { String.format("C-%02d", n++), label + " contact phone too short (12345)", prefix, "contact_no", "12345" });
            rows.add(new Object[] { String.format("C-%02d", n++), label + " contact phone letters", prefix, "contact_no", "abcdefghij" });
        }
        return rows.toArray(new Object[0][]);
    }

    // C-01..C-21 - Points of Contact with ONE bad field for each of Business, Finance and HR: name empty, designation empty, e-mail empty / "abc@", phone empty / "12345" / letters.
    // Expected: Continue is refused with a message and the wizard stays on step 2.
    @Test(priority = 810, dataProvider = "pocFields")
    public void invalidContactFieldIsRejected(String id, String title, String prefix, String field, String bad) {
        onlyIfSelected(id);
        prepare(id, G_POC, title, this::ensurePocStep);
        String valid = validPocValue(prefix, field);
        lastOutcome = null;
        try {
            mustReject(id, G_POC, title, "Valid form, only " + prefix + "." + field + " set to '" + bad + "'",
                    "Continue is refused with a message; the wizard stays on Points of Contact", data, new String[0], () -> {
                        pocPage.setField(prefix, field, bad);
                        lastOutcome = pocPage.continueAttempt();
                        return lastOutcome;
                    });
        } finally {
            if (!(lastOutcome != null && lastOutcome.accepted) && pocPage.isOpen()) {
                pocPage.setField(prefix, field, valid);
            }
        }
    }

    private String validPocValue(String prefix, String field) {
        ClientData.Contact c = contactOf(prefix);
        return switch (field) {
            case "name" -> c.name;
            case "designation" -> c.designation;
            case "email" -> c.email;
            default -> c.phone;
        };
    }

    // ================================================================== B4 - the onboarding wizard on the edge client: refresh, Back, re-login, review, back-and-edit
    // The wizard is never confirmed ('Confirm & Continue' would start the MSA), so the client stays In-Progress.

    // B-30 - F5 inside the wizard keeps the client signed in, in the wizard, on the same wizard step. A client that had already saved a LATER step (it went Back
    // to edit) is resumed at that furthest saved step: that is recorded as an observation, not as a failure (the result must not depend on the client's history).
    @Test(priority = 900)
    public void refreshKeepsTheWizardStep() {
        runCase("B-30", G_WIZARD, "Refresh (F5) inside the wizard keeps the client on the same step",
                "Signed in as the edge client, open the onboarding wizard, press F5",
                "Still signed in, still on /portal/onboarding, on the same step (or on the furthest step already saved, never an earlier one)", Who.CLIENT, () -> {
                    String before = openWizard();
                    driver.navigate().refresh();
                    Landing l = PageProbe.settle(driver, "F5 in wizard", 10);
                    Assert.assertFalse(l.loginForm, "F5 inside the wizard logged the client out. " + l.describe());
                    pocPage.dismissContinueSetupPrompt();
                    String after = waitStep();
                    Assert.assertTrue(driver.getCurrentUrl().contains("/portal/onboarding"), "F5 left the wizard: " + driver.getCurrentUrl());
                    List<String> order = List.of("Organization Details", "Points of Contact", "Review of Information", "Sign MSA");
                    int was = order.indexOf(before);
                    int now = order.indexOf(after);
                    if (was >= 0 && now > was) {
                        current.note("OBSERVATION (not a defect): the client was on '" + before + "' (it had gone Back); after F5 the wizard resumed at the furthest "
                                + "saved step '" + after + "'. A client whose saved progress equals the step on screen stays on that step.");
                        return "Step before F5: " + before + " | after F5: " + after + " (resumed at the furthest saved step)";
                    }
                    Assert.assertEquals(after, before, "F5 moved the client from step '" + before + "' to '" + after + "'");
                    return "Step before F5: " + before + " | after F5: " + after;
                });
    }

    // B-31 - The browser Back button from the wizard keeps the client signed in and shows a readable page.
    @Test(priority = 910)
    public void browserBackFromWizardKeepsSession() {
        runCase("B-31", G_WIZARD, "Browser Back from the wizard keeps the client signed in and shows a page",
                "Signed in as the edge client, in the wizard press the browser Back button",
                "A readable portal page (not the login page, not blank)", Who.CLIENT, () -> {
                    openWizard();
                    driver.navigate().back();
                    Landing l = PageProbe.settle(driver, "browser Back from wizard", 10);
                    Assert.assertFalse(l.loginForm, "Browser Back logged the client out. " + l.describe());
                    Assert.assertFalse(l.blank, "Browser Back left a blank page. " + l.describe());
                    return l.describe();
                });
    }

    // B-32 - Step 1 is saved (Continue, then Back), the session is ended and the client signs in again: every saved text value must still be there.
    @Test(priority = 920)
    public void savedValuesSurviveSessionEnd() {
        runCase("B-32", G_WIZARD, "Saved organization values survive ending the session and signing in again",
                "Edge client: Continue (saves step 1), Back, read the Organization Details form, end the session (cookies/storage cleared), sign in again, read the form again",
                "Every saved text value is identical after signing in again (documents are covered by B-37)", Who.NONE, this::ensureOrgStep, () -> {
                    orgPage.clickContinue(); // saves step 1: only saved values can be expected after a new session
                    pocPage.dismissContinueSetupPrompt();
                    pocPage.waitUntilOpen();
                    Assert.assertTrue(pocPage.clickBack(), "No Back button on Points of Contact");
                    orgPage.waitUntilOpen();
                    Map<String, String> before = orgValues();
                    signIn(data.email, activePassword, Who.CLIENT); // ends the session first
                    String resumed = openWizard();
                    gotoOrgStep();
                    Map<String, String> after = orgValues();
                    Assert.assertEquals(after, before, "Saved values changed after signing in again (wizard resumed on '" + resumed + "')");
                    return "Identical after re-login (wizard resumed on '" + resumed + "'): " + before.size() + " values compared";
                });
    }

    // B-33 - Fill both wizard steps and open the Review page (never confirmed). Expected: every value on the Review page equals what was entered and the mailing address
    // equals the registered address.
    @Test(priority = 930)
    public void reviewPageMatchesEnteredData() {
        runCase("B-33", G_WIZARD, "Review page shows exactly what was entered in Organization Details and Points of Contact",
                "Edge client: valid Organization Details (documents attached) and valid Business/Finance/HR contacts, Continue twice, read the Review page (Confirm is NOT clicked)",
                "Every value on the Review page equals what was entered; mailing address equals registered address", Who.NONE, this::ensureOrgStep, () -> {
                    String orgName = orgPage.prefilledOrganizationName();
                    String shownMobile = orgPage.mobileValue(); // read now: the form is gone after Continue
                    orgPage.clickContinue();
                    pocPage.dismissContinueSetupPrompt();
                    pocPage.waitUntilOpen();
                    pocPage.fill(data);
                    pocPage.clickContinue();
                    reviewPage.waitUntilOpen();
                    ReviewInformationPage.Review r = reviewPage.read();
                    List<String> bad = new ArrayList<>();
                    cmp(bad, "organization name", orgName, r.organization.get("Organization Name"));
                    cmp(bad, "company type", data.companyType, r.organization.get("Company Type"));
                    cmp(bad, "years in industry", data.yearsInIndustry, r.organization.get("Years in Industry"));
                    cmp(bad, "website", data.website, r.organization.get("Website"));
                    cmp(bad, "mobile", shownMobile, r.organization.get("Mobile Number"));
                    cmp(bad, "CIN", data.cin, r.organization.get("CIN"));
                    cmp(bad, "GST", data.gst, r.organization.get("Tax ID Number (GST Number)"));
                    String regAddr = r.organization.get("Registered Address");
                    if (regAddr == null || !regAddr.contains(data.address) || !regAddr.contains(data.pincode)) {
                        bad.add("registered address does not contain the entered address/pincode: " + regAddr);
                    }
                    if (!java.util.Objects.equals(regAddr, r.organization.get("Mailing Address"))) {
                        bad.add("mailing address differs although 'same as registered' was ticked: " + r.organization.get("Mailing Address"));
                    }
                    contactCmp(bad, "business", data.business, r.business);
                    contactCmp(bad, "finance", data.finance, r.finance);
                    contactCmp(bad, "hr", data.hr, r.hr);
                    Assert.assertTrue(bad.isEmpty(), "Review page differs from the entered data:\n - " + String.join("\n - ", bad));
                    return "Review page matches the entered organization data and the three contacts";
                });
    }

    // B-34 - Back from the Review step returns to Points of Contact with the three contacts still filled in.
    @Test(priority = 940)
    public void backFromReviewKeepsContacts() {
        runCase("B-34", G_WIZARD, "Back from the Review step returns to Points of Contact with the entered contacts kept",
                "Edge client on the Review step, click the wizard's Back button",
                "Points of Contact is shown again with the same Business/Finance/HR values", Who.NONE, this::ensureReviewStep, () -> {
                    ReviewInformationPage.Review r = reviewPage.read();
                    Assert.assertTrue(pocPage.clickBack(), "No Back button on the Review step");
                    pocPage.waitUntilOpen();
                    List<String> bad = new ArrayList<>();
                    for (String prefix : new String[] { "business", "finance", "hr" }) {
                        Map<String, String> shown = prefix.equals("business") ? r.business : prefix.equals("finance") ? r.finance : r.hr;
                        cmp(bad, prefix + " name", shown.get("Name"), pocPage.read(prefix, "name"));
                        cmp(bad, prefix + " email", shown.get("Email"), pocPage.read(prefix, "email"));
                        cmp(bad, prefix + " phone", shown.get("Contact Number"), pocPage.read(prefix, "contact_no"));
                    }
                    Assert.assertTrue(bad.isEmpty(), "Contacts were not kept after Back from Review:\n - " + String.join("\n - ", bad));
                    return "Points of Contact shown with the same values as the Review page";
                });
    }

    // B-35 (back-and-edit) - Go Back from step 2, change years in industry / website / address, go forward twice. Expected: the earlier values were kept after Back, the Review
    // page shows the NEW values (not the old ones) and the mailing address follows the registered address.
    /** Back-and-edit: enter details, move next, go back, enter DIFFERENT values, move next again. The new values must win. */
    @Test(priority = 950)
    public void changedOrganizationValuesReachTheReview() {
        runCase("B-35", G_WIZARD, "Back-and-edit: values changed on Organization Details after going Back are the ones on the Review page",
                "Edge client on Points of Contact: Back to Organization Details, change years in industry / website / address, Continue twice, read the Review page (Confirm is NOT clicked)",
                "The earlier values are still there after Back; the Review page shows the NEW values (not the old ones); the mailing address follows the registered address",
                Who.NONE, this::ensurePocStep, () -> {
                    List<String> bad = new ArrayList<>();
                    Assert.assertTrue(pocPage.clickBack(), "No Back button on Points of Contact");
                    orgPage.waitUntilOpen();
                    cmp(bad, "years in industry kept after Back", data.yearsInIndustry, orgPage.fieldValue("years_in_industry"));
                    cmp(bad, "website kept after Back", data.website, orgPage.fieldValue("website_link"));
                    cmp(bad, "address kept after Back", data.address, orgPage.fieldValue("registered_address.address"));
                    cmp(bad, "CIN kept after Back", data.cin, orgPage.fieldValue("company_registration_number"));

                    String years = "9";
                    String website = "https://www.changed-after-back.example.com";
                    String address = "Changed address 99, Back-and-edit Area";
                    orgPage.setField("years_in_industry", years);
                    orgPage.setField("website_link", website);
                    orgPage.setField("registered_address.address", address);
                    orgPage.clickContinue();
                    pocPage.dismissContinueSetupPrompt();
                    pocPage.waitUntilOpen();
                    pocPage.clickContinue();
                    reviewPage.waitUntilOpen();
                    ReviewInformationPage.Review r = reviewPage.read();
                    cmp(bad, "Review: years in industry shows the NEW value", years, r.organization.get("Years in Industry"));
                    cmp(bad, "Review: website shows the NEW value", website, r.organization.get("Website"));
                    String regAddr = r.organization.get("Registered Address");
                    if (regAddr == null || !regAddr.contains(address)) {
                        bad.add("Review: registered address does not show the NEW address '" + address + "' but '" + regAddr + "'");
                    }
                    if (!java.util.Objects.equals(regAddr, r.organization.get("Mailing Address"))) {
                        bad.add("Review: mailing address does not follow the registered address although 'same as registered' is ticked: '"
                                + r.organization.get("Mailing Address") + "'");
                    }
                    if (!bad.isEmpty()) {
                        current.defect("Back-and-edit on Organization Details does not behave: " + bad.size() + " problem(s)",
                                "Values survive Back, and the changed values (not the old ones) are shown on the Review page", String.join("; ", bad));
                    }
                    return bad.isEmpty() ? "Values survived Back and the changed values are on the Review page"
                            : bad.size() + " problem(s) (see defect)";
                });
    }

    // B-37 - After Back from step 2, the CIN and GST documents uploaded in step 1 must still be attached (known bug: the document slots are empty).
    /** Uploaded documents must still be attached when the client comes back to step 1 (they were uploaded there). */
    @Test(priority = 965)
    public void documentsSurviveBackFromPointsOfContact() {
        runCase("B-37", G_WIZARD, "Documents uploaded in step 1 are still attached after Back from Points of Contact",
                "Edge client on Points of Contact (CIN and GST were uploaded in step 1): click Back, look at the document slots",
                "Both documents (CIN, GST) are still shown with a Remove control", Who.NONE, this::ensurePocStep, () -> {
                    Assert.assertTrue(pocPage.clickBack(), "No Back button on Points of Contact");
                    orgPage.waitUntilOpen();
                    current.evidence("organization_details_after_back");
                    int shown = orgPage.documentCount();
                    if (shown != 2) {
                        current.defect("Documents uploaded in step 1 are not shown after Back from step 2",
                                "Both documents (" + fileName(data.cinDocPath) + ", " + fileName(data.taxDocPath) + ") still attached",
                                shown + " document(s) shown after Back");
                    }
                    return shown + " document(s) shown after Back";
                });
    }

    // B-36 (back-and-edit) - Go Back from the Review step, change the Finance contact (name, e-mail, phone), go forward. Expected: the Review page shows the NEW Finance contact;
    // Business and HR are unchanged.
    @Test(priority = 960)
    public void changedContactsReachTheReview() {
        runCase("B-36", G_WIZARD, "Back-and-edit: contacts changed on Points of Contact after going Back from Review are the ones on the Review page",
                "Edge client on the Review step: Back, change the Finance contact name / e-mail / phone, Continue, read the Review page (Confirm is NOT clicked)",
                "The Review page shows the NEW Finance contact; the Business and HR contacts are unchanged", Who.NONE, this::ensureReviewStep, () -> {
                    List<String> bad = new ArrayList<>();
                    Assert.assertTrue(pocPage.clickBack(), "No Back button on the Review step");
                    pocPage.waitUntilOpen();
                    String name = "Finance Changed " + data.runId;
                    String email = "komp.ind.edge.fin2." + data.runId + "@yopmail.com";
                    String phone = TestDataGenerator.phone();
                    pocPage.setField("finance", "name", name);
                    pocPage.setField("finance", "email", email);
                    pocPage.setField("finance", "contact_no", phone);
                    pocPage.clickContinue();
                    reviewPage.waitUntilOpen();
                    ReviewInformationPage.Review r = reviewPage.read();
                    cmp(bad, "Review: finance name shows the NEW value", name, r.finance.get("Name"));
                    cmp(bad, "Review: finance e-mail shows the NEW value", email, r.finance.get("Email"));
                    cmp(bad, "Review: finance phone shows the NEW value", phone, r.finance.get("Contact Number"));
                    contactCmp(bad, "business (unchanged)", data.business, r.business);
                    contactCmp(bad, "hr (unchanged)", data.hr, r.hr);
                    if (!bad.isEmpty()) {
                        current.defect("Back-and-edit on Points of Contact does not behave: " + bad.size() + " problem(s)",
                                "The changed Finance contact is shown on the Review page; Business and HR stay as entered", String.join("; ", bad));
                    }
                    return bad.isEmpty() ? "The changed Finance contact is on the Review page; Business and HR unchanged" : bad.size() + " problem(s) (see defect)";
                });
    }

    // ================================================================== X-81 client portal API health, L-09 wrong password of the edge client

    private String apiCheck(List<ApiCall> calls) {
        List<String> bad = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (ApiCall c : calls) {
            if (c.isServerError() || c.error != null) {
                bad.add(c.toString());
            } else if (c.isClientError()) {
                notes.add(c.toString());
            }
        }
        Assert.assertTrue(bad.isEmpty(), "Server errors / failed calls while browsing: " + bad);
        return calls.size() + " API call(s), no 5xx" + (notes.isEmpty() ? "" : "; 4xx seen: " + notes);
    }

    // X-81 - Browsing the client portal (home, then the wizard) produces no 5xx API answers; the notifications call must answer 200.
    @Test(priority = 970)
    public void clientPortalHasNoServerErrors() {
        runCase("X-81", G_API, "Browsing the client portal produces no 5xx API responses (incl. notifications)",
                "Signed in as the edge client (" + SecretMasker.maskEmail(data.email) + " / " + SecretMasker.reveal(activePassword)
                        + "): portal home, then the wizard via Continue (read-only), then back to home",
                "Every API call returns 2xx/3xx; GET .../notifications/push_api/user-notification/ is 200", Who.CLIENT, () -> {
                    current.observeApi();
                    PageProbe.open(driver, base() + "/portal", 10);
                    By entry = By.xpath("//button[normalize-space()='Start' or normalize-space()='Continue' or normalize-space()='Continue Setup']");
                    if (!driver.findElements(entry).isEmpty()) {
                        enterWizardFromHome();
                        PageProbe.settle(driver, "wizard", 8);
                        driver.navigate().back();
                        PageProbe.settle(driver, "portal", 8);
                    }
                    List<ApiCall> calls = current.observeApi();
                    boolean notif = false;
                    for (ApiCall c : calls) {
                        if (c.url.contains("user-notification")) {
                            notif = true;
                            Assert.assertTrue(c.status >= 200 && c.status < 300, "Notifications call failed: " + c);
                        }
                    }
                    return apiCheck(calls) + (notif ? "; notifications call OK" : "; notifications call not seen in this visit");
                });
    }

    // L-09 - A wrong password for an EXISTING client account (the edge client, single attempt). Run late so that a possible lock-out cannot hurt the other cases.
    // Expected: refused with a message.
    /** Last sign-in case of the lifecycle: a wrong password could lock the account, so it comes after every case that needs the client. */
    @Test(priority = 980)
    public void existingAccountWrongPassword() {
        onlyIfSelected("L-09");
        String email = registered ? data.email : ConfigManager.get("login.negative.existing.email", "komp.recon.143733@yopmail.com");
        mustReject("L-09", G_LOGIN, "Existing client, wrong password",
                "Existing client " + email + " with a wrong password (single attempt)",
                "Sign-in is refused, the user stays on the login page, and a message explains the problem", data, new String[0], () -> {
                    signedOut();
                    return rejectedLogin(email, WRONG);
                });
    }

    // ================================================================== admin side, read-only: session cases, EOR status of an ACTIVE client
    // Nothing is created or changed. The wizard's client is not used here.

    // B-22 - The admin opens /clients by URL: expected the Clients page (known bug: it lands on the dashboard).
    @Test(priority = 1000)
    public void adminSessionSurvivesDeepLink() {
        runCase("B-22", G_SESSION, "Admin session survives opening a deep link (/clients)",
                "Signed in as admin, open " + base() + "/clients by URL (full page load)", "The Clients page is shown, not the login page or the dashboard",
                Who.ADMIN, () -> {
                    Landing l = PageProbe.open(driver, base() + "/clients", 10);
                    Assert.assertFalse(l.loginForm, "Deep link to /clients logged the admin out. " + l.describe());
                    Assert.assertTrue(l.path.startsWith("/clients"), "Deep link to /clients landed elsewhere. " + l.describe());
                    return l.describe();
                });
    }

    // B-23 - A signed-in admin opens /login: expected a redirect to the console (known bug: the login form is shown again).
    @Test(priority = 1010)
    public void loginPageWhenAlreadySignedInAdmin() {
        runCase("B-23", G_SESSION, "Signed-in admin opening /login is sent back to the console",
                "Signed in as admin, open " + base() + "/login", "Redirect to the console (a signed-in user is not shown the login form)", Who.ADMIN, () -> {
                    Landing l = PageProbe.open(driver, base() + "/login", 8);
                    Assert.assertFalse(l.loginForm, "A signed-in admin is shown the login form again. " + l.describe());
                    return l.describe();
                });
    }

    // B-25 - F5 on the Clients page keeps the Clients page.
    @Test(priority = 1020)
    public void refreshOnClientsPageKeepsThePage() {
        runCase("B-25", G_SESSION, "Refresh (F5) on the Clients page keeps the Clients page",
                "Signed in as admin, open Clients from the sidebar, press F5", "The Clients page is shown again (not the dashboard / login)", Who.ADMIN, () -> {
                    clientsPage.open();
                    driver.navigate().refresh();
                    Landing l = PageProbe.settle(driver, "F5 on /clients", 10);
                    Assert.assertFalse(l.loginForm, "F5 logged the admin out. " + l.describe());
                    Assert.assertTrue(l.path.startsWith("/clients"), "F5 on /clients lands on " + l.path + " instead of staying on the Clients page. " + l.describe());
                    return l.describe();
                });
    }

    /** Name of an ACTIVE client (read-only fixture): -Deor.active.client, else the first ACTIVE client of the Clients list. */
    private String activeClient() {
        if (activeClientName == null) {
            String configured = ConfigManager.get("eor.active.client");
            if (configured != null) {
                activeClientName = configured;
            } else {
                adminSession();
                clientsPage.open();
                resetList();
                clientsPage.clickTile("Active");
                activeClientName = clientsPage.visibleRows().stream().filter(r -> r.status.equalsIgnoreCase("Active")).map(r -> r.company)
                        .findFirst().orElseThrow(() -> new SkipException("The sandbox has no ACTIVE client (run the happy-flow class first)"));
                clientsPage.clickTile("Total");
            }
        }
        return activeClientName;
    }

    // B-40 - The status badge of an ACTIVE MSA must not offer "Approve & Activate" again (it is never clicked).
    @Test(priority = 1030)
    public void activeClientCannotBeApprovedAgain() {
        runCase("B-40", G_EOR, "An ACTIVE MSA does not offer 'Approve & Activate' again",
                "Signed in as admin/EOR, MSA & Contract > MSA, search an ACTIVE client, click its status badge (Approve is never clicked)",
                "The status badge is not actionable, or the dialog offers no 'Approve & Activate'", Who.ADMIN, this::activeClient, () -> {
                    String client = activeClient();
                    EorMsaPage msa = new EorMsaPage(driver);
                    msa.open();
                    msa.searchClient(client);
                    Assert.assertEquals(msa.status(client), "ACTIVE", "Precondition: '" + client + "' must be ACTIVE");
                    ToastWatcher.clear(driver);
                    List<WebElement> badge = driver.findElements(By.xpath("//tbody/tr[td[1][normalize-space()=\"" + client + "\"]]/td[5]//button"));
                    if (badge.isEmpty() || !badge.get(0).isEnabled()) {
                        return "Client '" + client + "': the status badge of the ACTIVE row is not an enabled button: nothing to approve again.";
                    }
                    badge.get(0).click();
                    boolean offered = !driver.findElements(By.xpath("//div[@role='dialog']//button[normalize-space()='Approve & Activate']")).isEmpty();
                    String dialog = driver.findElements(By.xpath("//div[@role='dialog']")).isEmpty() ? "no dialog"
                            : driver.findElement(By.xpath("//div[@role='dialog']")).getText().replace("\n", " ");
                    driver.findElement(By.tagName("body")).sendKeys(org.openqa.selenium.Keys.ESCAPE); // never approve
                    Assert.assertFalse(offered, "An already ACTIVE client offers 'Approve & Activate' again. Dialog: " + dialog);
                    return "Client '" + client + "': badge clicked; dialog: " + dialog;
                });
    }

    // B-41 - "Refresh sign status" on an ACTIVE MSA keeps it ACTIVE with the same start date.
    @Test(priority = 1040)
    public void refreshOnActiveKeepsActive() {
        runCase("B-41", G_EOR, "Refresh sign status on an ACTIVE MSA keeps it ACTIVE",
                "Signed in as admin/EOR, MSA list, search an ACTIVE client, click the Refresh sign status icon",
                "Status stays ACTIVE (and the start date does not change)", Who.ADMIN, this::activeClient, () -> {
                    String client = activeClient();
                    EorMsaPage msa = new EorMsaPage(driver);
                    msa.open();
                    msa.searchClient(client);
                    String before = msa.status(client) + " / start " + msa.startDate(client);
                    msa.clickRefresh(client);
                    try {
                        Thread.sleep(6000); // documented exception: lets the sign-status sync finish
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    String after = msa.status(client) + " / start " + msa.startDate(client);
                    Assert.assertEquals(after, before, "Refresh changed an ACTIVE MSA ('" + client + "')");
                    return "Client '" + client + "' | before: " + before + " | after: " + after;
                });
    }

    // ================================================================== X - Clients list: snapshot, helpers
    // Read-only, admin account. Expected counts come from a snapshot of EVERY client (taken the first time a list case runs).

    /** Snapshot of all clients (50 per page, all pages): the expected counts of the search / filter / tile / pagination cases come from it. */
    private void ensureClientList() {
        adminSession();
        if (allRows != null) {
            return;
        }
        existingClient(); // the search fixture (first yopmail client)
        clientsPage.open();
        clientsPage.searchOnly("");
        if (clientsPage.hasPageSizeControl()) {
            clientsPage.pageSize(50);
        }
        List<ClientsPage.Row> all = new ArrayList<>();
        int count = -1;
        for (int page = 0; page < 40; page++) {
            all.addAll(clientsPage.visibleRows());
            if (count < 0) {
                count = clientsPage.totalFromSummary();
            }
            if (!clientsPage.isNextEnabled()) {
                break;
            }
            clientsPage.clickNext();
        }
        if (clientsPage.hasPageSizeControl()) {
            clientsPage.pageSize(10);
        }
        Assert.assertEquals(all.size(), count, "Snapshot of all clients is incomplete");
        total = count;
        allRows = all;
    }

    /** First step of every list case: the snapshot exists (so 'ref' and 'allRows' can be used to build the case). */
    private void prepareList(String id, String group, String title) {
        onlyIfSelected(id);
        prepare(id, group, title, this::ensureClientList);
    }

    /** Back to the plain list: no search text, no filter, Total tile, 10 per page, page 1. */
    private void resetList() {
        clientsPage.open();
        clientsPage.searchOnly("");
        if (clientsPage.clearAllVisible()) {
            clientsPage.clearAllFilters();
        }
        clientsPage.clickTile("Total");
        if (clientsPage.hasPageSizeControl() && !"10 / page".equals(clientsPage.pageSizeShown())) {
            clientsPage.pageSize(10);
        }
        clientsPage.gotoPage(1);
        clientsPage.visibleRows();
    }

    private long count(Predicate<ClientsPage.Row> p) {
        return allRows.stream().filter(p).count();
    }

    private static boolean matches(ClientsPage.Row r, String term) {
        String t = term.trim().toLowerCase(Locale.ROOT);
        return r.company.toLowerCase(Locale.ROOT).contains(t) || r.poc.toLowerCase(Locale.ROOT).contains(t);
    }

    private String rowsSummary(List<ClientsPage.Row> rows) {
        List<String> names = new ArrayList<>();
        for (ClientsPage.Row r : rows) {
            names.add(r.company);
        }
        return rows.size() + " row(s) " + (names.size() > 3 ? names.subList(0, 3) + "..." : names) + " | " + clientsPage.listSummary();
    }

    // ================================================================== C1 - Clients search

    // X-01 - Clients search by the full company name lists only matching clients.
    @Test(priority = 1100)
    public void searchByFullCompanyName() {
        prepareList("X-01", G_SEARCH, "Search by the full company name returns only matching clients");
        runCase("X-01", G_SEARCH, "Search by the full company name returns only matching clients",
                "Clients > search box = '" + ref.company + "'",
                "Only rows whose company or POC contains the term; the client is listed; other clients are filtered out", Who.ADMIN, () -> {
                    resetList();
                    clientsPage.searchOnly(ref.company);
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    Assert.assertFalse(rows.isEmpty(), "No rows for an existing client name. " + rowsSummary(rows));
                    for (ClientsPage.Row r : rows) {
                        Assert.assertTrue(matches(r, ref.company), "Row does not match the search term: " + r);
                    }
                    if (total > 1) {
                        Assert.assertTrue(rows.size() < total, "Search did not filter: still " + rows.size() + " of " + total + " rows");
                    }
                    return rowsSummary(rows);
                });
    }

    // X-02 - Search by the last characters of a company name finds it; every row matches the text.
    @Test(priority = 1101)
    public void searchByPartialName() {
        prepareList("X-02", G_SEARCH, "Search by a partial company name (last characters)");
        String part = ref.company.substring(Math.max(0, ref.company.length() - 8));
        runCase("X-02", G_SEARCH, "Search by a partial company name (last characters)",
                "Clients > search box = '" + part + "'", "The client is listed; every row matches the fragment", Who.ADMIN, () -> {
                    resetList();
                    clientsPage.searchOnly(part);
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    Assert.assertTrue(rows.stream().anyMatch(r -> r.company.equals(ref.company)), "Client not found by a partial name. " + rowsSummary(rows));
                    for (ClientsPage.Row r : rows) {
                        Assert.assertTrue(matches(r, part), "Row does not match the fragment: " + r);
                    }
                    return rowsSummary(rows);
                });
    }

    // X-03 - Search by the point-of-contact name finds the client.
    @Test(priority = 1102)
    public void searchByPocName() {
        prepareList("X-03", G_SEARCH, "Search by the point of contact name");
        runCase("X-03", G_SEARCH, "Search by the point of contact name",
                "Clients > search box = '" + ref.poc + "'", "The client is listed; every row matches the POC name", Who.ADMIN, () -> {
                    resetList();
                    clientsPage.searchOnly(ref.poc);
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    Assert.assertTrue(rows.stream().anyMatch(r -> r.company.equals(ref.company)), "Client not found by its POC name. " + rowsSummary(rows));
                    for (ClientsPage.Row r : rows) {
                        Assert.assertTrue(matches(r, ref.poc), "Row does not match the POC term: " + r);
                    }
                    return rowsSummary(rows);
                });
    }

    // X-04 - Search in UPPER CASE finds the same client.
    @Test(priority = 1103)
    public void searchIsCaseInsensitive() {
        prepareList("X-04", G_SEARCH, "Search ignores upper / lower case");
        runCase("X-04", G_SEARCH, "Search ignores upper / lower case",
                "Clients > search box = '" + ref.company.toUpperCase(Locale.ROOT) + "' (upper case)", "Same client found as with the original case", Who.ADMIN, () -> {
                    resetList();
                    clientsPage.searchOnly(ref.company.toUpperCase(Locale.ROOT));
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    Assert.assertTrue(rows.stream().anyMatch(r -> r.company.equals(ref.company)), "Upper-case search did not find the client. " + rowsSummary(rows));
                    return rowsSummary(rows);
                });
    }

    // X-05 - Spaces before / after the search text are ignored.
    @Test(priority = 1104)
    public void searchTrimsSpaces() {
        prepareList("X-05", G_SEARCH, "Leading and trailing spaces in the search are ignored");
        runCase("X-05", G_SEARCH, "Leading and trailing spaces in the search are ignored",
                "Clients > search box = '   " + ref.company + "   '", "Same client found as without the spaces", Who.ADMIN, () -> {
                    resetList();
                    clientsPage.searchOnly("   " + ref.company + "   ");
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    Assert.assertTrue(rows.stream().anyMatch(r -> r.company.equals(ref.company)), "Padded search did not find the client. " + rowsSummary(rows));
                    return rowsSummary(rows);
                });
    }

    // X-06 - A search without a match shows no rows and a readable empty-state message.
    @Test(priority = 1105)
    public void searchWithNoMatchShowsEmptyState() {
        prepareList("X-06", G_SEARCH, "A search with no match shows an empty-state message");
        String term = "zzz-no-such-client-" + System.currentTimeMillis();
        runCase("X-06", G_SEARCH, "A search with no match shows an empty-state message",
                "Clients > search box = '" + term + "'", "No client rows and a readable empty-state message (not the full list, not a blank table)", Who.ADMIN, () -> {
                    resetList();
                    clientsPage.searchOnly(term);
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    Assert.assertTrue(rows.isEmpty(), "A no-match search still lists clients. " + rowsSummary(rows));
                    String empty = clientsPage.emptyStateText();
                    if (empty.isEmpty() || empty.startsWith("(table body is empty)")) {
                        current.defect("No empty-state message for a search without results", "A message such as 'No clients found'",
                                "Table body text: '" + empty + "' | " + clientsPage.listSummary());
                    }
                    return "0 rows; empty-state text: '" + empty.replace("\n", " ") + "'";
                });
    }

    @DataProvider(name = "specialSearchTerms")
    public Object[][] specialSearchTerms() {
        return new Object[][] {
            { "X-07", "<script>alert(1)</script>" }, { "X-08", "%" }, { "X-09", "'" }, { "X-10", "\"; DROP TABLE clients;--" },
            { "X-11", "\\" }, { "X-12", "A".repeat(300) },
        };
    }

    // X-07..X-12 - Search text <script>, %, ', SQL-like text, backslash and 300 characters: no script runs, no 5xx answer, the page stays usable.
    @Test(priority = 1106, dataProvider = "specialSearchTerms")
    public void specialSearchTermsAreHandledSafely(String id, String term) {
        String shown = term.length() > 40 ? term.substring(0, 40) + "... (" + term.length() + " chars)" : term;
        prepareList(id, G_SEARCH, "Search text '" + shown + "' is handled safely");
        runCase(id, G_SEARCH, "Search text '" + shown + "' is handled safely",
                "Clients > search box = '" + shown + "'",
                "No script runs, no server error (5xx), the list shows no match or only matching rows, the page stays usable", Who.ADMIN, () -> {
                    resetList();
                    current.observeApi();
                    clientsPage.searchOnly(term);
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    assertNoAlert();
                    List<ApiCall> calls = current.observeApi();
                    for (ApiCall c : calls) {
                        Assert.assertFalse(c.isServerError() || c.error != null, "Server error while searching: " + c);
                    }
                    Assert.assertTrue(driver.findElement(By.xpath("//input[@placeholder='Search by company or POC name']")).isDisplayed(),
                            "The search box disappeared (page broke)");
                    return rowsSummary(rows) + " | API calls: " + calls.size();
                });
    }

    // X-13 - Clearing the search brings back the full list and the same "Showing ... of N" text.
    @Test(priority = 1107)
    public void clearingSearchRestoresTheList() {
        prepareList("X-13", G_SEARCH, "Clearing the search restores the full list");
        runCase("X-13", G_SEARCH, "Clearing the search restores the full list",
                "Search for a client, then clear the search box", "The same number of rows and the same 'Showing ... of N' total as before searching", Who.ADMIN, () -> {
                    resetList();
                    String before = clientsPage.listSummary();
                    clientsPage.searchOnly(ref.company);
                    clientsPage.visibleRows();
                    clientsPage.searchOnly("");
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    Assert.assertEquals(rows.size(), Math.min(10, total), "Row count after clearing differs from the unfiltered list");
                    Assert.assertEquals(clientsPage.listSummary(), before, "Summary text differs after clearing the search");
                    return rowsSummary(rows);
                });
    }

    // ================================================================== C2 - Clients filters (Country, Client Type, status tiles, Clear all)

    /** Applies a popover filter and checks the result against the snapshot of all clients. */
    private String filterCase(String label, Predicate<ClientsPage.Row> expected, String... options) {
        resetList();
        clientsPage.selectFilter(label, options);
        List<ClientsPage.Row> rows = clientsPage.visibleRows();
        long want = count(expected);
        int shown = rows.isEmpty() ? 0 : clientsPage.totalFromSummary();
        String text = clientsPage.filterText(label);
        Assert.assertFalse(text.endsWith("Any"), "The " + label + " filter still reads 'Any' after choosing " + Arrays.toString(options) + ": '" + text + "'");
        for (ClientsPage.Row r : rows) {
            Assert.assertTrue(expected.test(r), "Row outside the " + label + " filter " + Arrays.toString(options) + ": " + r);
        }
        Assert.assertEquals(shown, want, label + " filter " + Arrays.toString(options) + " lists " + shown + " client(s) but " + want
                + " of " + total + " clients match. " + rowsSummary(rows));
        if (want == 0) {
            Assert.assertFalse(clientsPage.emptyStateText().isEmpty(), "A filter without results shows no message");
        }
        return "trigger '" + text + "' | expected " + want + " | " + rowsSummary(rows);
    }

    // X-20 - Country filter = India lists exactly the India clients (the count is compared with the full list).
    @Test(priority = 1210)
    public void countryIndia() {
        prepareList("X-20", G_FILTER, "Country filter = India lists exactly the India clients");
        runCase("X-20", G_FILTER, "Country filter = India lists exactly the India clients", "Clients > Country > tick 'India'",
                "Every row has Country India and 'of N' equals the number of India clients in the full list", Who.ADMIN,
                () -> filterCase("Country", r -> r.country.equals("India"), "India"));
    }

    // X-21 - Country filter = Brazil lists exactly the Brazil clients, or shows the empty state.
    @Test(priority = 1211)
    public void countryBrazil() {
        prepareList("X-21", G_FILTER, "Country filter = Brazil lists exactly the Brazil clients (or the empty state)");
        runCase("X-21", G_FILTER, "Country filter = Brazil lists exactly the Brazil clients (or the empty state)", "Clients > Country > tick 'Brazil'",
                "Every row has Country Brazil, 'of N' equals their number; with none, 'No clients found' is shown", Who.ADMIN,
                () -> filterCase("Country", r -> r.country.equals("Brazil"), "Brazil"));
    }

    // X-22 - Country filter = United States, same check.
    @Test(priority = 1212)
    public void countryUnitedStates() {
        prepareList("X-22", G_FILTER, "Country filter = United States lists exactly the US clients (or the empty state)");
        runCase("X-22", G_FILTER, "Country filter = United States lists exactly the US clients (or the empty state)", "Clients > Country > tick 'United States'",
                "Every row has Country United States, 'of N' equals their number; with none, 'No clients found' is shown", Who.ADMIN,
                () -> filterCase("Country", r -> r.country.equals("United States"), "United States"));
    }

    // X-23 - Client Type filter = AOR lists exactly the AOR clients.
    @Test(priority = 1213)
    public void clientTypeAor() {
        prepareList("X-23", G_FILTER, "Client Type filter = AOR lists exactly the AOR clients");
        runCase("X-23", G_FILTER, "Client Type filter = AOR lists exactly the AOR clients", "Clients > Client Type > tick 'AOR'",
                "Every row is AOR and 'of N' equals the number of AOR clients in the full list", Who.ADMIN,
                () -> filterCase("Client Type", r -> r.type.equals("AOR"), "AOR"));
    }

    // X-24 - Client Type filter = EOR lists exactly the EOR clients.
    @Test(priority = 1214)
    public void clientTypeEor() {
        prepareList("X-24", G_FILTER, "Client Type filter = EOR lists exactly the EOR clients");
        runCase("X-24", G_FILTER, "Client Type filter = EOR lists exactly the EOR clients", "Clients > Client Type > tick 'EOR'",
                "Every row is EOR and 'of N' equals the number of EOR clients in the full list", Who.ADMIN,
                () -> filterCase("Client Type", r -> r.type.equals("EOR"), "EOR"));
    }

    // X-25 - The Country filter accepts several countries at once (India + Brazil).
    @Test(priority = 1215)
    public void countryMultiSelect() {
        prepareList("X-25", G_FILTER, "Country filter accepts several countries (India + Brazil)");
        runCase("X-25", G_FILTER, "Country filter accepts several countries (India + Brazil)", "Clients > Country > tick 'India' and 'Brazil'",
                "Rows from either country; 'of N' equals the number of India + Brazil clients", Who.ADMIN,
                () -> filterCase("Country", r -> r.country.equals("India") || r.country.equals("Brazil"), "India", "Brazil"));
    }

    // X-26 - Country and Client Type filters combine (India AND AOR).
    @Test(priority = 1216)
    public void countryAndTypeCombined() {
        prepareList("X-26", G_FILTER, "Country and Client Type filters combine (India AND AOR)");
        runCase("X-26", G_FILTER, "Country and Client Type filters combine (India AND AOR)", "Clients > Country 'India' + Client Type 'AOR'",
                "Only clients that are India AND AOR; 'of N' equals their number", Who.ADMIN, () -> {
                    resetList();
                    clientsPage.selectFilter("Country", "India");
                    clientsPage.selectFilter("Client Type", "AOR");
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    long want = count(r -> r.country.equals("India") && r.type.equals("AOR"));
                    int shown = rows.isEmpty() ? 0 : clientsPage.totalFromSummary();
                    for (ClientsPage.Row r : rows) {
                        Assert.assertTrue(r.country.equals("India") && r.type.equals("AOR"), "Row outside India AND AOR: " + r);
                    }
                    Assert.assertEquals(shown, want, "Combined filters list " + shown + " client(s) but " + want + " match. " + rowsSummary(rows));
                    return "expected " + want + " | " + rowsSummary(rows);
                });
    }

    // X-27 - The search box inside the Country filter narrows its options ("bra" shows Brazil; a term without a match shows "No results").
    @Test(priority = 1217)
    public void filterOptionsCanBeSearched() {
        prepareList("X-27", G_FILTER, "The search field inside the Country filter narrows its options");
        runCase("X-27", G_FILTER, "The search field inside the Country filter narrows its options",
                "Clients > Country > type 'bra', then 'brazzz'", "'bra' shows only Brazil; a term without match shows 'No results.'", Who.ADMIN, () -> {
                    resetList();
                    clientsPage.openFilter("Country");
                    clientsPage.searchInFilter("bra");
                    List<String> options = clientsPage.filterOptionsShown();
                    Assert.assertEquals(options, List.of("Brazil"), "Options for 'bra'");
                    clientsPage.searchInFilter("zzzz");
                    List<String> none = clientsPage.filterOptionsShown();
                    String popover = clientsPage.filterPopoverText();
                    clientsPage.closePopover();
                    Assert.assertTrue(none.isEmpty(), "Options shown for a term without match: " + none);
                    Assert.assertFalse(popover.isEmpty(), "Empty popover without any message");
                    return "'bra' -> " + options + " | 'zzzz' -> " + none + " | popover text: '" + popover + "'";
                });
    }

    // X-28 - A filter and the search box work together (AND).
    @Test(priority = 1218)
    public void filterAndSearchCombine() {
        prepareList("X-28", G_FILTER, "A filter and the search box combine (AND)");
        String other = ref.type.equals("EOR") ? "AOR" : "EOR";
        runCase("X-28", G_FILTER, "A filter and the search box combine (AND)",
                "Client Type '" + other + "' + search '" + ref.company + "' (the client is " + ref.type + "), then Client Type '" + ref.type + "' + the same search",
                "With the other type: no rows and an empty-state message; with its own type: the client is listed", Who.ADMIN, () -> {
                    resetList();
                    clientsPage.selectFilter("Client Type", other);
                    clientsPage.searchOnly(ref.company);
                    List<ClientsPage.Row> none = clientsPage.visibleRows();
                    String empty = clientsPage.emptyStateText();
                    Assert.assertTrue(none.isEmpty(), "Filter " + other + " + search for a " + ref.type + " client still lists rows: " + rowsSummary(none));
                    Assert.assertFalse(empty.isEmpty(), "No empty-state message when filter and search exclude every client");
                    resetList();
                    clientsPage.selectFilter("Client Type", ref.type);
                    clientsPage.searchOnly(ref.company);
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    Assert.assertTrue(rows.stream().anyMatch(r -> r.company.equals(ref.company)), "Filter " + ref.type + " + search lost the client. " + rowsSummary(rows));
                    return "other type: 0 rows ('" + empty.replace("\n", " ") + "') | own type: " + rowsSummary(rows);
                });
    }

    // X-29 - "Clear all" resets both filters and shows every client again.
    @Test(priority = 1219)
    public void clearAllResetsFilters() {
        prepareList("X-29", G_FILTER, "'Clear all' resets the Country and Client Type filters");
        runCase("X-29", G_FILTER, "'Clear all' resets the Country and Client Type filters",
                "Clients > Country 'India' + Client Type 'AOR', then click 'Clear all'",
                "Both filters read 'Any' again and the full list ('of N' = all clients) is shown", Who.ADMIN, () -> {
                    resetList();
                    clientsPage.selectFilter("Country", "India");
                    clientsPage.selectFilter("Client Type", "AOR");
                    Assert.assertTrue(clientsPage.clearAllVisible(), "'Clear all' is not offered while filters are active");
                    clientsPage.clearAllFilters();
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    Assert.assertTrue(clientsPage.filterText("Country").endsWith("Any"), "Country still filtered: " + clientsPage.filterText("Country"));
                    Assert.assertTrue(clientsPage.filterText("Client Type").endsWith("Any"), "Client Type still filtered: " + clientsPage.filterText("Client Type"));
                    Assert.assertEquals(clientsPage.totalFromSummary(), total, "List is not back to all clients after 'Clear all'");
                    return "Filters: " + clientsPage.filterText("Country") + " / " + clientsPage.filterText("Client Type") + " | " + rowsSummary(rows);
                });
    }

    @DataProvider(name = "statusTiles")
    public Object[][] statusTiles() {
        return new Object[][] { { "X-30", "Active" }, { "X-31", "In-Progress" }, { "X-32", "Inactive" }, { "X-33", "Total" } };
    }

    // X-30..X-33 - The Active / In-Progress / Inactive / Total tiles filter the list by status, and the number on a tile equals the real count.
    @Test(priority = 1220, dataProvider = "statusTiles")
    public void statusTileFiltersTheList(String id, String tile) {
        prepareList(id, G_FILTER, "Status tile '" + tile + "' filters the list by status");
        runCase(id, G_FILTER, "Status tile '" + tile + "' filters the list by status", "Clients > click the '" + tile + "' tile",
                "Only rows with that status; 'of N' equals the number of such clients; no clients -> 'No clients found'", Who.ADMIN, () -> {
                    resetList();
                    int tileValue = clientsPage.tileValue(tile);
                    clientsPage.clickTile(tile);
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    Predicate<ClientsPage.Row> inTile = r -> tile.equals("Total") || r.status.equalsIgnoreCase(tile);
                    long want = count(inTile);
                    int shown = rows.isEmpty() ? 0 : clientsPage.totalFromSummary();
                    for (ClientsPage.Row r : rows) {
                        Assert.assertTrue(inTile.test(r), "Row with another status under the '" + tile + "' tile: " + r);
                    }
                    Assert.assertEquals(shown, want, "'" + tile + "' tile lists " + shown + " client(s) but " + want + " have that status. " + rowsSummary(rows));
                    if (want == 0) {
                        Assert.assertFalse(clientsPage.emptyStateText().isEmpty(), "No message for a status without clients");
                    }
                    if (tileValue != want) {
                        current.defect("The '" + tile + "' tile number differs from the clients that have this status", "Tile = " + want,
                                "Tile shows " + tileValue + " but " + want + " clients have the status");
                    }
                    return "tile " + tileValue + " | expected " + want + " | " + rowsSummary(rows);
                });
    }

    // X-34 - A filter stays applied on page 2.
    @Test(priority = 1221)
    public void filterStaysAcrossPages() {
        prepareList("X-34", G_FILTER, "A filter stays applied on page 2");
        runCase("X-34", G_FILTER, "A filter stays applied on page 2", "Clients > Country 'India', then page 2",
                "Page 2 shows only India clients and 'Showing 11-20 of N'", Who.ADMIN, () -> {
                    resetList();
                    clientsPage.selectFilter("Country", "India");
                    clientsPage.visibleRows();
                    int n = clientsPage.totalFromSummary();
                    if (n <= 10) {
                        throw new SkipException("Fewer than 11 India clients: no page 2");
                    }
                    Assert.assertTrue(clientsPage.gotoPage(2), "No page 2 button");
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    Assert.assertFalse(rows.isEmpty(), "Page 2 is empty");
                    for (ClientsPage.Row r : rows) {
                        Assert.assertEquals(r.country, "India", "Page 2 shows a client outside the filter: " + r);
                    }
                    Assert.assertEquals(clientsPage.totalFromSummary(), n, "'of N' changed on page 2");
                    return rowsSummary(rows);
                });
    }

    // X-35 - Applying a filter while on page 2 returns to page 1.
    @Test(priority = 1222)
    public void applyingAFilterReturnsToPageOne() {
        prepareList("X-35", G_FILTER, "Applying a filter while on page 2 returns to page 1");
        runCase("X-35", G_FILTER, "Applying a filter while on page 2 returns to page 1",
                "Clients > page 2, then Client Type 'AOR' (few clients)", "The first page of the AOR clients is shown ('Showing 1-...'), not an empty page 2", Who.ADMIN, () -> {
                    resetList();
                    if (total <= 10) {
                        throw new SkipException("Fewer than 11 clients: no page 2");
                    }
                    Assert.assertTrue(clientsPage.gotoPage(2), "No page 2 button");
                    clientsPage.visibleRows();
                    clientsPage.selectFilter("Client Type", "AOR");
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    long want = count(r -> r.type.equals("AOR"));
                    Assert.assertEquals(rows.size(), Math.min(10, want), "After filtering from page 2 the list shows " + rows.size()
                            + " row(s) instead of the AOR clients. " + clientsPage.listSummary() + " | " + clientsPage.emptyStateText().replace("\n", " "));
                    return rowsSummary(rows);
                });
    }

    // ================================================================== C3 - Clients table controls: pagination, page size, counters, sorting, Columns

    private static final List<Comparator<String>> ORDERINGS = List.of(
            Comparator.naturalOrder(),
            String.CASE_INSENSITIVE_ORDER,
            (a, b) -> Collator.getInstance(Locale.ENGLISH).compare(a, b));

    private static boolean sorted(List<String> v, boolean ascending) {
        for (Comparator<String> c : ORDERINGS) {
            boolean ok = true;
            for (int i = 1; i < v.size() && ok; i++) {
                int cmp = c.compare(v.get(i - 1), v.get(i));
                ok = ascending ? cmp <= 0 : cmp >= 0;
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    private static List<String> keys(List<ClientsPage.Row> rows, Function<ClientsPage.Row, String> key) {
        List<String> out = new ArrayList<>();
        for (ClientsPage.Row r : rows) {
            out.add(key.apply(r));
        }
        return out;
    }

    // X-40 - Page 2 shows different clients than page 1 and the "Showing" text moves on.
    @Test(priority = 1300)
    public void secondPageShowsDifferentClients() {
        prepareList("X-40", G_TABLE, "Page 2 shows different clients than page 1");
        runCase("X-40", G_TABLE, "Page 2 shows different clients than page 1", "Clients > click page '2' of the pagination",
                "Different rows, and the 'Showing' text moves to 'Showing 11-20 of N'", Who.ADMIN, () -> {
                    resetList();
                    if (total <= 10) {
                        throw new SkipException("Fewer than 11 clients: no page 2");
                    }
                    List<String> first = keys(clientsPage.visibleRows(), r -> r.company);
                    String firstSummary = clientsPage.listSummary();
                    Assert.assertTrue(clientsPage.gotoPage(2), "No page 2 button");
                    List<ClientsPage.Row> second = clientsPage.visibleRows();
                    Assert.assertFalse(second.isEmpty(), "Page 2 is empty");
                    for (ClientsPage.Row r : second) {
                        Assert.assertFalse(first.contains(r.company), "Client '" + r.company + "' appears on page 1 and page 2");
                    }
                    Assert.assertNotEquals(clientsPage.listSummary(), firstSummary, "'Showing' text did not change on page 2");
                    return rowsSummary(second);
                });
    }

    @DataProvider(name = "pageSizes")
    public Object[][] pageSizes() {
        return new Object[][] { { "X-41", 5 }, { "X-42", 10 }, { "X-43", 20 }, { "X-44", 30 }, { "X-45", 40 }, { "X-46", 50 } };
    }

    // X-41..X-46 - Page size 5 / 10 / 20 / 30 / 40 / 50 shows that many rows and the right "Showing 1-n of N".
    @Test(priority = 1301, dataProvider = "pageSizes")
    public void pageSizeIsRespected(String id, int size) {
        prepareList(id, G_TABLE, "Page size " + size + " shows " + size + " clients per page");
        runCase(id, G_TABLE, "Page size " + size + " shows " + size + " clients per page", "Clients > page size '" + size + " / page'",
                "min(" + size + ", N) rows and 'Showing 1-" + size + " of N clients'", Who.ADMIN, () -> {
                    resetList();
                    Assert.assertTrue(clientsPage.hasPageSizeControl(), "No page-size control found");
                    clientsPage.pageSize(size);
                    List<ClientsPage.Row> rows = clientsPage.visibleRows();
                    int want = Math.min(size, total);
                    Assert.assertEquals(rows.size(), want, "Page size " + size + " shows " + rows.size() + " row(s) of " + total + " clients");
                    Matcher m = Pattern.compile("Showing 1\\D+(\\d+) of (\\d+) clients").matcher(clientsPage.listSummary());
                    Assert.assertTrue(m.find(), "Unexpected summary text: '" + clientsPage.listSummary() + "'");
                    Assert.assertEquals(Integer.parseInt(m.group(1)), want, "Summary range end");
                    Assert.assertEquals(Integer.parseInt(m.group(2)), total, "Summary total");
                    return rowsSummary(rows);
                });
    }

    // X-47 - Previous is disabled on page 1, Next is disabled on the last page, and both work in between.
    @Test(priority = 1302)
    public void previousAndNextButtons() {
        prepareList("X-47", G_TABLE, "Previous / Next buttons are enabled only where there is a page to go to");
        runCase("X-47", G_TABLE, "Previous / Next buttons are enabled only where there is a page to go to",
                "Clients (10 per page): check Previous on page 1, click Next to the last page, check Next, click Previous",
                "Previous is disabled on page 1; Next is disabled on the last page ('Showing ...-N of N'); Previous goes back one page", Who.ADMIN, () -> {
                    resetList();
                    if (total <= 10) {
                        throw new SkipException("Only one page of clients");
                    }
                    Assert.assertFalse(clientsPage.isPreviousEnabled(), "Previous is enabled on page 1");
                    Assert.assertTrue(clientsPage.isNextEnabled(), "Next is disabled on page 1 although there are " + total + " clients");
                    int pages = (total + 9) / 10;
                    for (int i = 1; i < pages; i++) {
                        Assert.assertTrue(clientsPage.isNextEnabled(), "Next is disabled on page " + i + " of " + pages);
                        clientsPage.clickNext();
                        clientsPage.visibleRows();
                    }
                    Assert.assertFalse(clientsPage.isNextEnabled(), "Next is still enabled on the last page");
                    Assert.assertTrue(clientsPage.isPreviousEnabled(), "Previous is disabled on the last page");
                    String last = clientsPage.listSummary();
                    int start = (pages - 1) * 10 + 1;
                    Assert.assertTrue(last.contains("Showing " + start) && last.contains("of " + total), "Last page summary is '" + last + "'");
                    clientsPage.clickPrevious();
                    clientsPage.visibleRows();
                    return "Last page: " + last + " | after Previous: " + clientsPage.listSummary();
                });
    }

    // X-48 - The tile numbers equal the clients with that status (known bug: "Pending" clients have no tile, so the tiles do not add up to Total).
    @Test(priority = 1303)
    public void tilesMatchTheClientList() {
        prepareList("X-48", G_TABLE, "Stat tiles (Total / Active / Inactive / In-Progress) match the clients in the list");
        runCase("X-48", G_TABLE, "Stat tiles (Total / Active / Inactive / In-Progress) match the clients in the list",
                "Clients > read the four tiles and compare them with all clients (50 per page)",
                "Total = number of clients; each tile = number of clients with that status; the tiles add up to Total", Who.ADMIN, () -> {
                    resetList();
                    int tTotal = clientsPage.tileValue("Total");
                    int tActive = clientsPage.tileValue("Active");
                    int tInactive = clientsPage.tileValue("Inactive");
                    int tProgress = clientsPage.tileValue("In-Progress");
                    long cActive = count(r -> r.status.equalsIgnoreCase("Active"));
                    long cInactive = count(r -> r.status.equalsIgnoreCase("Inactive"));
                    long cProgress = count(r -> r.status.equalsIgnoreCase("In-Progress"));
                    long cOther = total - cActive - cInactive - cProgress;
                    String facts = "tiles Total " + tTotal + " / Active " + tActive + " / Inactive " + tInactive + " / In-Progress " + tProgress
                            + " | clients " + total + ": Active " + cActive + ", Inactive " + cInactive + ", In-Progress " + cProgress
                            + ", other (Pending) " + cOther;
                    Assert.assertEquals(tTotal, total, "Total tile differs from the number of clients. " + facts);
                    Assert.assertEquals(tActive, cActive, "Active tile differs from the list. " + facts);
                    Assert.assertEquals(tInactive, cInactive, "Inactive tile differs from the list. " + facts);
                    Assert.assertEquals(tProgress, cProgress, "In-Progress tile differs from the list. " + facts);
                    if (tActive + tInactive + tProgress != tTotal) {
                        current.defect("Stat tiles do not add up to Total: clients with status 'Pending' have no tile",
                                "Tiles that account for every client (or a 'Pending' tile)", facts);
                    }
                    return facts;
                });
    }

    @DataProvider(name = "sortColumns")
    public Object[][] sortColumns() {
        return new Object[][] {
            { "X-50", "Company", (Function<ClientsPage.Row, String>) r -> r.company },
            { "X-51", "POC Name", (Function<ClientsPage.Row, String>) r -> r.poc },
            { "X-52", "Client Type", (Function<ClientsPage.Row, String>) r -> r.type },
            { "X-53", "Country", (Function<ClientsPage.Row, String>) r -> r.country },
            { "X-54", "Status", (Function<ClientsPage.Row, String>) r -> r.status },
        };
    }

    // X-50..X-54 - Clicking a column header (Company, POC Name, Client Type, Country, Status) sorts ascending, then descending, then back to the default order.
    @Test(priority = 1304, dataProvider = "sortColumns")
    public void headerSortsAscendingDescendingThenDefault(String id, String header, Function<ClientsPage.Row, String> key) {
        prepareList(id, G_TABLE, "Sorting by '" + header + "': ascending, descending, then back to the default order");
        runCase(id, G_TABLE, "Sorting by '" + header + "': ascending, descending, then back to the default order",
                "Clients > click the '" + header + "' header three times (10 per page)",
                "1st click ascending, 2nd descending, 3rd the original order", Who.ADMIN, () -> {
                    resetList();
                    List<String> original = keys(clientsPage.visibleRows(), key);
                    clientsPage.sortBy(header);
                    List<String> asc = keys(clientsPage.visibleRows(), key);
                    current.evidence("ascending");
                    Assert.assertTrue(sorted(asc, true), header + " is not ascending after the first click: " + asc);
                    clientsPage.sortBy(header);
                    List<String> desc = keys(clientsPage.visibleRows(), key);
                    Assert.assertTrue(sorted(desc, false), header + " is not descending after the second click: " + desc);
                    clientsPage.sortBy(header);
                    List<String> back = keys(clientsPage.visibleRows(), key);
                    Assert.assertEquals(back, original, "The third click does not restore the default order");
                    return "asc " + asc.subList(0, Math.min(3, asc.size())) + " | desc " + desc.subList(0, Math.min(3, desc.size())) + " | default restored";
                });
    }

    // X-55 - Sorting by Company orders ALL clients, not only the 10 rows of the current page (known bug: the sort is page-local).
    @Test(priority = 1305)
    public void sortCoversAllClientsNotOnlyThePage() {
        prepareList("X-55", G_TABLE, "Sorting by Company orders ALL clients, not only the 10 rows of the current page");
        runCase("X-55", G_TABLE, "Sorting by Company orders ALL clients, not only the 10 rows of the current page",
                "Clients (10 per page) > click the 'Company' header once and compare page 1 with the first 10 of ALL companies sorted",
                "Page 1 holds the 10 alphabetically first companies of the whole list", Who.ADMIN, () -> {
                    resetList();
                    if (total <= 10) {
                        throw new SkipException("Only one page of clients");
                    }
                    List<String> all = keys(allRows, r -> r.company);
                    List<String> defaultFirst10 = all.subList(0, 10);
                    clientsPage.sortBy("Company");
                    List<String> page1 = keys(clientsPage.visibleRows(), r -> r.company);
                    current.evidence("page1_after_sort");
                    List<String> globalFacts = new ArrayList<>();
                    for (Comparator<String> c : ORDERINGS) {
                        List<String> globalSorted = new ArrayList<>(all);
                        globalSorted.sort(c);
                        if (page1.equals(globalSorted.subList(0, 10))) {
                            return "Page 1 equals the first 10 of all " + total + " companies sorted: " + page1.subList(0, 3) + "...";
                        }
                        globalFacts.add(globalSorted.subList(0, 3).toString());
                    }
                    for (Comparator<String> c : ORDERINGS) {
                        List<String> localSorted = new ArrayList<>(defaultFirst10);
                        localSorted.sort(c);
                        if (page1.equals(localSorted)) {
                            current.defect("Column sort only reorders the 10 rows of the current page, not all clients",
                                    "Page 1 shows the alphabetically first 10 of all " + total + " companies (e.g. " + globalFacts.get(0) + ")",
                                    "Page 1 shows " + page1.subList(0, 3) + "... = the previously visible 10 rows sorted among themselves");
                            return "Page-local sort: page 1 = the default first 10 rows re-ordered: " + page1;
                        }
                    }
                    Assert.fail("Page 1 after sorting matches neither a global nor a page-local ordering: " + page1
                            + " | global first rows under the tried orderings: " + globalFacts);
                    return "";
                });
    }

    @DataProvider(name = "columns")
    public Object[][] columns() {
        return new Object[][] { { "X-60", "Company" }, { "X-61", "POC Name" }, { "X-62", "Client Type" }, { "X-63", "Country" },
            { "X-64", "Mobile Number" }, { "X-65", "Status" } };
    }

    // X-60..X-65 - The Columns menu hides and shows each column (Company, POC Name, Client Type, Country, Mobile Number, Status); the other columns stay untouched.
    @Test(priority = 1306, dataProvider = "columns")
    public void columnCanBeHiddenAndShown(String id, String column) {
        prepareList(id, G_TABLE, "Columns menu: hide and show the '" + column + "' column");
        runCase(id, G_TABLE, "Columns menu: hide and show the '" + column + "' column", "Clients > Columns > untick '" + column + "', then tick it again",
                "The column header and the cells disappear, then come back; the other columns are untouched", Who.ADMIN, () -> {
                    resetList();
                    List<String> before = clientsPage.headerTexts();
                    int cellsBefore = clientsPage.firstRowCellCount();
                    try {
                        clientsPage.toggleColumn(column);
                        List<String> hidden = clientsPage.headerTexts();
                        current.evidence("hidden");
                        Assert.assertTrue(hidden.stream().noneMatch(h -> h.equalsIgnoreCase(column)), "Header '" + column + "' is still shown: " + hidden);
                        Assert.assertEquals(hidden.size(), before.size() - 1, "Only one header should disappear: " + before + " -> " + hidden);
                        Assert.assertEquals(clientsPage.firstRowCellCount(), cellsBefore - 1, "The row still has the hidden column's cell");
                    } finally {
                        if (clientsPage.headerTexts().stream().noneMatch(h -> h.equalsIgnoreCase(column))) {
                            clientsPage.toggleColumn(column);
                        }
                    }
                    List<String> shown = clientsPage.headerTexts();
                    Assert.assertEquals(shown, before, "Headers after showing the column again");
                    Assert.assertEquals(clientsPage.firstRowCellCount(), cellsBefore, "Cells after showing the column again");
                    return "Hidden and restored; headers " + shown;
                });
    }

    // ================================================================== C4 - MSA & Contract list search, C5 - API health of the admin console

    /** The term typed into the MSA search box by {@link #msaSearch}. */
    private String msaTerm = "";

    /**
     * Client names of the MSA list once it stopped changing (max 12 s): while it re-renders it shows blank placeholder rows. A SLOW search leaves the
     * old list unchanged for a while, so the list only counts as settled when it is empty or a row contains the searched term (seen: X-71 read the
     * old list before the no-match search was applied).
     */
    private List<String> msaRows() {
        List<String> last = null;
        long stableSince = System.currentTimeMillis();
        long end = System.currentTimeMillis() + 12_000;
        String term = msaTerm.trim().toLowerCase(Locale.ROOT);
        while (System.currentTimeMillis() < end) {
            List<String> now = readMsaRows();
            boolean placeholders = now.stream().anyMatch(String::isEmpty);
            boolean showsTerm = term.isEmpty() || now.isEmpty() || now.stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).contains(term));
            if (!now.equals(last)) {
                last = now;
                stableSince = System.currentTimeMillis();
            } else if (!placeholders && showsTerm && System.currentTimeMillis() - stableSince > 1500) {
                return now;
            }
            try {
                Thread.sleep(300); // documented: the list re-renders after typing
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last == null ? new ArrayList<>() : last;
    }

    private List<String> readMsaRows() {
        List<String> names = new ArrayList<>();
        try {
            for (WebElement tr : driver.findElements(By.xpath("//tbody/tr"))) {
                List<WebElement> td = tr.findElements(By.tagName("td"));
                if (td.size() >= 5) {
                    names.add(td.get(0).getText().trim());
                }
            }
        } catch (StaleElementReferenceException rerendered) {
            // the table re-rendered while it was read: the next poll reads it again
        }
        return names;
    }

    private void msaSearch(String term) {
        msaTerm = term == null ? "" : term;
        WebElement e = driver.findElement(By.xpath("//input[@placeholder='Search by client name']"));
        e.sendKeys(org.openqa.selenium.Keys.chord(org.openqa.selenium.Keys.CONTROL, "a"), org.openqa.selenium.Keys.BACK_SPACE);
        if (!term.isEmpty()) {
            e.sendKeys(term);
        }
    }

    // X-70 - MSA & Contract search by client name lists only that client.
    @Test(priority = 1420)
    public void msaSearchFilters() {
        runCase("X-70", G_MSA, "MSA & Contract search by client name filters the list",
                "MSA & Contract > MSA, search box = the name of an ACTIVE client", "Only that client's row(s) are listed", Who.ADMIN, this::activeClient, () -> {
                    String name = activeClient();
                    new EorMsaPage(driver).open();
                    msaSearch(name);
                    List<String> rows = msaRows();
                    Assert.assertFalse(rows.isEmpty(), "MSA search found nothing for the existing client '" + name + "'");
                    for (String r : rows) {
                        Assert.assertTrue(r.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT)), "MSA list shows a non-matching client: " + r);
                    }
                    return "Search '" + name + "': " + rows.size() + " row(s): " + rows;
                });
    }

    // X-71 - MSA search without a match shows no rows and a message.
    @Test(priority = 1421)
    public void msaSearchNoMatch() {
        String term = "zzz-no-such-client-" + System.currentTimeMillis();
        runCase("X-71", G_MSA, "MSA & Contract search with no match shows no rows and a message",
                "MSA & Contract > MSA, search box = '" + term + "'", "No rows and a readable empty-state message", Who.ADMIN, () -> {
                    new EorMsaPage(driver).open();
                    msaSearch(term);
                    List<String> rows = msaRows();
                    Assert.assertTrue(rows.isEmpty(), "A no-match MSA search still lists rows: " + rows);
                    String body = driver.findElement(By.tagName("body")).getText().toLowerCase(Locale.ROOT);
                    if (!body.matches("(?s).*(no (results|clients|msa|data|records)|nothing|not found|no match).*")) {
                        current.defect("No empty-state message for an MSA search without results", "A message such as 'No MSAs found'",
                                "Page text contains no empty-state wording");
                    }
                    return "0 rows";
                });
    }

    // X-72 - MSA search text <script>alert(1)</script> is handled safely (no script, no 5xx answer).
    @Test(priority = 1422)
    public void msaSearchSpecialCharacters() {
        runCase("X-72", G_MSA, "MSA search text '<script>alert(1)</script>' is handled safely",
                "MSA & Contract > MSA, search box = '<script>alert(1)</script>'", "No script runs, no server error, no match or only matching rows", Who.ADMIN, () -> {
                    new EorMsaPage(driver).open();
                    current.observeApi();
                    msaSearch("<script>alert(1)</script>");
                    List<String> rows = msaRows();
                    assertNoAlert();
                    List<ApiCall> calls = current.observeApi();
                    for (ApiCall c : calls) {
                        Assert.assertFalse(c.isServerError() || c.error != null, "Server error while searching the MSA list: " + c);
                    }
                    return rows.size() + " row(s) | API calls: " + calls.size();
                });
    }

    // X-80 - Browsing the admin console (Dashboard, Clients, MSA, Candidates, Appraisal) produces no 5xx API answers.
    @Test(priority = 1430)
    public void adminBrowsingHasNoServerErrors() {
        runCase("X-80", G_API, "Browsing the admin console produces no 5xx API responses",
                "Signed in as admin: Dashboard, Clients, MSA & Contract > MSA, Candidates, Appraisal (read-only)",
                "Every API call returns 2xx/3xx (no 500/502/503, no failed requests)", Who.ADMIN, () -> {
                    current.observeApi();
                    PageProbe.open(driver, base() + "/", 10);
                    clientsPage.open();
                    clientsPage.visibleRows();
                    new EorMsaPage(driver).open();
                    driver.findElement(By.xpath("//a[@href='/candidates']")).click();
                    PageProbe.settle(driver, "candidates", 8);
                    driver.findElement(By.xpath("//a[@href='/appraisal']")).click();
                    PageProbe.settle(driver, "appraisal", 8);
                    return apiCheck(current.observeApi());
                });
    }

    // ================================================================== B-24 - the last case: it ends the admin session

    // B-24 - LAST case (it ends the admin session): after signing out, the browser Back button must not show protected content.
    @Test(priority = 1500)
    public void backAfterSignOutDoesNotShowProtectedPage() {
        runCase("B-24", G_SESSION, "After signing out, browser Back does not show protected content",
                "Signed in as admin on /clients, sign out via the profile menu, press the browser Back button",
                "Login page (or a signed-out state); the Clients list is not shown", Who.ADMIN, () -> {
                    PageProbe.open(driver, base() + "/clients", 10);
                    if (!signOut()) {
                        throw new SkipException("Could not sign out: no 'Sign out' control or the login page did not appear");
                    }
                    session = Who.NONE;
                    driver.navigate().back();
                    Landing l = PageProbe.settle(driver, "browser Back after sign out", 8);
                    Assert.assertFalse(l.adminContent, "Protected admin content is shown after sign out + Back. " + l.describe());
                    return l.describe();
                });
    }

    /**
     * Opens the profile menu with a REAL click (the menu opens on pointer events, a script click does nothing) and clicks its
     * Sign out / Log out item, then confirms the 'Log out?' dialog. The menu items found are listed when none matches.
     */
    private boolean signOut() {
        driver.findElement(By.xpath("(//button[contains(normalize-space(.),'@')])[last()]")).click();
        try {
            new WebDriverWait(driver, Duration.ofSeconds(5)).until(d -> !d.findElements(By.xpath("//*[@role='menu']")).isEmpty());
        } catch (org.openqa.selenium.TimeoutException e) {
            throw new AssertionError("The profile menu did not open after clicking the profile button.");
        }
        List<String> seen = new ArrayList<>();
        By items = By.xpath("//*[@role='menuitem' or @role='menu']//*[self::div or self::button or self::a or self::span]");
        for (WebElement e : driver.findElements(items)) {
            String t = e.getText().trim();
            if (!t.isEmpty()) {
                seen.add(t.replace("\n", " "));
            }
            if (t.matches("(?i)(sign ?out|log ?out)")) {
                e.click();
                By confirm = By.xpath("//div[@role='dialog']//button[normalize-space()='Log out']");
                try {
                    new WebDriverWait(driver, Duration.ofSeconds(5)).until(ExpectedConditions.elementToBeClickable(confirm));
                    driver.findElement(confirm).click();
                } catch (org.openqa.selenium.TimeoutException noDialog) {
                    // signed out without a confirmation
                }
                try {
                    new WebDriverWait(driver, Duration.ofSeconds(10)).until(d -> !d.findElements(By.id("password")).isEmpty());
                    return true;
                } catch (org.openqa.selenium.TimeoutException e2) {
                    return false;
                }
            }
        }
        throw new AssertionError("No 'Sign out' / 'Log out' item in the profile menu. Items seen: " + seen);
    }

    // ================================================================== generated test files (under target/, never committed)

    private Path testFile(String key) throws IOException {
        switch (key) {
            case "not_a_document.txt" -> {
                return write("not_a_document.txt", "This is plain text, not a company registration document.".getBytes(StandardCharsets.UTF_8));
            }
            case "fake_program.exe" -> {
                return write("fake_program.exe", "MZ - harmless text file with an .exe extension".getBytes(StandardCharsets.UTF_8));
            }
            case "empty.pdf" -> {
                return write("empty.pdf", new byte[0]);
            }
            case "text_renamed.pdf" -> {
                return write("text_renamed.pdf", "Plain text saved with a .pdf extension".getBytes(StandardCharsets.UTF_8));
            }
            case "oversize_21MB.pdf" -> {
                Path p = copyOf(data.cinDocPath, "oversize_21MB.pdf");
                try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
                    raf.setLength(21L * 1024 * 1024); // valid PDF header/body followed by padding
                }
                return p;
            }
            case "LONG" -> {
                return copyOf(data.cinDocPath, "L".repeat(176) + ".pdf");
            }
            default -> throw new IllegalArgumentException(key);
        }
    }

    private Path write(String name, byte[] content) throws IOException {
        Path p = files.resolve(name);
        try (OutputStream os = Files.newOutputStream(p)) {
            os.write(content);
        }
        return p.toAbsolutePath();
    }

    private Path copyOf(String source, String name) throws IOException {
        Path p = files.resolve(name);
        Files.copy(Paths.get(source), p, StandardCopyOption.REPLACE_EXISTING);
        return p.toAbsolutePath();
    }
}
