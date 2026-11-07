package flow;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.openqa.selenium.WebDriver;
import org.testng.Assert;

import model.ClientData;
import pages.AdminHomePage;
import pages.ClientPortalHomePage;
import pages.ClientsPage;
import pages.EorMsaPage;
import pages.LoginPage;
import pages.MsaSigningPage;
import pages.OrganizationDetailsPage;
import pages.PasswordResetPage;
import pages.PointOfContactPage;
import pages.RegisterClientPage;
import pages.ReviewInformationPage;
import pages.ReviewInformationPage.Review;
import pages.YopmailPage;
import reporting.TestReporter;
import utils.ConfigManager;
import utils.SecretMasker;
import utils.ToastWatcher;

/**
 * The client-onboarding journey, one method per phase. Each returns a short "actual result" string and
 * throws on any failed expectation. Reusable by later negative / edge-case tests.
 */
public class ClientOnboardingFlow {

    private final WebDriver driver;
    private TestReporter rep;
    private final ClientData data;

    private final LoginPage login;
    private final AdminHomePage adminHome;
    private final ClientsPage clients;
    private final RegisterClientPage register;
    private final YopmailPage yopmail;
    private final PasswordResetPage passwordReset;
    private final ClientPortalHomePage portalHome;
    private final OrganizationDetailsPage org;
    private final PointOfContactPage poc;
    private final ReviewInformationPage review;
    private final MsaSigningPage msa;
    private final EorMsaPage eorMsa;

    private String temporaryPassword;
    private String portalLink;
    private String selectedCity;
    private String msaId;

    public ClientOnboardingFlow(WebDriver driver, TestReporter rep, ClientData data) {
        this.driver = driver;
        this.rep = rep;
        this.data = data;
        login = new LoginPage(driver);
        adminHome = new AdminHomePage(driver);
        clients = new ClientsPage(driver);
        register = new RegisterClientPage(driver);
        yopmail = new YopmailPage(driver);
        passwordReset = new PasswordResetPage(driver);
        portalHome = new ClientPortalHomePage(driver);
        org = new OrganizationDetailsPage(driver);
        poc = new PointOfContactPage(driver);
        review = new ReviewInformationPage(driver);
        msa = new MsaSigningPage(driver);
        eorMsa = new EorMsaPage(driver);
    }

    /** Temporary password read from the welcome e-mail (available after verifyWelcomeEmail). For negative tests. */
    public String temporaryPassword() {
        return temporaryPassword;
    }

    /** Hands the state of an earlier flow object to this one (set-up steps of the edge cases run in separate report cases). */
    public void restoreMailState(String temporaryPassword, String portalLink) {
        this.temporaryPassword = temporaryPassword;
        this.portalLink = portalLink;
    }

    public String portalLink() {
        return portalLink;
    }

    /** Reports defects and notes of the following phases into this case. */
    public void setReporter(TestReporter reporter) {
        this.rep = reporter;
    }

    /** Closes the Yopmail tab and returns to the application window. */
    public void closeMailTab() {
        yopmail.closeAndReturn();
    }

    /** Signs in as the sandbox admin / EOR user after somebody else was signed in (the old session is ended first). */
    public String adminLoginFresh() {
        login.freshLogin(ConfigManager.baseUrl());
        login.login(ConfigManager.adminEmail(), ConfigManager.adminPassword());
        Assert.assertTrue(adminHome.isLoaded(), "Dashboard not displayed after admin login (sidebar 'Clients' link missing).");
        return "Signed in as " + SecretMasker.maskEmail(ConfigManager.adminEmail()) + " at " + driver.getCurrentUrl();
    }

    // ------------------------------------------------------------------ 1. admin login

    public String adminLogin() {
        login.open(ConfigManager.baseUrl() + "/login");
        Assert.assertTrue(login.isDisplayed(), "Login page did not load (email field / Sign in button missing).");
        login.login(ConfigManager.adminEmail(), ConfigManager.adminPassword());
        Assert.assertTrue(adminHome.isLoaded(), "Dashboard not displayed after admin login (sidebar 'Clients' link missing).");
        return "Login page loaded, credentials accepted, dashboard displayed for "
                + SecretMasker.maskEmail(ConfigManager.adminEmail()) + " at " + driver.getCurrentUrl();
    }

    // ------------------------------------------------------------------ 2. register client

    public String registerClient() {
        clients.open();
        clients.clickRegisterClient();
        String toast = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            register.fill(data);
            ToastWatcher.clear(driver);
            register.submit();
            toast = ToastWatcher.waitFor(driver, "registered", 10);
            if (toast != null) {
                break;
            }
            // the anime was not in testdata/existing-clients.txt but the application says the name or an e-mail exists: list it and use the next anime
            boolean taken = ToastWatcher.all(driver).stream().anyMatch(utils.ClientNames::isDuplicateMessage);
            if (!taken) {
                break;
            }
            utils.ClientNames.markUsed(data.legalName, "the application says this name or e-mail already exists");
            utils.TestDataGenerator.applyPick(data, utils.ClientNames.pick());
        }
        Assert.assertNotNull(toast, "Registration success confirmation ('Client registered') was not displayed.");
        utils.ClientNames.record(data.legalName, data.email); // the client exists now: its name is never picked again
        register.waitClosed();

        ClientsPage.Row row = clients.findClient(data.legalName);
        List<String> bad = new ArrayList<>();
        cmp(bad, "company", data.legalName, row.company);
        cmp(bad, "client type", data.clientType, row.type);
        cmp(bad, "country", data.country, row.country);
        cmp(bad, "POC name", data.pocName, row.poc);
        cmp(bad, "email", data.email, row.email);
        cmp(bad, "mobile", data.mobile, row.mobile);
        failIfAny("Registered client does not match submitted data", bad);
        Assert.assertEquals(row.status, "Pending", "New client should be 'Pending' until onboarding completes.");
        return "Toast '" + toast + "'; list row matches submitted data: " + row.company + " | " + row.type + " | "
                + row.country + " | status " + row.status;
    }

    // ------------------------------------------------------------------ 3. welcome email

    public String verifyWelcomeEmail() {
        String subject = ConfigManager.get("client.welcome.subject.contains", "Welcome to KOMP");
        yopmail.openInbox(data.email);
        yopmail.waitForMail(subject, ConfigManager.getInt("email.poll.timeout.seconds", 120),
                ConfigManager.getInt("email.poll.interval.seconds", 5));
        String body = yopmail.openMailAndReadBody(subject);
        Assert.assertTrue(body.contains(data.legalName), "Welcome email does not address the client '" + data.legalName + "'.");
        Assert.assertTrue(body.toLowerCase().contains(data.email.toLowerCase()), "Welcome email does not show the login email.");
        portalLink = yopmail.findPortalLink();
        temporaryPassword = yopmail.extractTemporaryPassword(body);
        data.temporaryPassword = temporaryPassword;
        yopmail.maskForEvidence(temporaryPassword); // only hides it while masking is on (-Dmask.secrets=true or production)
        return "Email received (subject contains '" + subject + "'); addressed to client, login email shown, "
                + "portal link " + portalLink + " present, temporary password: " + SecretMasker.reveal(temporaryPassword) + ".";
    }

    // ------------------------------------------------------------------ 4. client portal login

    public String clientPortalLogin() {
        yopmail.closeAndReturn();
        login.freshLogin(ConfigManager.baseUrl());
        driver.get(portalLink);
        Assert.assertTrue(login.isDisplayed(), "Portal link from the email did not open the login page.");
        login.login(data.email, temporaryPassword);
        Assert.assertTrue(passwordReset.isDisplayed(),
                "After first login with the temporary password the 'Set a new password' dialog was not shown.");
        return "Portal link opened login page; temporary-password login accepted; 'Set a new password' dialog displayed at "
                + driver.getCurrentUrl();
    }

    // ------------------------------------------------------------------ 5. password reset

    public String resetPassword() {
        ToastWatcher.clear(driver);
        passwordReset.changePassword(temporaryPassword, ConfigManager.clientPassword());
        Assert.assertTrue(login.isDisplayed(), "After updating the password the session did not end on the login page.");
        String toasts = String.join(" / ", ToastWatcher.all(driver));
        login.login(data.email, ConfigManager.clientPassword());
        Assert.assertTrue(portalHome.isStartVisible(), "Login with the NEW password did not reach the portal home (Start button missing).");
        data.password = ConfigManager.clientPassword();
        return "Password updated to " + SecretMasker.reveal(data.password) + " (messages seen: " + (toasts.isEmpty() ? "none captured" : toasts)
                + "); signed out; re-login with the new password succeeded at " + driver.getCurrentUrl();
    }

    // ------------------------------------------------------------------ 6. organization details

    public String completeOrganizationDetails() {
        portalHome.clickStart();
        org.waitUntilOpen();
        Assert.assertEquals(org.prefilledOrganizationName(), data.legalName,
                "Organization name pre-filled in onboarding differs from the registered legal name.");
        String prefEmail = org.prefilledEmail();
        String prefMobile = org.prefilledMobile();
        selectedCity = org.fill(data);
        data.selectedCity = selectedCity;
        data.selectedState = org.stateShown(); // auto-filled by the app from the pincode: kept only to compare it with the profile later

        List<String> bad = new ArrayList<>();
        cmp(bad, "years in industry", data.yearsInIndustry, org.yearsValue());
        cmp(bad, "website", data.website, org.websiteValue());
        cmp(bad, "description", data.description, org.descriptionValue());
        cmp(bad, "pincode", data.pincode, org.pincodeValue());
        cmp(bad, "address", data.address, org.addressValue());
        cmp(bad, "CIN", data.cin, org.cinValue());
        cmp(bad, "GST", data.gst, org.gstValue());
        cmp(bad, "company type", data.companyType, org.companyTypeShown());
        cmp(bad, "registered email", data.email, org.emailValue());
        cmp(bad, "registered mobile", data.mobile, org.mobileValue());
        failIfAny("Organization form does not reflect the entered values", bad);
        return "Pre-filled org name matches; form accepted values (state auto-filled: not asserted; city '" + selectedCity
                + "'); registered email " + (prefEmail.isEmpty() ? "was blank, entered" : "pre-filled, kept")
                + ", mobile " + (prefMobile.isEmpty() ? "was blank, entered" : "pre-filled, kept") + ".";
    }

    // ------------------------------------------------------------------ 7 / 8. documents

    public String uploadCinDocument() {
        String name = new File(data.cinDocPath).getName();
        org.uploadCin(data.cinDocPath, name);
        Assert.assertTrue(org.isFileShown(name), "CIN document '" + name + "' not displayed after upload.");
        return "CIN document '" + name + "' uploaded and displayed.";
    }

    public String uploadTaxIdDocument() {
        String name = new File(data.taxDocPath).getName();
        org.uploadTaxId(data.taxDocPath, name);
        Assert.assertTrue(org.isFileShown(name), "Tax ID document '" + name + "' not displayed after upload.");
        return "Tax ID (GST) document '" + name + "' uploaded and displayed.";
    }

    // ------------------------------------------------------------------ 9. points of contact

    public String completePointsOfContact() {
        org.clickContinue();
        boolean prompt = poc.dismissContinueSetupPrompt();
        poc.waitUntilOpen();
        poc.fill(data);
        List<String> bad = new ArrayList<>();
        for (String[] s : new String[][] { { "business" }, { "finance" }, { "hr" } }) {
            ClientData.Contact c = s[0].equals("business") ? data.business : s[0].equals("finance") ? data.finance : data.hr;
            cmp(bad, s[0] + " name", c.name, poc.read(s[0], "name"));
            cmp(bad, s[0] + " designation", c.designation, poc.read(s[0], "designation"));
            cmp(bad, s[0] + " email", c.email, poc.read(s[0], "email"));
            cmp(bad, s[0] + " phone", c.phone, poc.read(s[0], "contact_no"));
        }
        failIfAny("Points-of-contact form does not reflect the entered values", bad);
        return "Continue accepted; 'Continue Setup' prompt " + (prompt ? "shown and used" : "not shown")
                + "; Business, Finance and HR contacts completed.";
    }

    // ------------------------------------------------------------------ 10. review

    public String reviewInformation() {
        poc.clickContinue();
        review.waitUntilOpen();
        Review r = review.read();
        List<String> bad = new ArrayList<>();
        cmp(bad, "organization name", data.legalName, r.organization.get("Organization Name"));
        cmp(bad, "company type", data.companyType, r.organization.get("Company Type"));
        cmp(bad, "years in industry", data.yearsInIndustry, r.organization.get("Years in Industry"));
        cmp(bad, "website", data.website, r.organization.get("Website"));
        cmp(bad, "email", data.email, r.organization.get("Email"));
        cmp(bad, "mobile", data.mobile, r.organization.get("Mobile Number"));
        cmp(bad, "CIN", data.cin, r.organization.get("CIN"));
        cmp(bad, "GST", data.gst, r.organization.get("Tax ID Number (GST Number)"));
        contains(bad, "registered address", r.organization.get("Registered Address"), data.address, data.pincode, selectedCity);
        contains(bad, "mailing address", r.organization.get("Mailing Address"), data.address, data.pincode);
        contact(bad, "business", data.business, r.business);
        contact(bad, "finance", data.finance, r.finance);
        contact(bad, "hr", data.hr, r.hr);
        failIfAny("Review page does not match the submitted data", bad);

        // "Mailing address is the same as registered address" was ticked, so both must be identical.
        String reg = r.organization.get("Registered Address");
        String mail = r.organization.get("Mailing Address");
        if (!Objects.equals(reg, mail)) {
            rep.defect("Mailing address differs from registered address although 'same as registered' was ticked",
                    "Mailing address identical to registered: " + reg, "Mailing address shown: " + mail);
        }
        String cinName = new File(data.cinDocPath).getName();
        String gstName = new File(data.taxDocPath).getName();
        rep.note("Uploaded documents on Review page: CIN " + (review.pageMentions(cinName) ? "listed" : "NOT listed")
                + ", GST " + (review.pageMentions(gstName) ? "listed" : "NOT listed"));
        review.scrollToBottom();
        return "Organization, registered/mailing address, company type, email, mobile, CIN, GST and the Business/Finance/HR "
                + "contacts on the review page match the submitted data.";
    }

    // ------------------------------------------------------------------ 11. MSA generation

    public String generateMsa() {
        review.confirmAndContinue();
        msa.waitUntilOpen();
        Assert.assertTrue(msa.isProceedEnabled(), "'Proceed to Sign/View MSA' is disabled.");
        Assert.assertTrue(msa.waitForGeneratedMsa(), "MSA was not generated: the document viewer did not receive a document within the configured timeout.");
        String content = msa.viewerContentSummary();
        Assert.assertFalse(content.startsWith("error") || content.equals("no-src"),
                "MSA document could not be retrieved from the viewer: " + content);
        return "Review confirmed; Sign MSA step open; MSA viewer present and retrievable (" + content + ").";
    }

    // ------------------------------------------------------------------ 12. MSA signing

    public String signMsa() {
        msa.proceedToSign();
        String message = msa.waitForSuccess();
        Assert.assertTrue(message.toLowerCase().contains("submitted successfully"),
                "Unexpected signing confirmation: " + message);
        return "Signing completed; confirmation displayed: '" + message + "'.";
    }

    // ------------------------------------------------------------------ 13. client-side state after signing

    public String clientStateAfterSigning() {
        msa.clickDone();
        Assert.assertTrue(login.isDisplayed(), "After 'Done' the client portal did not return to the login page.");
        login.login(data.email, ConfigManager.clientPassword());
        portalHome.waitForProgressWidget();
        int pct = portalHome.progressPercent();
        Assert.assertTrue(pct >= 0 && pct < 100,
                "Client must NOT be fully onboarded before EOR approval, but progress shows " + pct + "%.");
        rep.note("Brief expected an 'Onboarding Successful' message; the application instead signs the client out after 'Done'. "
                + "Client home shows " + pct + "% (Sign MSA awaiting EOR approval).");
        return "Done clicked; client signed out; re-login shows onboarding " + pct + "% - not yet active (awaiting EOR approval).";
    }

    // ------------------------------------------------------------------ 14. EOR login + MSA lookup

    public String eorLoginAndFindMsa() {
        login.freshLogin(ConfigManager.eorBaseUrl());
        login.login(ConfigManager.eorEmail(), ConfigManager.eorPassword());
        Assert.assertTrue(adminHome.isLoaded(), "EOR portal dashboard not displayed.");
        eorMsa.open();
        eorMsa.searchClient(data.legalName);
        msaId = eorMsa.msaId(data.legalName);
        data.msaId = msaId;
        String status = eorMsa.status(data.legalName);
        Assert.assertTrue(Pattern.matches("MSA-\\d{8}-[A-Z0-9]+", msaId), "Unexpected MSA ID format: " + msaId);
        Assert.assertEquals(status, "PENDING", "MSA should be PENDING before refresh (signature not yet synced).");
        return "EOR signed in; MSA list shows '" + data.legalName + "' with MSA " + msaId + ", status " + status + ".";
    }

    // ------------------------------------------------------------------ 15. refresh -> In Review

    public String refreshToInReview() {
        String status = eorMsa.refreshUntil(data.legalName, "IN REVIEW", ConfigManager.getInt("status.poll.timeout.seconds", 60));
        Assert.assertEquals(status, "IN REVIEW", "Client status did not become 'IN REVIEW' after Refresh.");
        eorMsa.openStatusDialog(data.legalName);
        String dlg = eorMsa.dialogText();
        Assert.assertTrue(dlg.contains("Update client status") && dlg.contains(data.legalName),
                "Update status dialog missing or for another client: " + dlg);
        return "Refresh moved status to IN REVIEW; status dialog opened: '" + dlg + "'.";
    }

    // ------------------------------------------------------------------ 16. approve + active

    public String approveAndActivate() {
        ToastWatcher.clear(driver);
        eorMsa.approveAndActivate();
        String finalStatus = eorMsa.refreshUntil(data.legalName, "ACTIVE", ConfigManager.getInt("status.poll.timeout.seconds", 60));
        Assert.assertEquals(finalStatus, "ACTIVE", "Client activation failed: expected status 'ACTIVE' but the MSA list shows '" + finalStatus + "'.");
        String start = eorMsa.startDate(data.legalName);
        Assert.assertFalse(start.isEmpty(), "Active MSA has no start date.");

        clients.open();
        ClientsPage.Row row = clients.waitForStatus(data.legalName, "Active", ConfigManager.getInt("status.poll.timeout.seconds", 60));
        Assert.assertEquals(row.status, "Active",
                "Client activation failed: Clients list shows '" + row.status + "' instead of 'Active'.");
        return "Approved; MSA " + msaId + " is ACTIVE (start " + start + "); Clients list shows "
                + row.company + " as " + row.status + ". Toasts: " + String.join(" / ", ToastWatcher.all(driver));
    }

    // ------------------------------------------------------------------ 17. client confirms

    public String clientSeesOnboardingComplete() {
        login.freshLogin(ConfigManager.baseUrl());
        login.login(data.email, ConfigManager.clientPassword());
        portalHome.waitForProgressWidget();
        int pct = portalHome.progressPercent();
        Assert.assertEquals(pct, 100, "Client portal should show onboarding 100% after activation but shows " + pct + "%.");
        return "Client portal shows Company Onboarding " + pct + "% completed after activation.";
    }

    // ------------------------------------------------------------------ helpers

    private static void cmp(List<String> bad, String field, String expected, String actual) {
        if (!Objects.equals(expected == null ? null : expected.trim(), actual == null ? null : actual.trim())
                && !(expected != null && actual != null && expected.equalsIgnoreCase(actual) && field.contains("email"))) {
            bad.add(field + ": expected '" + expected + "' but was '" + actual + "'");
        }
    }

    private static void contains(List<String> bad, String field, String actual, String... parts) {
        for (String p : parts) {
            if (actual == null || !actual.contains(p)) {
                bad.add(field + ": expected to contain '" + p + "' but was '" + actual + "'");
            }
        }
    }

    private static void contact(List<String> bad, String name, ClientData.Contact c, java.util.Map<String, String> shown) {
        cmp(bad, name + " POC name", c.name, shown.get("Name"));
        cmp(bad, name + " POC designation", c.designation, shown.get("Designation"));
        cmp(bad, name + " POC email", c.email, shown.get("Email"));
        cmp(bad, name + " POC phone", c.phone, shown.get("Contact Number"));
    }

    private static void failIfAny(String title, List<String> bad) {
        if (!bad.isEmpty()) {
            Assert.fail(title + ":\n - " + String.join("\n - ", bad));
        }
    }
}
