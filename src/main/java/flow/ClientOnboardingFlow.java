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
        Assert.assertTrue(adminHome.isLoaded(), "The admin dashboard did not open after the sign-in (the 'Clients' link of the side menu is missing).");
        return "Signed in as " + SecretMasker.maskEmail(ConfigManager.adminEmail()) + " (the browser is at " + driver.getCurrentUrl() + ").";
    }

    // ------------------------------------------------------------------ 1. admin login

    public String adminLogin() {
        login.open(ConfigManager.baseUrl() + "/login");
        Assert.assertTrue(login.isDisplayed(), "The login page did not load (the e-mail box or the Sign in button is missing).");
        login.login(ConfigManager.adminEmail(), ConfigManager.adminPassword());
        Assert.assertTrue(adminHome.isLoaded(), "The admin dashboard did not open after the sign-in (the 'Clients' link of the side menu is missing).");
        return "The login page loaded, the sign-in was accepted and the dashboard opened for "
                + SecretMasker.maskEmail(ConfigManager.adminEmail()) + " (the browser is at " + driver.getCurrentUrl() + ").";
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
        Assert.assertNotNull(toast, "The application did not confirm the registration (no 'Client registered' notice was shown).");
        utils.ClientNames.record(data.legalName, data.email); // the client exists now: its name is never picked again
        register.waitClosed();

        ClientsPage.Row row = clients.findClient(data.legalName);
        List<String> bad = new ArrayList<>();
        cmp(bad, "company", data.legalName, row.company);
        cmp(bad, "client type", data.clientType, row.type);
        cmp(bad, "country", data.country, row.country);
        cmp(bad, "contact person", data.pocName, row.poc);
        cmp(bad, "e-mail", data.email, row.email);
        cmp(bad, "mobile number", data.mobile, row.mobile);
        failIfAny("The client in the list does not match what was entered when registering", bad);
        Assert.assertEquals(row.status, "Pending", "A new client should have the status 'Pending' until the onboarding is complete.");
        return "The application showed the notice '" + toast + "'. The client is listed with the entered data: " + row.company + ", " + row.type + ", "
                + row.country + ", status " + row.status + ".";
    }

    // ------------------------------------------------------------------ 3. welcome email

    public String verifyWelcomeEmail() {
        String subject = ConfigManager.get("client.welcome.subject.contains", "Welcome to KOMP");
        yopmail.openInbox(data.email);
        yopmail.waitForMail(subject, ConfigManager.getInt("email.poll.timeout.seconds", 120),
                ConfigManager.getInt("email.poll.interval.seconds", 5));
        String body = yopmail.openMailAndReadBody(subject);
        Assert.assertTrue(body.contains(data.legalName), "The welcome e-mail does not address the client '" + data.legalName + "'.");
        Assert.assertTrue(body.toLowerCase().contains(data.email.toLowerCase()), "The welcome e-mail does not show the login e-mail address.");
        portalLink = yopmail.findPortalLink();
        temporaryPassword = yopmail.extractTemporaryPassword(body);
        data.temporaryPassword = temporaryPassword;
        yopmail.maskForEvidence(temporaryPassword); // only hides it while masking is on (-Dmask.secrets=true or production)
        return "The welcome e-mail arrived (its subject contains '" + subject + "'). It is addressed to the client, shows the login e-mail address and has the portal link "
                + portalLink + ". Temporary password: " + SecretMasker.reveal(temporaryPassword) + ".";
    }

    // ------------------------------------------------------------------ 4. client portal login

    public String clientPortalLogin() {
        yopmail.closeAndReturn();
        login.freshLogin(ConfigManager.baseUrl());
        driver.get(portalLink);
        Assert.assertTrue(login.isDisplayed(), "The portal link from the e-mail did not open the login page.");
        login.login(data.email, temporaryPassword);
        Assert.assertTrue(passwordReset.isDisplayed(),
                "After the first sign-in with the temporary password the 'Set a new password' window was not shown.");
        return "The portal link opened the login page, the temporary password was accepted and the 'Set a new password' window opened (the browser is at "
                + driver.getCurrentUrl() + ").";
    }

    // ------------------------------------------------------------------ 5. password reset

    public String resetPassword() {
        ToastWatcher.clear(driver);
        passwordReset.changePassword(temporaryPassword, ConfigManager.clientPassword());
        Assert.assertTrue(login.isDisplayed(), "After the password was changed the application did not return to the login page.");
        String toasts = String.join(" / ", ToastWatcher.all(driver));
        login.login(data.email, ConfigManager.clientPassword());
        Assert.assertTrue(portalHome.isStartVisible(), "Signing in with the NEW password did not open the portal home (the Start button is missing).");
        data.password = ConfigManager.clientPassword();
        return "The password was changed to " + SecretMasker.reveal(data.password) + " (the application showed: " + (toasts.isEmpty() ? "no notice" : toasts)
                + "). The client was signed out and could sign in again with the new password (the browser is at " + driver.getCurrentUrl() + ").";
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
        failIfAny("The Organization Details form does not show the entered values", bad);
        return "The organization name was pre-filled correctly and the form accepted the values (the state is filled in automatically and is not checked; the city is '" + selectedCity
                + "'). The registered e-mail was " + (prefEmail.isEmpty() ? "empty, so it was entered" : "pre-filled and kept")
                + " and the mobile number was " + (prefMobile.isEmpty() ? "empty, so it was entered" : "pre-filled and kept") + ".";
    }

    // ------------------------------------------------------------------ 7 / 8. documents

    public String uploadCinDocument() {
        String name = new File(data.cinDocPath).getName();
        org.uploadCin(data.cinDocPath, name);
        Assert.assertTrue(org.isFileShown(name), "The CIN document '" + name + "' is not shown after the upload.");
        return "The CIN document '" + name + "' was uploaded and is shown.";
    }

    public String uploadTaxIdDocument() {
        String name = new File(data.taxDocPath).getName();
        org.uploadTaxId(data.taxDocPath, name);
        Assert.assertTrue(org.isFileShown(name), "The Tax ID (GST) document '" + name + "' is not shown after the upload.");
        return "The Tax ID (GST) document '" + name + "' was uploaded and is shown.";
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
            cmp(bad, s[0] + " contact name", c.name, poc.read(s[0], "name"));
            cmp(bad, s[0] + " contact designation", c.designation, poc.read(s[0], "designation"));
            cmp(bad, s[0] + " contact e-mail", c.email, poc.read(s[0], "email"));
            cmp(bad, s[0] + " contact phone number", c.phone, poc.read(s[0], "contact_no"));
        }
        failIfAny("The Points of Contact form does not show the entered values", bad);
        return "Continue was accepted; the 'Continue Setup' prompt was " + (prompt ? "shown and used" : "not shown")
                + "; the Business, Finance and HR contacts are completed.";
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
                    "The mailing address is the same as the registered address: " + reg, "The mailing address shown is: " + mail);
        }
        String cinName = new File(data.cinDocPath).getName();
        String gstName = new File(data.taxDocPath).getName();
        rep.note("Uploaded documents on Review page: CIN " + (review.pageMentions(cinName) ? "listed" : "NOT listed")
                + ", GST " + (review.pageMentions(gstName) ? "listed" : "NOT listed"));
        review.scrollToBottom();
        return "The Review page shows what was entered: organization, registered and mailing address, company type, e-mail, mobile number, CIN, GST "
                + "and the Business, Finance and HR contacts.";
    }

    // ------------------------------------------------------------------ 11. MSA generation

    public String generateMsa() {
        review.confirmAndContinue();
        msa.waitUntilOpen();
        Assert.assertTrue(msa.isProceedEnabled(), "The 'Proceed to Sign/View MSA' button is disabled.");
        Assert.assertTrue(msa.waitForGeneratedMsa(), "The MSA (agreement document) was not generated: the document viewer did not receive a document within the waiting time.");
        String content = msa.viewerContentSummary();
        Assert.assertFalse(content.startsWith("error") || content.equals("no-src"),
                "The MSA document could not be opened in the viewer: " + content);
        return "The Review was confirmed, the Sign MSA step is open and the MSA document can be opened (" + content + ").";
    }

    // ------------------------------------------------------------------ 12. MSA signing

    public String signMsa() {
        msa.proceedToSign();
        String message = msa.waitForSuccess();
        Assert.assertTrue(message.toLowerCase().contains("submitted successfully"),
                "The signing confirmation is not the expected one: " + message);
        return "The signing was completed; the application confirms: '" + message + "'.";
    }

    // ------------------------------------------------------------------ 13. client-side state after signing

    public String clientStateAfterSigning() {
        msa.clickDone();
        Assert.assertTrue(login.isDisplayed(), "After clicking 'Done' the client portal did not return to the login page.");
        login.login(data.email, ConfigManager.clientPassword());
        portalHome.waitForProgressWidget();
        int pct = portalHome.progressPercent();
        Assert.assertTrue(pct >= 0 && pct < 100,
                "The client must NOT be fully onboarded before the approval, but the progress shows " + pct + "%.");
        rep.note("The original description expected an 'Onboarding Successful' message; the application instead signs the client out after 'Done'. "
                + "The client home shows " + pct + "% (the MSA is waiting for the approval).");
        return "After 'Done' the client was signed out. Signing in again shows the onboarding at " + pct + "%: not active yet (waiting for the approval).";
    }

    // ------------------------------------------------------------------ 14. EOR login + MSA lookup

    public String eorLoginAndFindMsa() {
        login.freshLogin(ConfigManager.eorBaseUrl());
        login.login(ConfigManager.eorEmail(), ConfigManager.eorPassword());
        Assert.assertTrue(adminHome.isLoaded(), "The dashboard of the approver (EOR user) did not open.");
        eorMsa.open();
        eorMsa.searchClient(data.legalName);
        msaId = eorMsa.msaId(data.legalName);
        data.msaId = msaId;
        String status = eorMsa.status(data.legalName);
        Assert.assertTrue(Pattern.matches("MSA-\\d{8}-[A-Z0-9]+", msaId), "The MSA number does not have the expected format: " + msaId);
        Assert.assertEquals(status, "PENDING", "The MSA should be PENDING before the refresh (the signature is not synchronised yet).");
        return "The approver signed in; the MSA list shows '" + data.legalName + "' with MSA number " + msaId + " and status " + status + ".";
    }

    // ------------------------------------------------------------------ 15. refresh -> In Review

    public String refreshToInReview() {
        String status = eorMsa.refreshUntil(data.legalName, "IN REVIEW", ConfigManager.getInt("status.poll.timeout.seconds", 60));
        Assert.assertEquals(status, "IN REVIEW", "The status did not change to 'IN REVIEW' after clicking Refresh.");
        eorMsa.openStatusDialog(data.legalName);
        String dlg = eorMsa.dialogText();
        Assert.assertTrue(dlg.contains("Update client status") && dlg.contains(data.legalName),
                "The 'Update client status' window is missing or belongs to another client: " + dlg);
        return "The refresh changed the status to IN REVIEW and the 'Update client status' window opened: '" + dlg + "'.";
    }

    // ------------------------------------------------------------------ 16. approve + active

    public String approveAndActivate() {
        ToastWatcher.clear(driver);
        eorMsa.approveAndActivate();
        String finalStatus = eorMsa.refreshUntil(data.legalName, "ACTIVE", ConfigManager.getInt("status.poll.timeout.seconds", 60));
        Assert.assertEquals(finalStatus, "ACTIVE", "The client was not activated: the MSA list shows '" + finalStatus + "' instead of 'ACTIVE'.");
        String start = eorMsa.startDate(data.legalName);
        Assert.assertFalse(start.isEmpty(), "The ACTIVE MSA has no start date.");

        clients.open();
        ClientsPage.Row row = clients.waitForStatus(data.legalName, "Active", ConfigManager.getInt("status.poll.timeout.seconds", 60));
        Assert.assertEquals(row.status, "Active",
                "The client was not activated: the Clients list shows '" + row.status + "' instead of 'Active'.");
        return "Approved: the MSA " + msaId + " is ACTIVE (start date " + start + ") and the Clients list shows "
                + row.company + " as " + row.status + ". The application showed: " + String.join(" / ", ToastWatcher.all(driver));
    }

    // ------------------------------------------------------------------ 17. client confirms

    public String clientSeesOnboardingComplete() {
        login.freshLogin(ConfigManager.baseUrl());
        login.login(data.email, ConfigManager.clientPassword());
        portalHome.waitForProgressWidget();
        int pct = portalHome.progressPercent();
        Assert.assertEquals(pct, 100, "After the activation the client portal should show onboarding 100% but shows " + pct + "%.");
        return "After the activation the client portal shows Company Onboarding " + pct + "% completed.";
    }

    // ------------------------------------------------------------------ helpers

    private static void cmp(List<String> bad, String field, String expected, String actual) {
        if (!Objects.equals(expected == null ? null : expected.trim(), actual == null ? null : actual.trim())
                && !(expected != null && actual != null && expected.equalsIgnoreCase(actual) && field.contains("mail"))) {
            bad.add(field + ": should be '" + expected + "' but is '" + actual + "'");
        }
    }

    private static void contains(List<String> bad, String field, String actual, String... parts) {
        for (String p : parts) {
            if (actual == null || !actual.contains(p)) {
                bad.add(field + ": should contain '" + p + "' but is '" + actual + "'");
            }
        }
    }

    private static void contact(List<String> bad, String name, ClientData.Contact c, java.util.Map<String, String> shown) {
        cmp(bad, name + " contact name", c.name, shown.get("Name"));
        cmp(bad, name + " contact designation", c.designation, shown.get("Designation"));
        cmp(bad, name + " contact e-mail", c.email, shown.get("Email"));
        cmp(bad, name + " contact phone number", c.phone, shown.get("Contact Number"));
    }

    private static void failIfAny(String title, List<String> bad) {
        if (!bad.isEmpty()) {
            Assert.fail(title + ":\n - " + String.join("\n - ", bad));
        }
    }
}
