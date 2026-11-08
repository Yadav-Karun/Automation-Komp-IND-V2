package tests.client;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import api.ApiCall;
import base.BaseTest;
import flow.ClientOnboardingFlow;
import model.ClientData;
import pages.ClientPortalHomePage;
import pages.ClientProfilePage;
import pages.ClientsPage;
import pages.EorMsaPage;
import pages.LoginPage;
import reporting.ExecutionInfo;
import reporting.TestReporter;
import reporting.ValueChecks;
import utils.ConfigManager;
import utils.TestDataGenerator;

/**
 * CLIENT ONBOARDING - E2E HAPPY FLOW.  ONE client is created (all e-mail addresses are yopmail) and taken to the end:
 *
 *   TC-E2E-01  register -> welcome mail -> first login + new password -> Organization Details + documents -> Points of Contact
 *              -> Review -> MSA generation + signing -> EOR approval -> the client is ACTIVE (17 phases, one case)
 *   then, on that same client, "go to the client, open its profile and match everything that is visible", in THIS order
 *   (one case per page; every value of a page is one row of ONE table, so a wrong value reads "2 of 27 values are wrong, the other 25 are correct"):
 *   TC-E2E-02  admin: MSA & Contract > MSA, search the client -> the MSA is ACTIVE (client, MSA id, start date, country, status)
 *   TC-E2E-03  admin: Clients list, search the client -> the client is Active (company, e-mail, contact, type, country, mobile, status)
 *   TC-E2E-04  admin: the client's profile - header, summary tiles, About Organization, Mailing Address, Legal Details incl. the two documents
 *   TC-E2E-05  admin: profile > Point of Contact tab - Business, Finance, HR
 *   TC-E2E-06  admin: profile > MSA tab - the signed document is listed, Preview opens a PDF, Download is enabled
 *   TC-E2E-07  the client signs in: portal home, top-right profile button and the My Profile dialog
 *   TC-E2E-08  the client portal, every tab of its menu: Home, Employees, Projects, MSA
 *
 * The numbering always starts at TC-E2E-01: when an existing client is re-checked (see below) the journey case is written as SKIP, not left out.
 * The data of the run is saved as reports/&lt;this class&gt;/&lt;run folder&gt;/test-data.json (no passwords).
 *
 * Run (visible browser):   mvn clean test -Dsuite=testng-client-happy-flow.xml
 * Re-check the client of an earlier run WITHOUT creating a client:
 *                          mvn clean test -Dsuite=testng-client-happy-flow.xml -Dhappy.client.data=reports/&lt;folder&gt;/test-data.json
 */
public class ClientOnboardingE2EHappyFlow extends BaseTest {

    private static final String G_JOURNEY = "Client journey";
    private static final String G_VERIFY = "Check of the finished client";

    private static final String T_JOURNEY = "Client onboarding from start to finish: register, set up in the client portal, sign the MSA, approval, the client becomes Active";
    private static final String T_MSA_LIST = "MSA & Contract: search the client, its MSA is ACTIVE";
    private static final String T_CLIENTS = "Clients list: search the client, its status is Active";
    private static final String T_PROFILE = "Admin client profile: every value (header, tiles, company details, documents)";
    private static final String T_POC = "Admin client profile > Point of Contact tab: every value";
    private static final String T_MSA_TAB = "Admin client profile > MSA tab: the signed MSA";
    private static final String T_CLIENT_PROFILE = "Client signs in: portal home and My Profile show the registered details";
    private static final String T_PORTAL_TABS = "Client portal: every tab (Home, Employees, Projects, MSA)";

    private enum Who { NONE, ADMIN, CLIENT }

    private LoginPage login;
    private ClientsPage clients;
    private ClientProfilePage profile;
    private ClientPortalHomePage portal;

    private ClientData data;
    private boolean clientActive;
    private Who session = Who.NONE;

    @Override
    protected void browserReady() {
        login = new LoginPage(driver);
        clients = new ClientsPage(driver);
        profile = new ClientProfilePage(driver);
        portal = new ClientPortalHomePage(driver);
        session = Who.NONE; // a new browser has no sessions
    }

    /** -Dhappy.client.data=<test-data.json of an earlier run>: check that client, create nothing. */
    private static Path verifyOnlyFile() {
        String p = ConfigManager.get("happy.client.data");
        return p == null ? null : Paths.get(p);
    }

    // ================================================================== TC-E2E-01 the journey

    // TC-E2E-01 - THE WHOLE HAPPY JOURNEY on ONE new client (the only client this class creates). 17 phases, one report case.
    // The admin registers the client, the client reads its welcome mail, signs in, sets a password, fills Organization Details + documents and the three
    // Points of Contact, checks the Review page, generates and signs the MSA; then the EOR user refreshes the status to IN REVIEW and approves it.
    // Expected: the client ends ACTIVE and its portal shows Company Onboarding 100%. A failed phase stops the journey; a mismatch found by a soft check is
    // reported as a defect and the journey goes on. The data of the run is saved to test-data.json (no passwords).
    // When an existing client is re-checked (-Dhappy.client.data) this case is written as SKIP (so the numbering still starts at 1) and nothing is created.
    @Test(priority = 1)
    public void tc01_registerThroughActive() {
        if (verifyOnlyFile() != null) {
            data = TestDataGenerator.load(verifyOnlyFile());
            data.password = ConfigManager.clientPassword(); // a happy-flow client always ends with the common client password
            clientActive = true;
            TestReporter skipped = TestReporter.start("TC-E2E-01", G_JOURNEY, T_JOURNEY, "The whole client onboarding on one new client, from registration to the status Active", "-",
                    "The whole journey is completed and the client ends up Active (17 steps)", driver, data);
            Path folder = verifyOnlyFile().toAbsolutePath().getParent();
            SkipException skip = new SkipException("Not run this time: the existing client '" + data.legalName + "' is only re-checked (from TC-E2E-02 on) and nothing new is created. "
                    + "Its journey was run earlier" + (folder == null ? "" : " (report folder " + folder.getFileName() + ")") + ".");
            skipped.finish("SKIP", skip);
            throw skip;
        }
        data = TestDataGenerator.generate();
        TestReporter rep = TestReporter.start("TC-E2E-01", G_JOURNEY, T_JOURNEY,
                "The whole client onboarding on one new client, from registration to the status Active",
                "Valid, unique generated client data (all yopmail addresses)",
                "The whole journey is completed and the client ends up Active (17 steps)", driver, data);
        ClientOnboardingFlow flow = new ClientOnboardingFlow(driver, rep, data);
        try {
            // Phase 1 - Admin signs in: the login page loads, the credentials are accepted, the dashboard opens.
            rep.phase("admin_login", "Admin login and dashboard",
                    "Login page loads, credentials accepted, dashboard visible", flow::adminLogin);
            // Phase 2 - Admin registers the client (Clients > Register Client): success toast, and the Clients list shows the submitted data with status Pending.
            rep.phase("client_registration", "Register client",
                    "Success confirmation shown; client listed with the submitted data and status Pending", flow::registerClient);
            TestDataGenerator.save(data, ExecutionInfo.root().resolve("test-data.json")); // the client exists now: keep its data
            // Phase 3 - Welcome e-mail in Yopmail: addressed to the client, shows the login e-mail, has the portal link and the temporary password (shown in the report; hidden only with -Dmask.secrets=true).
            rep.phase("welcome_email", "Welcome email (Yopmail)",
                    "The welcome e-mail arrives with the portal link and the temporary password", flow::verifyWelcomeEmail);
            // Phase 4 - The client opens the portal link and signs in with the temporary password: the "Set a new password" dialog appears.
            rep.phase("client_portal_login", "Client portal login with temporary password",
                    "Portal link opens; temporary-password login succeeds and asks for a new password", flow::clientPortalLogin);
            // Phase 5 - The client sets the permanent password, is signed out and signs in again with it.
            rep.phase("password_reset", "Reset client password",
                    "Password updated; login with the new password succeeds", flow::resetPassword);
            // Phase 6 - Organization Details (wizard step 1): the pre-filled name matches; the client enters years, website, description, pincode, address, company type, CIN, GST.
            rep.phase("organization_details", "Complete organization details",
                    "Required information accepted and reflected in the form", flow::completeOrganizationDetails);
            // Phase 7 - The CIN document is uploaded and shown.
            rep.phase("cin_upload", "Upload CIN document", "CIN document uploaded and displayed", flow::uploadCinDocument);
            // Phase 8 - The Tax ID (GST) document is uploaded and shown.
            rep.phase("tax_id_upload", "Upload Tax ID (GST) document", "Tax ID document uploaded and displayed", flow::uploadTaxIdDocument);
            // Phase 9 - Points of Contact (wizard step 2): Business, Finance and HR contacts are entered and accepted.
            rep.phase("point_of_contact", "Complete Points of Contact",
                    "Business, Finance and HR contacts completed", flow::completePointsOfContact);
            // Phase 10 - Review page (wizard step 3): every value equals what was entered; the mailing address equals the registered address (soft checks).
            rep.phase("review_information", "Review information",
                    "Review page values match the submitted data", flow::reviewInformation);
            // Phase 11 - "Confirm & Continue": the MSA is generated (waits for the viewer) and is a retrievable PDF.
            rep.phase("msa_generation", "Generate MSA",
                    "MSA generated, accessible, and signing can be initiated", flow::generateMsa);
            // Phase 12 - The MSA is signed (completes by itself in the sandbox): the success confirmation is shown.
            rep.phase("msa_signing", "Complete MSA signing",
                    "Signing succeeds and a success confirmation is displayed", flow::signMsa);
            // Phase 13 - After "Done" the client is signed out; signing in again shows onboarding below 100% (not active until the EOR approves).
            rep.phase("client_after_signing", "Client state after signing",
                    "Client onboarding submitted but client not yet active", flow::clientStateAfterSigning);
            // Phase 14 - EOR user signs in, MSA & Contract > MSA, finds the client with status PENDING and a valid MSA id.
            rep.phase("eor_client_lookup", "EOR login and MSA lookup",
                    "EOR portal accessible; new client found in MSA list as PENDING", flow::eorLoginAndFindMsa);
            // Phase 15 - "Refresh sign status" moves the MSA to IN REVIEW and the "Update client status" dialog opens.
            rep.phase("client_in_review", "Refresh to In Review",
                    "Refresh changes the status to IN REVIEW and the Update Client Status window opens", flow::refreshToInReview);
            // Phase 16 - "Approve & Activate": the MSA becomes ACTIVE with a start date and the Clients list shows the client as Active.
            rep.phase("client_active", "Approve and activate",
                    "Approval succeeds; client status is Active in MSA and Clients lists", () -> {
                        String result = flow.approveAndActivate();
                        clientActive = true; // the client IS active now: the checks below are meaningful
                        return result;
                    });
            session = Who.ADMIN;
            // Phase 17 - The client signs in again: Company Onboarding shows 100% completed.
            rep.phase("client_onboarding_complete", "Client confirms onboarding complete",
                    "Client portal shows onboarding 100% completed", () -> {
                        String result = flow.clientSeesOnboardingComplete();
                        session = Who.CLIENT;
                        return result;
                    });
        } finally {
            TestDataGenerator.save(data, ExecutionInfo.root().resolve("test-data.json")); // adds state / city / MSA id
        }
        Assert.assertTrue(rep.defects().isEmpty(),
                "The journey was completed but " + rep.defects().size() + " problem(s) were found:\n - " + String.join("\n - ", rep.defects()));
    }

    // ================================================================== TC-E2E-02 .. TC-E2E-06 the admin side

    // TC-E2E-02 - Admin: MSA & Contract > MSA, search the client by name. The row must be there and show the MSA id of the onboarding, a start date, the
    // country and the status ACTIVE. (The admin signs in first: that is a step of its own in the report.)
    @Test(priority = 2)
    public void tc02_msaListShowsActive() {
        requireActiveClient("TC-E2E-02", T_MSA_LIST);
        TestReporter rep = TestReporter.start("TC-E2E-02", G_VERIFY, T_MSA_LIST,
                "Admin console > MSA & Contract > MSA > search box = the client's name", "Sign in as the admin, search the client in the MSA list",
                "The client's row is listed with its MSA id, a start date, its country and the status ACTIVE", driver, data);
        ClientOnboardingFlow flow = new ClientOnboardingFlow(driver, rep, data);
        rep.phase("tc02_admin_login", "Sign in as the admin", "Login accepted, the admin console opens", () -> {
            String result = flow.adminLoginFresh();
            session = Who.ADMIN;
            return result;
        });
        rep.phaseChecks("tc02_msa_list", "MSA & Contract > MSA: search the client", "The client's MSA row shows status ACTIVE", c -> {
            EorMsaPage msa = new EorMsaPage(driver);
            msa.open();
            msa.searchClient(data.legalName);
            c.check("MSA list", "Search result is on the screen", msa.searchApplied(), "the list is narrowed to the searched client", msa.listSummary());
            List<String> cells = msa.rowTexts(data.legalName);
            c.eq("MSA list row", "Client", data.legalName, cells.get(0));
            if (data.msaId != null) {
                c.eq("MSA list row", "MSA ID", data.msaId, cells.get(1));
            } else {
                c.check("MSA list row", "MSA ID", !cells.get(1).isEmpty(), "an MSA id (it was not recorded by an earlier run)", cells.get(1));
            }
            c.check("MSA list row", "Start date", cells.get(2).matches(".*\\d.*"), "a date (the day the client was approved)", cells.get(2));
            c.eq("MSA list row", "Country", data.country, cells.get(3));
            c.eq("MSA list row", "Status", "ACTIVE", cells.get(4));
        });
        assertNoDefects(rep);
    }

    // TC-E2E-03 - Admin: Clients list, search the client by name. The row shows the registered data and the status Active.
    @Test(priority = 3)
    public void tc03_clientsListShowsActive() {
        requireActiveClient("TC-E2E-03", T_CLIENTS);
        ensureAdmin();
        TestReporter rep = TestReporter.start("TC-E2E-03", G_VERIFY, T_CLIENTS,
                "Admin console > Clients > search box = the client's name", "Search the client in the Clients list",
                "The client's row shows company, e-mail, contact, client type, country, mobile number and the status Active", driver, data);
        rep.phaseChecks("tc03_clients_list", "Clients list: search the client", "The client's row shows status Active", c -> {
            clients.open();
            ClientsPage.Row r = clients.findClient(data.legalName);
            c.check("Clients list", "Search result is on the screen", clients.searchApplied(), "the list is narrowed to the searched client", clients.listSummary());
            c.eq("Clients list row", "Company", data.legalName, r.company);
            c.eq("Clients list row", "E-mail (under the company name)", data.email, r.email);
            c.eq("Clients list row", "Contact person name", data.pocName, r.poc);
            c.eq("Clients list row", "Client type", data.clientType, r.type);
            c.eq("Clients list row", "Country", data.country, r.country);
            c.eq("Clients list row", "Mobile number", data.mobile, r.mobile);
            c.eq("Clients list row", "Status", "Active", r.status);
        });
        assertNoDefects(rep);
    }

    // TC-E2E-04 - Admin: open the client's profile (click its row) and check EVERY value of the page in one table: heading + type, status, e-mail, the four tiles
    // (name, phone, company no = CIN, MSA id) and the cards of the tab "Company Details": About Organization, Mailing Address, Legal Details with the two uploaded
    // documents. State / city are only compared with what the form itself showed (the pincode lookup is not judged).
    // KNOWN BUG: the two document rows read "Not uploaded" -> the case FAILS, naming those two values, and the other values are shown as correct.
    @Test(priority = 4)
    public void tc04_adminProfile() {
        requireActiveClient("TC-E2E-04", T_PROFILE);
        ensureAdmin();
        TestReporter rep = TestReporter.start("TC-E2E-04", G_VERIFY, T_PROFILE,
                "The client's profile page in the admin area (Clients > the client's row), tab 'Company Details'",
                "Heading, status, e-mail, four tiles, the cards About Organization / Mailing Address / Legal Details and the CIN / GST documents (uploaded: "
                        + fileName(data.cinDocPath) + ", " + fileName(data.taxDocPath) + ")",
                "Every value equals what was entered; the two documents are shown (file name or a link to open), not 'Not uploaded'", driver, data);
        rep.phaseChecks("tc04_admin_profile", "Open the client profile and check every value", "All values of the profile page match the onboarding data", c -> {
            profile.open(clients, data.legalName);
            waitTilesFilled();
            c.eq("Header", "Company name (type)", data.legalName + "(" + data.clientType + ")", profile.titleText().replaceAll("\\s+\\(", "("));
            c.eq("Header", "Status label", "Active", profile.statusText());
            c.eq("Header", "E-mail under the heading", data.email, profile.headerEmail());
            Map<String, String> t = profile.summary();
            c.eq("Summary tiles", "NAME (point of contact)", data.pocName, t.get("name"));
            c.eq("Summary tiles", "PHONE NO.", data.mobile, t.get("phone no."));
            c.eq("Summary tiles", "COMPANY NO (= CIN)", data.cin, t.get("company no"));
            if (data.msaId != null) {
                c.eq("Summary tiles", "MSA ID", data.msaId, t.get("msa id"));
            }
            profile.openTab("Company Details");
            Map<String, Map<String, String>> cards = profile.waitForCards();
            Map<String, String> about = card(c, cards, "About Organization");
            c.eq("About Organization", "Company name", data.legalName, find(about, "company name"));
            c.eq("About Organization", "Years in industry", data.yearsInIndustry, find(about, "years in industry"));
            c.eq("About Organization", "Product or service", data.description, find(about, "product or service"));
            c.eq("About Organization", "Website", data.website, find(about, "website"));
            c.eq("About Organization", "Pincode / zipcode", data.pincode, find(about, "pincode"));
            c.eq("About Organization", "Country", data.country, find(about, "country"));
            if (data.selectedState != null) {
                c.eq("About Organization", "State / region (as the form showed it)", data.selectedState, find(about, "state"));
            }
            if (data.selectedCity != null) {
                c.eq("About Organization", "City (as the form showed it)", data.selectedCity, find(about, "city"));
            }
            c.eq("About Organization", "Street address", data.address, find(about, "street address"));
            Map<String, String> mail = card(c, cards, "Mailing Address");
            c.eq("Mailing Address", "Pincode / zipcode", data.pincode, find(mail, "pincode"));
            c.eq("Mailing Address", "Country", data.country, find(mail, "country"));
            if (data.selectedState != null) {
                c.eq("Mailing Address", "State / region", data.selectedState, find(mail, "state"));
            }
            if (data.selectedCity != null) {
                c.eq("Mailing Address", "City", data.selectedCity, find(mail, "city"));
            }
            c.eq("Mailing Address", "Street address", data.address, find(mail, "street address"));
            Map<String, String> legal = card(c, cards, "Legal Details");
            c.eq("Legal Details", "Company type", data.companyType, find(legal, "company type"));
            c.eq("Legal Details", "E-mail", data.email, find(legal, "email"));
            c.eq("Legal Details", "Mobile number", data.mobile, find(legal, "mobile"));
            c.eq("Legal Details", "CIN", data.cin, find(legal, "cin"));
            document(c, legal, "Upload CIN Document", "upload cin document", data.cinDocPath);
            c.eq("Legal Details", "Tax ID number (GST number)", data.gst, find(legal, "tax id number"));
            document(c, legal, "Upload Tax ID Number (GST Number)", "upload tax id number", data.taxDocPath);
        });
        assertNoDefects(rep);
    }

    // TC-E2E-05 - Admin: the profile's tab "Point of Contact". Name, designation, e-mail and mobile number of the Business, Finance and HR contacts, in one table.
    @Test(priority = 5)
    public void tc05_adminProfilePointsOfContact() {
        requireActiveClient("TC-E2E-05", T_POC);
        ensureOnProfile();
        TestReporter rep = TestReporter.start("TC-E2E-05", G_VERIFY, T_POC,
                "Client profile > tab 'Point of Contact'", "Name, designation, e-mail and mobile number of the three contacts",
                "Each value equals what was entered in the Points of Contact step", driver, data);
        rep.phaseChecks("tc05_poc_tab", "Point of Contact tab: check every value", "All contact values match", c -> {
            profile.openTab("Point of Contact");
            Map<String, Map<String, String>> cards = profile.waitForCards();
            contact(c, cards, "Business Point of Contact", data.business);
            contact(c, cards, "Finance Point of Contact", data.finance);
            contact(c, cards, "HR Point of Contact", data.hr);
        });
        assertNoDefects(rep);
    }

    // TC-E2E-06 - Admin: the profile's tab "MSA". The signed MSA card is listed, "Preview signed document" opens a real PDF (checked by fetching it) and
    // "Download signed document" is enabled (it is only checked, never clicked).
    @Test(priority = 6)
    public void tc06_adminProfileMsaTab() {
        requireActiveClient("TC-E2E-06", T_MSA_TAB);
        ensureOnProfile();
        TestReporter rep = TestReporter.start("TC-E2E-06", G_VERIFY, T_MSA_TAB,
                "Client profile > tab 'MSA' > Preview signed document (the Download button is only checked, never clicked)",
                "A signed MSA document card with working Preview and Download buttons",
                "The card is listed, Preview shows a PDF with content, Download is enabled", driver, data);
        rep.phaseChecks("tc06_msa_tab", "MSA tab: the signed MSA", "Signed MSA is listed, opens as a PDF and can be downloaded", c -> {
            profile.openTab("MSA");
            String name = profile.msaDocumentName();
            c.check("MSA tab", "Signed MSA document is listed", !name.isEmpty(), "a document card (for example 'MSA-Doc-Pdf')", name.isEmpty() ? "(none listed)" : name);
            boolean hasPreview = profile.hasPreviewButton();
            c.check("MSA tab", "'Preview signed document' button", hasPreview, "the button is there", hasPreview ? "the button is there" : "missing");
            boolean download = profile.isDownloadEnabled();
            c.check("MSA tab", "'Download signed document' button", download, "the button is there and enabled", download ? "the button is there and enabled" : "missing or disabled");
            if (hasPreview) {
                String preview = profile.previewSignedMsa();
                rep.evidence("signed_msa_preview");
                c.check("MSA tab", "Preview opens the signed document", preview.startsWith("application/pdf:") && !preview.endsWith(":0"),
                        "a PDF document with content", preview);
            }
            profile.closePreview();
        });
        profile.backToClients();
        assertNoDefects(rep);
    }

    // ================================================================== TC-E2E-07 .. TC-E2E-08 the client side

    // TC-E2E-07 - The client signs in with its password (a step of its own). The portal home shows the welcome heading with the company name and Company Onboarding
    // 100%; the top-right profile button shows the contact name and e-mail; "My Profile" shows company name, e-mail and phone - all equal to what was registered.
    @Test(priority = 7)
    public void tc07_clientSignsInAndSeesProfile() {
        requireActiveClient("TC-E2E-07", T_CLIENT_PROFILE);
        TestReporter rep = TestReporter.start("TC-E2E-07", G_VERIFY, T_CLIENT_PROFILE,
                "Client portal of the onboarded client", "Sign in as the client; portal home, top-right profile button, profile menu > My Profile",
                "Welcome heading = company name; 100% completed; profile button shows the contact name and e-mail; My Profile shows company name, e-mail and phone",
                driver, data);
        rep.phase("tc07_client_login", "Sign in as the client", "Login accepted, the client portal home opens", () -> {
            login.freshLogin(ConfigManager.baseUrl());
            login.login(data.email, ConfigManager.clientPassword());
            portal.waitForProgressWidget();
            session = Who.CLIENT;
            return "Signed in as " + data.email + " (the browser is at " + driver.getCurrentUrl() + ").";
        });
        rep.phaseChecks("tc07_client_profile", "Client portal home and My Profile window", "Heading, profile button and My Profile window match the registration data", c -> {
            c.eq("Portal home", "Welcome heading", "Welcome, " + data.legalName, portal.welcomeText());
            int progress = portal.progressPercent();
            c.check("Portal home", "Company Onboarding", progress == 100, "100% completed", progress + "% completed");
            String button = portal.profileButtonText();
            c.contains("Profile button (top right)", "Contact name", data.pocName, button);
            c.contains("Profile button (top right)", "E-mail", data.email, button);
            portal.openMyProfile();
            rep.evidence("my_profile_dialog");
            String dialog = String.join(" | ", portal.myProfileLines());
            c.contains("My Profile window", "Company name", data.legalName, dialog);
            c.contains("My Profile window", "E-mail", data.email, dialog);
            c.contains("My Profile window", "Phone", data.mobile, dialog);
            portal.closeMyProfile();
        });
        assertNoDefects(rep);
    }

    // TC-E2E-08 - The client portal, every tab of its menu in turn (opened by clicking): Home, Employees, Projects, MSA. Each tab is one step with its own table
    // and screenshot: heading, description, columns / cards, the MSA of the client (client name, MSA id, status ACTIVE, amendment row) and that no API call of the
    // tab fails (no 5xx). A brand-new client has no employees and no projects: the empty message is the expected content.
    @Test(priority = 8)
    public void tc08_clientPortalTabs() {
        requireActiveClient("TC-E2E-08", T_PORTAL_TABS);
        ensureClientPortal();
        TestReporter rep = TestReporter.start("TC-E2E-08", G_VERIFY, T_PORTAL_TABS,
                "Client portal, left menu: Home, Employees, Projects, MSA (each opened by clicking its menu entry)",
                "Open every tab and check what it shows", "Each tab opens with its heading and content, the MSA tab shows this client's ACTIVE MSA, no request to the server fails", driver, data);
        rep.phaseChecks("tc08_home", "Tab Home", "Home shows the welcome heading and Company Onboarding 100%", c -> {
            int before = rep.observeApi().size();
            c.guard("Home", "Tab opens and its content can be read", () -> {
                portal.openTab("Home");
                c.eq("Home", "Page heading", "Welcome, " + data.legalName, portal.heading());
                int progress = portal.progressPercent();
                c.check("Home", "Company Onboarding", progress == 100, "100% completed", progress + "% completed");
                String content = String.join(" | ", portal.mainLines());
                for (String step : new String[] { "Organization Details", "Points of Contact", "Sign MSA" }) {
                    c.contains("Home", "Onboarding step is listed: " + step, step, content);
                }
            });
            apiRow(c, rep, "Home", before);
        });
        rep.phaseChecks("tc08_employees", "Tab Employees", "Employees opens with its table (a new client has no employees)", c -> {
            int before = rep.observeApi().size();
            c.guard("Employees", "Tab opens and its content can be read", () -> {
                portal.openTab("Employees");
                listTab(c, "Employees", "Employees onboarded through your organization", List.of("EMPLOYEE", "EMPLOYEE CODE", "DESIGNATION", "COUNTRY", "STATUS"),
                        "employee");
            });
            apiRow(c, rep, "Employees", before);
        });
        rep.phaseChecks("tc08_projects", "Tab Projects", "Projects opens with its table (a new client has no projects)", c -> {
            int before = rep.observeApi().size();
            c.guard("Projects", "Tab opens and its content can be read", () -> {
                portal.openTab("Projects");
                listTab(c, "Projects", "Projects tracked for your organization", List.of("PROJECT NAME", "PROJECT CODE", "COUNTRY", "START DATE", "STATUS"), "project");
            });
            apiRow(c, rep, "Projects", before);
        });
        rep.phaseChecks("tc08_msa", "Tab MSA", "MSA shows this client's ACTIVE MSA and its amendment history", c -> {
            int before = rep.observeApi().size();
            c.guard("MSA", "Tab opens and its content can be read", () -> {
                portal.openTab("MSA");
                List<String> lines = portal.mainLines();
                c.eq("MSA", "Page heading", "MSA", portal.heading());
                c.contains("MSA", "Page description", "Your Master Services Agreement and its amendment history", String.join(" ", lines));
                c.eq("MSA card", "Client name", data.legalName, portal.valueAfter("CLIENT NAME"));
                int at = lines.indexOf(data.legalName);
                c.eq("MSA card", "Status label", "ACTIVE", at >= 0 && at + 1 < lines.size() ? lines.get(at + 1) : null);
                if (data.msaId != null) {
                    c.eq("MSA card", "MSA ID", data.msaId, portal.valueAfter("MSA ID"));
                } else {
                    c.check("MSA card", "MSA ID", portal.valueAfter("MSA ID") != null, "an MSA id (it was not recorded by an earlier run)", portal.valueAfter("MSA ID"));
                }
                String eor = portal.valueAfter("EOR NAME");
                c.check("MSA card", "EOR name", eor != null && !eor.isBlank(), "the name of the EOR company", eor);
                String msaName = portal.valueAfter("MSA NAME");
                c.check("MSA card", "MSA name", msaName != null && !msaName.isBlank(), "the name of the MSA", msaName);
                c.eq("Amendments table", "Columns", "S.NO. | AMENDMENT ID | AMENDMENT NAME | START DATE | END DATE | LAST UPDATE | STATUS",
                        String.join(" | ", portal.tableHeaders()).toUpperCase());
                List<List<String>> rows = portal.tableRows();
                List<String> first = rows.isEmpty() ? List.of() : rows.get(0);
                c.check("Amendments table", "The MSA is listed", first.size() >= 7, "one row: the MSA of this client", first.isEmpty() ? "(no row)" : String.join(" | ", first));
                if (first.size() >= 7) {
                    if (data.msaId != null) {
                        c.eq("Amendments table", "Amendment id of row 1", data.msaId, first.get(1));
                    }
                    c.eq("Amendments table", "Status of row 1", "ACTIVE", first.get(6));
                }
            });
            apiRow(c, rep, "MSA", before);
        });
        assertNoDefects(rep);
    }

    // ================================================================== sessions and prerequisites

    /** The checks only make sense for a client that really ended Active. Recorded as SKIP (with the reason) otherwise. */
    private void requireActiveClient(String id, String title) {
        if (data == null && verifyOnlyFile() != null) {
            data = TestDataGenerator.load(verifyOnlyFile());
            data.password = ConfigManager.clientPassword();
            clientActive = true;
        }
        if (clientActive && data != null) {
            return;
        }
        String reason = "TC-E2E-01 did not end with an ACTIVE client, so there is no finished onboarding to check";
        ClientData d = data != null ? data : newData();
        TestReporter rep = TestReporter.start(id, G_VERIFY, title, "Not run: the earlier step did not finish", "-", "-", driver, d);
        SkipException skip = new SkipException(reason);
        rep.finish("SKIP", skip);
        throw skip;
    }

    /** The client portal, signed in as the onboarded client (already the case right after TC-E2E-07). */
    private void ensureClientPortal() {
        if (session == Who.CLIENT && portalOpen()) {
            return;
        }
        login.freshLogin(ConfigManager.baseUrl());
        login.login(data.email, ConfigManager.clientPassword());
        portal.waitForProgressWidget();
        session = Who.CLIENT;
    }

    private boolean portalOpen() {
        try {
            return driver.getCurrentUrl().contains("/portal") && portal.progressPercent() >= 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void ensureAdmin() {
        if (session == Who.ADMIN && !driver.getCurrentUrl().contains("/login")) {
            return;
        }
        new ClientOnboardingFlow(driver, null, data).adminLoginFresh();
        session = Who.ADMIN;
    }

    /** The admin is on the client's profile page (opened from the Clients list when needed). */
    private void ensureOnProfile() {
        ensureAdmin();
        if (!profile.isOpen()) {
            profile.open(clients, data.legalName);
        }
    }

    private void waitTilesFilled() {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(12)).until(d -> profile.summary().values().stream().noneMatch(String::isEmpty));
        } catch (TimeoutException ignored) {
            // compare what is there: an empty tile is reported as a mismatch
        }
    }

    // ================================================================== helpers for the tables

    /** The card of the open tab (case-insensitive title); a missing card is one wrong row and an empty card. */
    private static Map<String, String> card(ValueChecks c, Map<String, Map<String, String>> cards, String title) {
        for (Map.Entry<String, Map<String, String>> e : cards.entrySet()) {
            if (e.getKey().equalsIgnoreCase(title)) {
                return e.getValue();
            }
        }
        c.check(title, "Card is shown", false, "the card '" + title + "' is shown", "not shown (cards found: " + cards.keySet() + ")");
        return Map.of();
    }

    /** Value of the first row whose label starts with the fragment (labels are lower case; '#controls' rows are skipped). */
    private static String find(Map<String, String> card, String labelStart) {
        for (Map.Entry<String, String> e : card.entrySet()) {
            if (!e.getKey().endsWith("#controls") && e.getKey().startsWith(labelStart)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Name, designation, e-mail and mobile number of one contact card. */
    private static void contact(ValueChecks c, Map<String, Map<String, String>> cards, String title, ClientData.Contact expected) {
        Map<String, String> m = card(c, cards, title);
        c.eq(title, "Name", expected.name, find(m, "name"));
        c.eq(title, "Designation", expected.designation, find(m, "designation"));
        c.eq(title, "E-mail", expected.email, find(m, "email"));
        c.eq(title, "Mobile number", expected.phone, find(m, "mobile"));
    }

    /** The row must show the uploaded document: its file name or a link / button to open it. */
    private static void document(ValueChecks c, Map<String, String> legal, String value, String label, String path) {
        String shown = find(legal, label);
        int controls = 0;
        for (Map.Entry<String, String> e : legal.entrySet()) {
            if (e.getKey().startsWith(label) && e.getKey().endsWith("#controls")) {
                controls = Integer.parseInt(e.getValue());
            }
        }
        boolean notUploaded = shown == null || shown.isEmpty() || shown.toLowerCase().contains("not uploaded");
        boolean namesFile = shown != null && shown.toLowerCase().contains(fileName(path).toLowerCase());
        c.check("Legal Details", value, !(notUploaded || (!namesFile && controls == 0)),
                "the uploaded file '" + fileName(path) + "' (or a link to open it)", shown == null || shown.isEmpty() ? "(empty)" : shown);
    }

    /** Employees / Projects tab: heading, description, columns and either the list or the empty message. */
    private void listTab(ValueChecks c, String where, String description, List<String> columns, String noun) {
        c.eq(where, "Page heading", where, portal.heading());
        c.contains(where, "Page description", description, String.join(" ", portal.mainLines()));
        c.eq(where, "Table columns", String.join(" | ", columns), String.join(" | ", portal.tableHeaders()).toUpperCase());
        List<List<String>> rows = portal.tableRows();
        boolean emptyMessage = rows.size() == 1 && rows.get(0).size() <= 1;
        String shown = rows.isEmpty() ? "(no table body)" : emptyMessage ? "empty message: '" + rows.get(0).get(0) + "'" : rows.size() + " " + noun + "(s) listed";
        c.check(where, "List or empty message", !rows.isEmpty(), "the " + noun + "s are listed, or a message says there are none", shown);
    }

    /** One row for the tab: none of the API calls made since {@code before} failed (5xx or no answer). */
    private static void apiRow(ValueChecks c, TestReporter rep, String where, int before) {
        List<ApiCall> all = rep.observeApi();
        List<ApiCall> mine = all.subList(Math.min(before, all.size()), all.size());
        List<String> bad = mine.stream().filter(a -> a.isServerError() || a.error != null).map(ApiCall::toString).toList();
        c.check(where, "Requests this tab sent to the server", bad.isEmpty(), "none of them fails (no server error, no missing answer)",
                bad.isEmpty() ? mine.size() + " request(s), none failed" : String.join("; ", bad));
    }

    private static String fileName(String path) {
        return path == null ? "?" : Paths.get(path).getFileName().toString();
    }
}
