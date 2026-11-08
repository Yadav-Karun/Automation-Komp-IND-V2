package reporting;

import utils.ConfigManager;

/**
 * Human-readable "how to reproduce / how to run manually" procedure for a test case, derived from its group and the
 * input it used. Used for the Excel "Test procedure" column and for the Jira bug descriptions, so a developer can
 * reproduce a defect without reading the automation code.
 */
public final class ReproTemplates {

    private ReproTemplates() {
    }

    private static String base() {
        try {
            return ConfigManager.baseUrl();
        } catch (RuntimeException e) {
            return "https://sandbox.kompglobal.com";
        }
    }

    public static String steps(ResultCollector.Case c) {
        String g = c.group == null ? "" : c.group;
        String input = c.input == null ? "" : c.input;
        String id = c.id == null ? "" : c.id;
        if (g.startsWith("A1")) {
            return register(id, input);
        }
        if (g.startsWith("A2")) {
            return login(input);
        }
        if (g.startsWith("A3 First-login")) {
            return clientPrefix(true) + "\n4. In the 'Set a new password' dialog apply the test input: " + input
                    + "\n5. Click 'Update password'.\n6. Observe the inline messages, toasts and whether the dialog closes / the password changes.";
        }
        if (g.startsWith("A4")) {
            return clientPrefix(false) + organization() + "\n8. Apply the test input: " + input
                    + "\n9. Click 'Continue' at the bottom of the form.\n10. Observe inline messages, toasts and whether the wizard moves to 'Points of Contact' (or shows 'Finish setting up your company -> Continue Setup').";
        }
        if (g.startsWith("A5")) {
            return clientPrefix(false) + organization()
                    + "\n8. Remove both uploaded documents (click 'Remove' next to CIN.pdf and GSTIN.pdf) so the upload slots are empty."
                    + "\n9. Apply the test input: " + input + "  (for a file test, choose that file with 'Click to upload document')."
                    + "\n10. Observe whether the file is attached (file name + 'Remove' link) or refused with a message; for 'Continue without documents' click 'Continue' with both slots empty.";
        }
        if (g.startsWith("A6")) {
            return clientPrefix(false) + organization()
                    + "\n8. Click 'Continue' (and 'Continue Setup' if shown) to open step 2 'Points of Contact'. Fill Business, Finance and HR POC (name, designation, e-mail, 10-digit contact number) with valid values."
                    + "\n9. Apply the test input: " + input + "\n10. Click 'Continue'.\n11. Observe inline messages and whether the wizard moves to 'Review of Information'.";
        }
        if (g.startsWith("B1")) {
            return "1. Start a fresh browser session (no cookies / storage).\n2. Type this URL in the address bar: " + input
                    + "\n3. Observe the page that is shown and the final URL (expected: the login page, no protected content).";
        }
        if (id.startsWith("TC-E2E") || id.startsWith("E2E-") || g.startsWith("E2E")) {
            return happyFlow(id, input, c.expected);
        }
        if (g.startsWith("B2") || g.startsWith("B3") || g.startsWith("B5") || g.startsWith("B4")) {
            String who = g.startsWith("B5") || id.matches("B-2[2-5]") ? "the sandbox admin (EOR console) account"
                    : "a client whose onboarding is In-Progress (the automated run uses the single edge-case client; it never confirms the Review page)";
            return "Account: " + who + " (sign in at " + base() + "/login; do not repeat sign-ins quickly: the app rate-limits with 'Too many sign-in attempts').\n"
                    + "Steps: " + input + "\nObserve: " + (c.expected == null ? "" : c.expected);
        }
        if (g.startsWith("C5 API health (observed")) {
            return id.equals("X-91")
                    ? "1. Open the browser developer tools > Network (filter Fetch/XHR).\n2. Sign in as a client whose onboarding is complete (Active) at " + base() + "/login.\n"
                    + "3. Watch GET .../notifications/push_api/user-notification/ when the portal home loads (several visits).\n4. It returned HTTP 500 once in a full happy-path run (phase 17) and 200 on later visits."
                    : "1. As admin register a client (yopmail); as the client complete Organization Details (documents) and Points of Contact.\n"
                    + "2. Open the browser developer tools > Network (filter Fetch/XHR) BEFORE the next click.\n3. On the Review page click 'Confirm & Continue' (starts the MSA generation).\n"
                    + "4. While the 'Sign MSA' step waits for the document, watch POST https://sandbox-backend.kompglobal.com/msa-contract/api/status/ -> HTTP 404.\n"
                    + "(Automated: the happy-path test records it in network-summary.json, phase 11_msa_generation.)";
        }
        if (g.startsWith("C")) {
            boolean client = id.equals("X-81");
            String where = g.startsWith("C4") ? "MSA & Contract > MSA (sidebar)" : g.startsWith("C5") ? "the pages named below" : "Clients (sidebar)";
            return "1. Sign in at " + base() + "/login as " + (client ? "an existing client (no new client is registered)" : "the sandbox admin (EOR console) account") + ".\n"
                    + "2. Open " + where + ".\n3. " + input + "\n4. Observe: " + (c.expected == null ? "" : c.expected);
        }
        if (g.startsWith("A3 Client setup") || id.startsWith("P-0") || id.startsWith("P-3") || id.startsWith("O-00") || id.startsWith("C-00")) {
            return "Setup step used by the portal cases (registers a yopmail client, reads the welcome mail, logs in, reaches the wizard). See the 'Steps' sheet for the executed actions.";
        }
        return "See the 'Steps' sheet for the executed actions.";
    }

    private static String happyFlow(String id, String input, String expected) {
        if (id.equals("TC-E2E-01")) {
            return "1. Sign in at " + base() + "/login as the sandbox admin (EOR console) account.\n"
                    + "2. Clients > Register Client: fill every field with valid data (unique @yopmail.com e-mail) and submit.\n"
                    + "3. Open the yopmail inbox of that address: mail 'Welcome to KOMP - Your client portal is ready' with the temporary password.\n"
                    + "4. Sign in as the client with the temporary password, set a new password (the app signs you out), sign in again.\n"
                    + "5. Start: fill Organization Details and upload the CIN and GST documents, Continue; fill the Business / Finance / HR contacts, Continue; "
                    + "check the Review page and click 'Confirm & Continue'.\n"
                    + "6. Wait for the Sign MSA step, 'Proceed to Sign/View MSA'; signing completes by itself, click Done (the client is signed out).\n"
                    + "7. Sign in as admin/EOR: MSA & Contract > MSA, find the client (PENDING), 'Refresh sign status' until IN REVIEW, click the status badge, 'Approve & Activate'.\n"
                    + "8. Clients shows the client as Active; the client's portal shows Company Onboarding 100%.";
        }
        if (id.equals("TC-E2E-07")) {
            return "1. Sign in as the onboarded (Active) client at " + base() + "/login.\n"
                    + "2. Compare the portal home heading 'Welcome, <company>', the sidebar company name and the top-right profile button (contact name + e-mail) with the registration data.\n"
                    + "3. Click the profile button > 'My Profile' and compare company name, e-mail and phone with the registration data.";
        }
        return "1. Sign in at " + base() + "/login as the sandbox admin (EOR console) account.\n"
                + "2. Clients: search the onboarded client by company name and click its row (opens /clients/<id>, 'Back to Clients' returns to the list).\n"
                + "3. " + input + "\n4. Compare every visible value with what was entered during registration and onboarding. Expected: " + (expected == null ? "" : expected);
    }

    private static String register(String id, String input) {
        StringBuilder sb = new StringBuilder();
        sb.append("1. Open ").append(base()).append("/login and sign in with the sandbox admin (EOR console) account.\n")
                .append("2. In the sidebar click Clients, then 'Register Client' (opens /clients/register).\n")
                .append("3. Fill every field with VALID data except the item under test: Legal Entity Name 'KOMP IND Regression Client <timestamp>', Client Type EOR, Country India, ")
                .append("Point of Contact Name 'Regression POC <timestamp>', a unique @yopmail.com e-mail, a 10-digit mobile starting with 9.\n")
                .append("4. Apply the test input: ").append(input).append("\n");
        if (id.equals("R-60")) {
            sb.append("5. Click 'Register Client' twice in the same instant (double-click, or two script clicks on the button).\n")
                    .append("6. Observe the toasts and open Clients, search the company name: how many rows exist?");
        } else if (id.equals("R-40")) {
            sb.append("5. Observe the 'Contact Number' field value (do not submit).");
        } else {
            sb.append("5. Click 'Register Client'.\n6. Observe the inline messages, the toasts and whether the page stays on /clients/register or a client appears in the Clients list.");
        }
        return sb.toString();
    }

    private static String login(String input) {
        return "1. Open " + base() + "/login in a fresh browser session (no cookies / storage).\n"
                + "2. Enter: " + input + " (a blank field means leave it empty).\n"
                + "3. Click 'Sign in'.\n4. Observe the messages shown and whether the URL leaves /login.";
    }

    private static String clientPrefix(boolean stopAtDialog) {
        String s = "1. As admin register a client (Clients > Register Client) using an @yopmail.com address that you can read; note the e-mail.\n"
                + "2. Open https://yopmail.com, open that inbox and the mail 'Welcome to KOMP - Your client portal is ready'; copy the 'Temporary password'.\n"
                + "3. Open " + base() + "/login in a fresh session and sign in as the client with the temporary password -> the 'Set a new password' dialog appears.";
        if (stopAtDialog) {
            return s;
        }
        return s + "\n4. Set a valid new password (8+ characters with upper, lower, number and symbol) -> you are signed out; sign in again with it and click 'Start' on the portal home (Organization Details step)."
                + "\n   (Alternatively use an existing In-Progress yopmail client that has not reached MSA signing and walk back to step 1 with the wizard's 'Back' button.)";
    }

    private static String organization() {
        return "\n5. Fill Organization Details with VALID data: Years in industry 7, Website https://www.example.com, a product description, Postal Code 201009 "
                + "(State/City auto-fill), an address, 'Mailing address same as registered' ticked, Company Type Private Ltd, valid CIN (e.g. U55861MH2011PTC280074) and GSTIN (e.g. 27BAUCV8804X1ZP)."
                + "\n6. Upload CIN.pdf and GSTIN.pdf (valid PDFs).\n7. Make sure only the single item under test differs from this valid baseline.";
    }
}
