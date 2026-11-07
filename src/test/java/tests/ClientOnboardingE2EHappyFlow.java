package tests;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import base.BaseTest;
import flow.ClientOnboardingFlow;
import model.ClientData;
import pages.ClientPortalHomePage;
import pages.ClientProfilePage;
import pages.ClientsPage;
import pages.LoginPage;
import reporting.ExecutionInfo;
import reporting.TestReporter;
import utils.ConfigManager;
import utils.TestDataGenerator;

/**
 * CLIENT ONBOARDING - E2E HAPPY FLOW.  ONE client is created (all e-mail addresses are yopmail) and taken to the end:
 *
 *   TC-E2E-01  register -> welcome mail -> first login + new password -> Organization Details + documents -> Points of Contact
 *              -> Review -> MSA generation + signing -> EOR approval -> the client is ACTIVE (17 phases, one case)
 *   then, on that same client, "go to the client, open its profile and match everything that is visible":
 *   E2E-02     client portal: heading, top-right profile button and the My Profile dialog
 *   E2E-03     admin client profile: heading, status, e-mail and the four tiles (name, phone, company no, MSA id)
 *   E2E-04     admin client profile > Company Details: About Organization, Mailing Address, Legal Details
 *   E2E-05     admin client profile: the uploaded CIN / GST documents are shown
 *   E2E-06     admin client profile > Point of Contact: Business, Finance, HR
 *   E2E-07     admin client profile > MSA: the signed document opens and can be downloaded
 *
 * A mismatch is a soft defect (written to the report, the next checks still run). The data of the run is saved as
 * reports/&lt;this class&gt;_&lt;ts&gt;/test-data.json (no passwords).
 *
 * Run (visible browser):   mvn clean test -Dsuite=testng-client-happy-flow.xml
 * Re-check the profile of the client of an earlier run WITHOUT creating a client:
 *                          mvn clean test -Dsuite=testng-client-happy-flow.xml -Dhappy.client.data=reports/&lt;folder&gt;/test-data.json
 */
public class ClientOnboardingE2EHappyFlow extends BaseTest {

    private static final String G_JOURNEY = "E2E happy flow";
    private static final String G_PROFILE = "E2E profile check";

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

    /** -Dhappy.client.data=<test-data.json of an earlier run>: check that client's profile, create nothing. */
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
    @Test(priority = 1)
    public void e2e01_registerThroughActive() {
        if (verifyOnlyFile() != null) {
            data = TestDataGenerator.load(verifyOnlyFile());
            data.password = ConfigManager.clientPassword(); // a happy-flow client always ends with the common client password
            clientActive = true;
            throw new SkipException("Verification-only run (-Dhappy.client.data): the existing client '" + data.legalName
                    + "' is checked, nothing is created");
        }
        data = TestDataGenerator.generate();
        TestReporter rep = TestReporter.start("TC-E2E-01", G_JOURNEY,
                "Client onboarding E2E happy flow: register -> portal setup -> MSA -> EOR approval -> Active",
                "Positive end-to-end client onboarding on ONE client",
                "Valid, unique generated client data (all yopmail addresses)",
                "The journey completes and the client ends Active (17 phases)", driver, data);
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
                    "Welcome email received with portal link and temporary password", flow::verifyWelcomeEmail);
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
                    "Refresh changes status to IN REVIEW and the Update Client Status dialog opens", flow::refreshToInReview);
            // Phase 16 - "Approve & Activate": the MSA becomes ACTIVE with a start date and the Clients list shows the client as Active.
            rep.phase("client_active", "Approve and activate",
                    "Approval succeeds; client status is Active in MSA and Clients lists", () -> {
                        String result = flow.approveAndActivate();
                        clientActive = true; // the client IS active now: the profile checks below are meaningful
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
                "Journey completed but " + rep.defects().size() + " defect(s) were found:\n - " + String.join("\n - ", rep.defects()));
    }

    // ================================================================== E2E-02 client portal

    // E2E-02 - Client portal check (client signed in). The welcome heading shows the company name, the top-right profile button shows the contact name and
    // e-mail, and the "My Profile" dialog shows company name, e-mail and phone - all equal to what was registered.
    @Test(priority = 2)
    public void e2e02_clientMyProfile() {
        requireActiveClient("E2E-02", "Client portal: heading, profile button and My Profile show the registered details");
        ensureClientPortal();
        TestReporter rep = TestReporter.start("E2E-02", G_PROFILE, "Client portal: heading, profile button and My Profile show the registered details",
                "Client portal of the onboarded client", "Signed in as the client; portal home, top-right profile button, profile menu > My Profile",
                "Welcome heading = company name; profile button shows the contact name and e-mail; My Profile shows company name, e-mail and phone",
                driver, data);
        rep.phase("e2e_02", "Client portal home and My Profile dialog",
                "Heading, profile button and My Profile dialog match the registration data", () -> {
                    List<String> bad = new ArrayList<>();
                    cmp(bad, "Portal heading", "Welcome, " + data.legalName, portal.welcomeText());
                    String button = portal.profileButtonText();
                    has(bad, "Profile button (top right): contact name", button, data.pocName);
                    has(bad, "Profile button (top right): e-mail", button, data.email);
                    portal.openMyProfile();
                    rep.evidence("my_profile_dialog");
                    List<String> lines = portal.myProfileLines();
                    has(bad, "My Profile: company name", String.join(" | ", lines), data.legalName);
                    has(bad, "My Profile: e-mail", String.join(" | ", lines), data.email);
                    has(bad, "My Profile: phone", String.join(" | ", lines), data.mobile);
                    portal.closeMyProfile();
                    report(rep, "Client portal profile", bad);
                    return bad.isEmpty() ? "Heading, profile button and My Profile dialog match (dialog lines: " + lines + ")"
                            : bad.size() + " value(s) differ (see defect); dialog lines: " + lines;
                });
        assertNoDefects(rep);
    }

    // ================================================================== E2E-03 .. E2E-07 admin client profile

    // E2E-03 - Admin client profile (Clients > the client's row), top part. Heading = company name + (client type), status badge = Active, e-mail under the
    // heading, and the four tiles NAME (contact), PHONE NO., COMPANY NO (= CIN) and MSA ID (= the id in the EOR MSA list) equal the data entered.
    @Test(priority = 3)
    public void e2e03_adminProfileHeader() {
        requireActiveClient("E2E-03", "Admin client profile: heading, status, e-mail and summary tiles");
        ensureAdmin();
        TestReporter rep = TestReporter.start("E2E-03", G_PROFILE, "Admin client profile: heading, status, e-mail and summary tiles",
                "Clients > the client's row > profile (/clients/<id>)", "Heading, status badge, e-mail and the tiles NAME, PHONE NO., COMPANY NO, MSA ID",
                "Heading = company name + (type); status Active; e-mail, name, phone, company no (= CIN) and MSA id equal the data entered",
                driver, data);
        rep.phase("e2e_03", "Open the client profile and read the header",
                "Header and tiles match the registration / onboarding data", () -> {
                    profile.open(clients, data.legalName);
                    waitTilesFilled();
                    List<String> bad = new ArrayList<>();
                    cmp(bad, "Heading (company name + type)", data.legalName + "(" + data.clientType + ")", profile.titleText().replaceAll("\\s+\\(", "("));
                    cmp(bad, "Status badge", "Active", profile.statusText());
                    cmp(bad, "E-mail under the heading", data.email, profile.headerEmail());
                    Map<String, String> t = profile.summary();
                    cmp(bad, "Tile NAME (point of contact)", data.pocName, t.get("name"));
                    cmp(bad, "Tile PHONE NO.", data.mobile, t.get("phone no."));
                    cmp(bad, "Tile COMPANY NO (= CIN)", data.cin, t.get("company no"));
                    if (data.msaId != null) {
                        cmp(bad, "Tile MSA ID (= id in the EOR MSA list)", data.msaId, t.get("msa id"));
                    }
                    report(rep, "Admin profile header and tiles", bad);
                    return bad.isEmpty() ? "Heading, status, e-mail and the tiles match: " + t : bad.size() + " value(s) differ (see defect): " + t;
                });
        assertNoDefects(rep);
    }

    // E2E-04 - Admin client profile > tab "Company Details". Every text value of the cards About Organization (name, years, product, website, pincode, country,
    // state, city, street), Mailing Address (same as registered) and Legal Details (type, e-mail, mobile, CIN, GST number) equals what was entered.
    // State / city are only compared with what the form itself showed (the pincode lookup is not judged).
    @Test(priority = 4)
    public void e2e04_adminProfileCompanyDetails() {
        requireActiveClient("E2E-04", "Admin client profile > Company Details: organization, mailing address and legal details");
        ensureOnProfile();
        TestReporter rep = TestReporter.start("E2E-04", G_PROFILE, "Admin client profile > Company Details: organization, mailing address and legal details",
                "Client profile > tab 'Company Details' (cards About Organization, Mailing Address, Legal Details)",
                "Every text value in the three cards",
                "Each value equals what was entered in the Organization Details step; mailing address equals the registered address (the box was ticked)",
                driver, data);
        rep.phase("e2e_04", "Read the Company Details cards", "All values match the onboarding data", () -> {
            profile.openTab("Company Details");
            Map<String, Map<String, String>> cards = profile.waitForCards();
            List<String> bad = new ArrayList<>();
            Map<String, String> about = card(bad, cards, "About Organization");
            cmp(bad, "About Organization: company name", data.legalName, find(about, "company name"));
            cmp(bad, "About Organization: years in industry", data.yearsInIndustry, find(about, "years in industry"));
            cmp(bad, "About Organization: product or service", data.description, find(about, "product or service"));
            cmp(bad, "About Organization: website", data.website, find(about, "website"));
            cmp(bad, "About Organization: pincode", data.pincode, find(about, "pincode"));
            cmp(bad, "About Organization: country", data.country, find(about, "country"));
            if (data.selectedState != null) {
                cmp(bad, "About Organization: state (as shown in the form)", data.selectedState, find(about, "state"));
            }
            if (data.selectedCity != null) {
                cmp(bad, "About Organization: city (as shown in the form)", data.selectedCity, find(about, "city"));
            }
            cmp(bad, "About Organization: street address", data.address, find(about, "street address"));
            Map<String, String> mail = card(bad, cards, "Mailing Address");
            cmp(bad, "Mailing Address: pincode", data.pincode, find(mail, "pincode"));
            cmp(bad, "Mailing Address: country", data.country, find(mail, "country"));
            if (data.selectedState != null) {
                cmp(bad, "Mailing Address: state", data.selectedState, find(mail, "state"));
            }
            if (data.selectedCity != null) {
                cmp(bad, "Mailing Address: city", data.selectedCity, find(mail, "city"));
            }
            cmp(bad, "Mailing Address: street address", data.address, find(mail, "street address"));
            Map<String, String> legal = card(bad, cards, "Legal Details");
            cmp(bad, "Legal Details: company type", data.companyType, find(legal, "company type"));
            cmp(bad, "Legal Details: e-mail id", data.email, find(legal, "email"));
            cmp(bad, "Legal Details: mobile number", data.mobile, find(legal, "mobile"));
            cmp(bad, "Legal Details: CIN", data.cin, find(legal, "cin"));
            cmp(bad, "Legal Details: tax id number (GST)", data.gst, find(legal, "tax id number"));
            report(rep, "Admin profile - Company Details", bad);
            return bad.isEmpty() ? "All values of the three cards match the data entered" : bad.size() + " value(s) differ (see defect)";
        });
        assertNoDefects(rep);
    }

    // E2E-05 - Admin client profile > Legal Details. The CIN and GST documents uploaded during onboarding must be shown (file name or a link), not "Not uploaded".
    // KNOWN BUG: both rows read "Not uploaded".
    @Test(priority = 5)
    public void e2e05_adminProfileDocuments() {
        requireActiveClient("E2E-05", "Admin client profile: the CIN and GST documents uploaded during onboarding are shown");
        ensureOnProfile();
        TestReporter rep = TestReporter.start("E2E-05", G_PROFILE, "Admin client profile: the CIN and GST documents uploaded during onboarding are shown",
                "Client profile > Company Details > Legal Details rows 'Upload CIN Document' and 'Upload Tax ID Number (GST Number)'",
                "Both documents were uploaded in the Organization Details step (" + fileName(data == null ? null : data.cinDocPath) + ", "
                        + fileName(data == null ? null : data.taxDocPath) + ")",
                "Each row shows the uploaded document (file name or a link to open it), not 'Not uploaded'", driver, data);
        rep.phase("e2e_05", "Read the document rows", "Both documents are visible on the profile", () -> {
            profile.openTab("Company Details");
            Map<String, String> legal = card(new ArrayList<>(), profile.waitForCards(), "Legal Details");
            List<String> bad = new ArrayList<>();
            document(bad, legal, "upload cin document", "CIN", data.cinDocPath);
            document(bad, legal, "upload tax id number", "Tax ID (GST)", data.taxDocPath);
            report(rep, "Admin profile - uploaded documents", bad);
            return bad.isEmpty() ? "Both documents are shown" : bad.size() + " document(s) not shown (see defect): CIN row = '"
                    + find(legal, "upload cin document") + "', GST row = '" + find(legal, "upload tax id number") + "'";
        });
        assertNoDefects(rep);
    }

    // E2E-06 - Admin client profile > tab "Point of Contact". Name, designation, e-mail and mobile number of the Business, Finance and HR contacts equal what
    // was entered in the wizard.
    @Test(priority = 6)
    public void e2e06_adminProfilePointsOfContact() {
        requireActiveClient("E2E-06", "Admin client profile > Point of Contact: Business, Finance and HR contacts");
        ensureOnProfile();
        TestReporter rep = TestReporter.start("E2E-06", G_PROFILE, "Admin client profile > Point of Contact: Business, Finance and HR contacts",
                "Client profile > tab 'Point of Contact'", "Name, designation, e-mail and mobile number of the three contacts",
                "Each value equals what was entered in the Points of Contact step", driver, data);
        rep.phase("e2e_06", "Read the three contact cards", "All contact values match", () -> {
            profile.openTab("Point of Contact");
            Map<String, Map<String, String>> cards = profile.waitForCards();
            List<String> bad = new ArrayList<>();
            contact(bad, cards, "Business Point of Contact", data.business);
            contact(bad, cards, "Finance Point of Contact", data.finance);
            contact(bad, cards, "HR Point of Contact", data.hr);
            report(rep, "Admin profile - Points of Contact", bad);
            return bad.isEmpty() ? "Business, Finance and HR contacts match" : bad.size() + " value(s) differ (see defect)";
        });
        assertNoDefects(rep);
    }

    // E2E-07 - Admin client profile > tab "MSA". The signed MSA card is listed, "Preview signed document" opens a real PDF (checked by fetching it), and
    // "Download signed document" is enabled (it is only checked, never clicked).
    @Test(priority = 7)
    public void e2e07_adminProfileSignedMsa() {
        requireActiveClient("E2E-07", "Admin client profile > MSA: the signed MSA can be previewed and downloaded");
        ensureOnProfile();
        TestReporter rep = TestReporter.start("E2E-07", G_PROFILE, "Admin client profile > MSA: the signed MSA can be previewed and downloaded",
                "Client profile > tab 'MSA' > Preview signed document (the Download button is only checked, never clicked)",
                "A signed MSA document card with working Preview and Download buttons",
                "The card is listed, Preview shows a PDF with content, Download is enabled", driver, data);
        rep.phase("e2e_07", "Open the MSA tab and preview the signed document", "Signed MSA is listed and opens as a PDF", () -> {
            profile.openTab("MSA");
            String name = profile.msaDocumentName();
            List<String> bad = new ArrayList<>();
            if (name.isEmpty()) {
                bad.add("No signed MSA document is listed on the MSA tab");
            }
            if (!profile.hasPreviewButton()) {
                bad.add("The 'Preview signed document' button is missing");
            }
            if (!profile.isDownloadEnabled()) {
                bad.add("The 'Download signed document' button is missing or disabled");
            }
            String preview = "(not opened)";
            if (profile.hasPreviewButton()) {
                preview = profile.previewSignedMsa();
                rep.evidence("signed_msa_preview");
                if (!preview.startsWith("application/pdf:") || preview.endsWith(":0")) {
                    bad.add("The signed document preview is not a PDF with content: '" + preview + "'");
                }
            }
            report(rep, "Admin profile - signed MSA", bad);
            profile.closePreview();
            profile.backToClients();
            return bad.isEmpty() ? "Signed MSA '" + name + "' is listed; preview opened (" + preview + "); download enabled"
                    : bad.size() + " problem(s) (see defect); document '" + name + "', preview '" + preview + "'";
        });
        assertNoDefects(rep);
    }

    // ================================================================== sessions and prerequisites

    /** The profile checks only make sense for a client that really ended Active. Recorded as SKIP (with the reason) otherwise. */
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
        TestReporter rep = TestReporter.start(id, G_PROFILE, title, "Skipped: prerequisite not met", "-", "-", driver, d);
        SkipException skip = new SkipException(reason);
        rep.finish("SKIP", skip);
        throw skip;
    }

    /** The client portal, signed in as the onboarded client (already the case right after TC-E2E-01). */
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

    // ================================================================== comparison helpers

    private static void cmp(List<String> bad, String field, String expected, String actual) {
        String e = expected == null ? "" : expected.trim();
        String a = actual == null ? "" : actual.trim();
        boolean same = e.equals(a) || (field.toLowerCase().contains("mail") && e.equalsIgnoreCase(a));
        if (!same) {
            bad.add(field + ": expected '" + e + "' but the page shows '" + (actual == null ? "(not found)" : a) + "'");
        }
    }

    private static void has(List<String> bad, String field, String text, String expectedPart) {
        if (text == null || !text.toLowerCase().contains(expectedPart.toLowerCase())) {
            bad.add(field + ": expected to contain '" + expectedPart + "' but the page shows '" + text + "'");
        }
    }

    /** One soft defect per verification case, listing every differing value. */
    private static void report(TestReporter rep, String area, List<String> bad) {
        if (!bad.isEmpty()) {
            rep.defect(area + ": " + bad.size() + " visible value(s) differ from what was entered",
                    "Every visible value equals the data entered during registration and onboarding", String.join("; ", bad));
        }
    }

    private static Map<String, String> card(List<String> bad, Map<String, Map<String, String>> cards, String title) {
        for (Map.Entry<String, Map<String, String>> e : cards.entrySet()) {
            if (e.getKey().equalsIgnoreCase(title)) {
                return e.getValue();
            }
        }
        bad.add("The card '" + title + "' is not shown (cards found: " + cards.keySet() + ")");
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

    private static void contact(List<String> bad, Map<String, Map<String, String>> cards, String title, ClientData.Contact c) {
        Map<String, String> m = card(bad, cards, title);
        cmp(bad, title + ": name", c.name, find(m, "name"));
        cmp(bad, title + ": designation", c.designation, find(m, "designation"));
        cmp(bad, title + ": e-mail", c.email, find(m, "email"));
        cmp(bad, title + ": mobile number", c.phone, find(m, "mobile"));
    }

    /** The row must show the uploaded document: its file name or a link / button to open it. */
    private static void document(List<String> bad, Map<String, String> legal, String label, String what, String path) {
        String shown = find(legal, label);
        int controls = 0;
        for (Map.Entry<String, String> e : legal.entrySet()) {
            if (e.getKey().startsWith(label) && e.getKey().endsWith("#controls")) {
                controls = Integer.parseInt(e.getValue());
            }
        }
        boolean notUploaded = shown == null || shown.isEmpty() || shown.toLowerCase().contains("not uploaded");
        boolean namesFile = shown != null && shown.toLowerCase().contains(fileName(path).toLowerCase());
        if (notUploaded || (!namesFile && controls == 0)) {
            bad.add(what + " document: the " + what + " document was uploaded during onboarding (" + fileName(path)
                    + ") but the profile row shows '" + (shown == null || shown.isEmpty() ? "(empty)" : shown) + "'");
        }
    }

    private static String fileName(String path) {
        return path == null ? "?" : Paths.get(path).getFileName().toString();
    }
}
