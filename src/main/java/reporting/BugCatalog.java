package reporting;

import java.util.HashMap;
import java.util.Map;

/**
 * Groups failing test cases that share one root cause into a single Jira bug and gives each bug a suggested
 * severity / priority / component. Severity and priority are SUGGESTIONS: adjust them to your Jira scheme.
 * A failing case that is not listed here becomes its own bug (BUG-<caseId>).
 */
public final class BugCatalog {

    public static final class Entry {
        public final String key;
        public final String summary;
        public final String severity;
        public final String priority;
        public final String component;
        public final String note;

        Entry(String key, String summary, String severity, String priority, String component, String note) {
            this.key = key;
            this.summary = summary;
            this.severity = severity;
            this.priority = priority;
            this.component = component;
            this.note = note;
        }
    }

    private static final Map<String, Entry> BY_CASE = new HashMap<>();

    static {
        Entry longFields = new Entry("BUG-REG-LONG-FIELDS",
                "Register Client: over-long Email / Legal Entity Name are not validated on the form and a raw database error is shown",
                "Major", "Medium", "Client onboarding - Register Client",
                "The server error text exposes the database column type ('character varying(254)' / '(255)'). The API call POST /kauth/auth/signup/ answers HTTP 500 (not a 400 validation error) for these inputs: see the *_api-evidence.json files. Add max-length validation on the form and map the server error to a friendly message.");
        add(longFields, "R-14", "R-19");
        add(new Entry("BUG-REG-POC-WHITESPACE",
                "Register Client: a whitespace-only Point of Contact name passes form validation; rejection comes from the server with the technical field name 'poc_name' and no inline message",
                "Minor", "Low", "Client onboarding - Register Client",
                "Legal Entity Name whitespace is trimmed and flagged inline, so POC name should behave the same."), "R-20");
        add(new Entry("BUG-REG-DUP-NAME-CASE",
                "Register Client: duplicate legal entity name check is case- and whitespace-sensitive (UPPER CASE + trailing space registers a second client)",
                "Major", "Medium", "Client onboarding - Register Client",
                "An exact duplicate is refused ('Client with this organization name is already exist!'), but the same name in upper case with a trailing space is accepted and a duplicate client is created."), "R-31");
        add(new Entry("BUG-REG-DOUBLE-SUBMIT",
                "Register Client: double-click submits twice; the second request shows a raw database 'duplicate key value violates unique constraint' error that includes the e-mail address",
                "Major", "Medium", "Client onboarding - Register Client",
                "Only one client is created, but the button is not disabled while the request is running and the second response leaks the constraint name 'k_auth_users_email_key'."), "R-60");
        add(new Entry("BUG-PWD-NO-MAXLEN",
                "Set a new password: a 300-character password is accepted (no maximum length)",
                "Minor", "Low", "Client onboarding - First login",
                "Policy text says '8+ characters with an uppercase, lowercase, number, and symbol' but no upper bound exists; very long passwords can be silently truncated by hashing libraries (e.g. bcrypt 72 bytes)."), "P-23");
        add(new Entry("BUG-ORG-YEARS",
                "Organization Details: 'Years in industry' accepts negative numbers, text and unrealistic values (-5, abc, 1000) and the wizard moves on",
                "Major", "Medium", "Client onboarding - Organization Details",
                "Only the empty value is rejected ('Required')."), "O-02", "O-03", "O-04");
        add(new Entry("BUG-ORG-PINCODE-FORMAT",
                "Organization Details: Postal Code format is not validated (5 digits, 7 digits, letters and 000000 are accepted)",
                "Major", "Medium", "Client onboarding - Organization Details",
                "Not about the pincode -> state/city mapping (out of scope): the FORMAT of the field is not checked, and the previous State/City stay selected."), "O-10", "O-11", "O-12", "O-13");
        add(new Entry("BUG-ORG-CIN-FORMAT",
                "Organization Details: CIN format/length is not validated ('INVALID123' and a 40-character value are accepted)",
                "Major", "Medium", "Client onboarding - Organization Details",
                "Only the empty value is rejected ('CIN is required')."), "O-21", "O-22");
        add(new Entry("BUG-ORG-GSTIN-FORMAT",
                "Organization Details: GSTIN format/length is not validated ('INVALIDGST' and a 30-character value are accepted)",
                "Major", "Medium", "Client onboarding - Organization Details",
                "Only the empty value is rejected ('Tax ID Number (GST Number) is required')."), "O-24", "O-25");
        add(new Entry("BUG-DOC-FILETYPE",
                "Document upload: any file type is accepted (.txt and a .exe can be attached as CIN and GST documents)",
                "Major", "High", "Client onboarding - Documents",
                "No file-type check on the client side; the file is shown with a 'Remove' link as if it were valid. Risk: executable/unsafe files stored in the tenant. Confirm server-side validation too."),
                "D-01", "D-02", "D-07", "D-08");
        add(new Entry("BUG-DOC-CONTENT",
                "Document upload: an empty (0 byte) file and a text file renamed to .pdf are accepted as CIN document",
                "Major", "Medium", "Client onboarding - Documents",
                "No content/size sanity check (empty file, non-PDF content with a .pdf extension)."), "D-03", "D-04");
        add(new Entry("BUG-DOC-SIZE",
                "Document upload: a 21 MB file is accepted with no maximum-size check or message",
                "Major", "Medium", "Client onboarding - Documents",
                "No maximum upload size is enforced or communicated (limit unknown: confirm the intended maximum)."), "D-05");
        add(new Entry("BUG-DOC-LONGNAME",
                "Document upload: a file with a 180-character name is accepted",
                "Minor", "Low", "Client onboarding - Documents",
                "Check truncation / layout / storage limits for long file names."), "D-06");
        add(new Entry("BUG-DOC-NOT-ENFORCED",
                "Organization Details: 'Continue' succeeds with no CIN and no GST document uploaded",
                "Major", "Medium", "Client onboarding - Documents",
                "NEEDS PRODUCT CONFIRMATION: assumes the CIN and GST documents are mandatory (the happy path uploads both and the form labels the numbers as required)."), "D-20");
    }

    static {
        add(new Entry("BUG-ACCESS-CLIENT-CANDIDATES-BLANK",
                "A signed-in client opening /candidates is redirected to /candidate and is left on an empty page showing only grey loading placeholders (no message, no redirect to the portal)",
                "Minor", "Low", "Client onboarding - Access control",
                "No admin content is exposed (good). The client should land on the portal or an access-denied/not-found message instead of a permanent skeleton page."), "B-15");
        add(new Entry("BUG-SESSION-LOGIN-PAGE-WHEN-SIGNED-IN",
                "A signed-in user (client or admin) opening /login is shown the login form again instead of being sent to their home page",
                "Minor", "Low", "Client onboarding - Session",
                "The session itself stays valid; only the redirect from /login for authenticated users is missing."), "B-19", "B-23");
        add(new Entry("BUG-ADMIN-DEEPLINK-DASHBOARD",
                "Admin: opening https://sandbox.kompglobal.com/clients by URL (full page load) lands on the dashboard (/) instead of the Clients page",
                "Minor", "Low", "Client onboarding - Navigation",
                "The session is valid (admin content is shown). Pressing F5 while on /clients keeps the Clients page (B-25 passes), so only the first full load of a deep link is redirected. Affects bookmarks and links shared with colleagues."), "B-22");
    }

    static {
        add(new Entry("BUG-CLIENTS-SORT-PAGE-LOCAL",
                "Clients list: clicking a column header only re-orders the rows of the CURRENT page, not all clients",
                "Major", "Medium", "Client onboarding - Clients list",
                "With 33 clients and 10 per page, sorting by Company ascending shows the previous 10 rows sorted among themselves; clients that should come first (e.g. the other '<b>Bold...' client and 'AOR Test Client') are on later pages. Sorting works as asc / desc / default, and the other sortable headers behave the same way."),
                "X-55");
        add(new Entry("BUG-CLIENTS-TILES-PENDING",
                "Clients list: the Total / Active / Inactive / In-Progress tiles do not add up to Total, clients with status 'Pending' have no tile",
                "Minor", "Low", "Client onboarding - Clients list",
                "NEEDS PRODUCT CONFIRMATION. Each tile equals the number of clients with that status and filters the list correctly; but 'Pending' clients (every client right after registration) are counted only in Total (e.g. 18 of 33 clients in the tiles at test time)."),
                "X-48");
        add(new Entry("BUG-API-MSA-STATUS-404",
                "MSA generation: POST /msa-contract/api/status/ returns 404 while the Sign MSA step waits for the document",
                "Minor", "Low", "Client onboarding - MSA",
                "Seen in 3 of 3 happy-path executions at phase 11 (evidence-based; not re-run because it needs a new client). The journey still completes and the same endpoint returns 200 for an existing contract (B-41), so this is probably a 'contract not created yet' answer that should be a non-error response. Needs backend confirmation."),
                "X-90");
        add(new Entry("BUG-API-NOTIFICATIONS-500-INTERMITTENT",
                "Client portal: GET /notifications/push_api/user-notification/ returned HTTP 500 once after activation (intermittent)",
                "Minor", "Low", "Client onboarding - Client portal",
                "Seen once (happy-path phase 17); the same call returned 200 in three later visits (low confidence). Check server logs around the timestamp of execution 20261107_151151_eb23."),
                "X-91");
        add(new Entry("BUG-ORG-WEBSITE-GENERIC-ERROR",
                "Organization Details: an invalid website ('not a url', 'javascript:alert(1)') is rejected by the server (HTTP 400) with only a generic toast, no message names the Website field",
                "Minor", "Low", "Client onboarding - Organization Details",
                "Toasts: 'Error in updating Client onboarding details' / 'Could not save your organization details.' The empty website shows a proper inline message ('Website is required'), so an invalid value should too."),
                "O-06", "O-07");
    }

    static {
        add(new Entry("BUG-PROFILE-DOCS-NOT-SHOWN",
                "Admin client profile: the CIN and GST documents uploaded during onboarding show as 'Not uploaded'",
                "Major", "Medium", "Client onboarding - Client profile",
                "Seen on an ACTIVE client whose onboarding completed with both PDFs uploaded in the Organization Details step (the Review page and the MSA flow worked). "
                        + "On Clients > <client> > Company Details > Legal Details the rows 'Upload CIN Document' and 'Upload Tax ID Number (GST Number)' both read 'Not uploaded'. "
                        + "Either the documents are not linked to the client record or the profile does not display them. Every other value of the profile matches the data entered."),
                "TC-E2E-04");
    }

    static {
        add(new Entry("BUG-ORG-DOCS-LOST-AFTER-BACK",
                "Onboarding wizard: the CIN and GST documents uploaded in step 1 are no longer shown after Back from step 2",
                "Major", "Medium", "Client onboarding - Documents",
                "NEEDS CONFIRMATION, probably the same root cause as BUG-PROFILE-DOCS-NOT-SHOWN (documents not persisted / not linked to the client). The Organization Details form keeps every text value after Back, but the document slots are empty."),
                "B-37");
    }

    private BugCatalog() {
    }

    private static void add(Entry e, String... caseIds) {
        for (String id : caseIds) {
            BY_CASE.put(id, e);
        }
    }

    public static Entry forCase(ResultCollector.Case c) {
        Entry e = BY_CASE.get(c.id);
        if (e != null) {
            return e;
        }
        boolean e2e = c.group != null && c.group.startsWith("E2E");
        return new Entry("BUG-" + c.id, e2e ? c.title + " (a visible value differs from the data entered, or a step failed)"
                : c.group + ": " + c.title + " (input accepted or message missing)",
                "Major", "Medium", "Client onboarding - " + c.group, "");
    }
}
