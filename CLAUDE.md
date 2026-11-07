# Client Onboarding - test guide

Selenium 4 + TestNG + Maven (Java 17) + Extent Reports automation for the KOMP Global **client onboarding** module (sandbox).
Independent of the old `C:\Testing\KOMP IND - Java` repo: never modify that repo.

## 1. What is in the project (2 test classes, 2 XML suites - nothing else)

| XML suite (repo root) | Test class (`src/test/java/tests/`) | What it does | Clients created |
|---|---|---|---|
| `testng-client-happy-flow.xml` | `ClientOnboardingE2EHappyFlow` | **7 test cases.** TC-E2E-01 = the whole happy journey (register -> ... -> Active, 17 phases in ONE case), then E2E-02..07 = open the client profile (client portal *and* admin) and match every visible value with what was entered | **1** |
| `testng-client-edge-cases.xml` | `ClientOnboardingEdgeCases` | **207 test cases** (76 test methods, many data-driven): every negative / edge case of the module, on ONE client, plus read-only admin / Clients-list checks | **1** |

The shared lifecycle (browser, report, helpers) is `src/main/java/base/BaseTest.java`, kept OUT of `tests/` so that folder holds only the two classes.
**No load testing; nothing that could break or flood the shared environment.**

## 2. Working rules (follow always)

- **2-CLIENT RULE: the module never creates more than 2 clients** (1 happy flow + 1 edge cases). All edge cases share one client: enter details, move next, go back,
  re-enter different values ... Do not register extra clients (also not while debugging): use `-Dedge.client.email=<yopmail>` (+ `-Dedge.client.fresh=true
  -Dedge.client.name="<name>"` for a never-logged-in leftover Pending client) or `-Dhappy.client.data=<test-data.json>`. Cases that would create an EXTRA client when the app
  accepts their input (R-31, R-50..R-52) are skipped in a full run. Leftover test clients cannot be deleted by automation.
- The user is a QA, not a developer: the focus is FINDING BUGS. The suites must be robust ("run it any time"): self-healing state, retries for the sign-in rate limit,
  automatic browser recovery, clear messages. Keep it that way.
  **A lost network connection is not a result** (`utils/Connectivity`, `BaseTest.ensureBrowserAlive`): a step cut off by `net::ERR_INTERNET_DISCONNECTED` / `ERR_NAME_NOT_RESOLVED` ... (or a browser left on
  Chrome's "This site can't be reached" page) is reported as SKIP "network lost - run the case again", never FAIL; before the next case the suite waits until the browser can open the login page again
  (polls every 10 s, `-Dnetwork.wait.max.seconds`, default 600; after one outage that never ended later waits are 30 s), goes on from a clean page and signs in again when a case needs a session.
  (Seen: a 13-second outage made 35 cases fail and 85 more skip for 13 minutes because the suite kept clicking on the error page.)
- Run with the browser VISIBLE and maximized (no `-DHeadless=true` unless asked). Long runs may use the harness' background-task mechanism, the browser is still visible.
- Use ONLY `@yopmail.com` addresses wherever an e-mail is needed.
- **Client names are ANIME names** for the two clients the tests create (110 anime in `utils/ClientNames`, each with 4 characters):
  client (legal entity) name = the anime ("Dragon Ball Z"); contact person at registration = its MAIN CHARACTER ("Son Goku") with e-mail = name in lower case + `@yopmail.com`
  (`songoku@yopmail.com`, also the client's login address); Business / Finance / HR contacts of the wizard = three other characters of the same anime ("Vegeta" `vegeta@yopmail.com`, "Son Gohan", "Piccolo").
  No e-mail is shared by two anime (checked offline). `testdata/existing-clients.txt` is the registry of anime already used
  (`name | e-mail | created by | date time`, comment lines start with `#`): before a client is registered the file is READ and an anime that is not listed is picked
  (`ClientNames.pick()`, applied with `TestDataGenerator.applyPick`), right after the registration the client is APPENDED (`ClientNames.record`). If the application still says the name or an e-mail exists
  ("Client with this organization name is already exist!") the anime is added to the file and the next one is used (up to 5 tries). When all 110 are used the names get a number
  ("Naruto 2", e-mails `narutouzumaki2@yopmail.com`). Delete a line to free a name. This registry is SEPARATE from the duplicate-name test cases (R-30..R-33), which use a client that exists in the Clients list.
  The throw-away data of the negative cases (`newData()` / `TestDataGenerator.generateScratch()`) keeps timestamped non-anime names. Override the file with `-Dclient.names.file=<path>`.
  Yopmail inboxes are public: addresses like `songoku@yopmail.com` may hold other people's mails; the welcome mail is found by its subject and checked for the client name and e-mail.
- Every finding needs evidence: the HTML report has steps, expected vs actual, screenshots, log and API calls per case.
- Reports: **one folder per test class, one dated folder per run** (own HTML, own screenshots). One XML `<test>` with several classes = several run folders.
- Ask before starting a new group of cases; keep new work read-only where possible. Never click `Confirm & Continue` on a test client (starts MSA generation), never click
  "Approve & Activate" on one. Do NOT assert pincode -> state/city correctness (API driven; the user does not care).
- **Secrets are NOT masked in the reports / logs / screenshots** (user decision 2026-11-07: sandbox test accounts, the report goes to management): the report shows the login e-mails in full, the client's
  password and temporary password in the header of every case ("Client: ... | login e-mail ... | password ... | temporary password ..."), the admin / EOR logins and the common client password in the
  report's system-info panel, and the temporary password stays visible in the Yopmail screenshot. `-Dmask.secrets=true` hides them again (`SecretMasker`); a run against production ALWAYS masks.
  Credentials still never go into committed files: `config/secrets.local.properties`, `reports/` and `*.log` are gitignored, and the passwords are not written to `test-data.json`.
  Production is refused without `-Dallow.production=true`.

## 3. How to run

```bash
mvn clean test                                          # = the happy flow (default suite)
mvn clean test -Dsuite=testng-client-happy-flow.xml     # happy flow + profile checks      (creates 1 client, ~8 min)
mvn clean test -Dsuite=testng-client-edge-cases.xml     # all negative / edge cases        (creates 1 client, ~20 min)
```
Edge cases - options (all optional):
```bash
-Dcases=O-10,C-03                    # only these case ids (the set-up steps - register, mail, first login ... - run on demand)
-Dedge.client.email=<yopmail>        # use an existing client instead of registering (password: -Dedge.client.password / env KOMP_EDGE_CLIENT_PASSWORD, default = common client
                                     # password; if the app accepted case P-23 the password is 'Aa1!' + 296 x 'x'; the run folder keeps the used client in test-data.json).
                                     # The client's legal name is looked up by this e-mail in testdata/existing-clients.txt (or give -Dedge.client.name="<legal name>")
-Dedge.client.fresh=true             # ... a client that NEVER logged in: the first-login password cases run too (name from the registry, or -Dedge.client.name)
-Deor.active.client="<name>"         # ACTIVE client for B-40 / B-41 / X-70 (default: the first ACTIVE client of the list)
```
Happy flow - re-check the profile of the client of an earlier run **without creating a client**:
```bash
mvn clean test -Dsuite=testng-client-happy-flow.xml -Dhappy.client.data=reports/ClientOnboardingE2EHappyFlow/<run folder>/test-data.json
```
**From VS Code:** the Test Runner for Java cannot run a TestNG XML (running the XML says "No tests found in the selected file or folder": it only runs Java classes / methods).
Use the **Run and Debug** dropdown (F5, `.vscode/launch.json`: "XML: Happy flow", "XML: Edge cases - everything", "XML: Edge cases - only the case ids I type"; it starts `org.testng.TestNG` with the XML and supports
breakpoints), or **right-click `pom.xml` > Run Maven Commands... > Favorites** (`.vscode/settings.json`) or **Terminal > Run Task...** (`.vscode/tasks.json`: the same plus edge
cases by ids you type), or run a class from the Testing sidebar / the "Run Test" link above it (that skips the XML; options such as `-Dcases` then have to be set in `java.test.config`).
If "Run Test" on a CLASS also says "No tests found": the Java language server missed files that were created outside VS Code (its log shows "Java Model Exception ... does not exist");
fix = Command Palette > **Java: Clean Java Language Server Workspace** > Restart and delete, wait until the Java status is ready, then use the Testing sidebar (Refresh Tests).
**If a run started from VS Code fails at the very start** (journey case missing from the report, the profile checks all SKIP) or VS Code asks "Build failed, do you want to continue?": the Java language server
rebuilt classes with the Eclipse compiler and wrote `java.lang.Error: Unresolved compilation problem: ... cannot be resolved` stubs into `target/classes` (its project view was broken). Maven does not notice
(the stub is newer than the source). Fix: `mvn clean test-compile` (clean is required), then Command Palette > **Java: Clean Java Language Server Workspace** > Restart and delete.
Runs through Maven (Terminal / Task / Favorites) always rebuild with javac and are not affected.
Other switches: `-Denv=preprod` (needs preprod logins), `-Dclient.type=AOR` (never executed), `-DHeadless=true` (only when asked).
`login.ratelimit.max.wait.seconds` (default 600) caps how long a run waits when the app answers "Too many sign-in attempts" (HTTP 429).
`network.wait.max.seconds` (default 600) caps how long a run waits for the application after the internet connection was lost (see section 2).
Configuration: `config/config.properties` (no secrets), `config/documents.properties` (CIN.pdf / GSTIN.pdf paths), `config/secrets.local.properties` (gitignored: logins,
common client password; template `config/secrets.example.properties`), `testdata/client-onboarding.json` (data template). Any key can be given as `-Dkey=value` or env `KOMP_<KEY>`.
Prerequisites: Java 17, Maven 3.9+, Google Chrome (driver resolved by Selenium Manager), internet access (sandbox + yopmail.com).

## 4. Reports

```
reports/
  ClientOnboardingE2EHappyFlow/
    2026-11-07_07.12.00/            <- one folder per run: date + 12-hour clock time (YYYY-MM-DD_hh.mm.ss)
      ClientOnboardingE2EHappyFlow.html   Extent report (steps, expected vs actual, screenshots, API calls); share the whole folder (links are relative)
      screenshots/                         NN_<phase>_PASS|FAIL|DEFECT|SKIP.png  (this run only)
      logs/execution.log
      api-evidence/                        network-<caseId>.json  (method + path + status only: no headers, cookies or bodies)
      test-data.json                       the client data of the run (no passwords) - feeds -Dhappy.client.data / -Dedge.client.email
  ClientOnboardingEdgeCases/
    2026-11-07_07.14.10/ ...
```
**Screenshots are FULL-PAGE** (`reporting/ScreenshotManager`): the KOMP pages scroll inside their own panel, so a normal screenshot cuts off everything below the fold (the E2E-05 evidence - the
"Not uploaded" document rows - was invisible). For a page whose CONTENT is taller than the window the browser view is enlarged for a moment (DevTools device-metrics override), the page gets time to settle
(the app recomputes the height of its tables and animates it for 300 ms: a capture taken at once shows half the rows), the real end of the content is measured (sidebar, sticky and fixed parts ignored) and the
page is captured down to that point (no blank space); the view is then restored and settled again, scroll positions kept, so the next step meets the page as it was (~1.3 s extra per such screenshot).
Pages that fit, pages that only overflow in the sidebar, and pages with something open on top (dialog, menu, list, PDF viewer / iframe, full-screen overlay - resizing would blank the PDF) get the normal screenshot.
`-Dscreenshot.fullpage=false` switches it off, `-Dscreenshot.max.height=6000` caps the height (px), `-Dscreenshot.settle.ms=350` is the minimum wait after each resize.
The Yopmail welcome-mail screenshot scrolls the temporary password into view and outlines it in red.
**The report HTML must never execute test data.** Extent writes test names unescaped (HTML sidebar + the report's own script block): a case title such as "Search text '<script>alert(1)</script>'" (X-07, X-72) ran the
script when the report was opened. `TestReporter.plain()` puts a zero-width space after every `<` in names / descriptions / node names; log and step texts go through `esc()`. Check a new report by opening it in a browser: no alert.
**A case title says what the case CHECKS, not what happened** ("... documents are shown"). The verdict is the status badge, and a failed case gets "FAILED because: <reason> | Checked: <what the case checks>"
as the first line under its title. A phase that found a defect is marked FAIL (with the DEFECT screenshot), never PASS; a case with a defect is never PASS.
The two chips under a case title are its tags: the module ("Client-Onboarding") and its group ("E2E-profile-check" = the profile cases E2E-02..07; the edge cases use "A1 Login ...", "B2 Role boundary ...", ...), used by
the report's Tags view to group failures by area; they are shown once per case (the report template layout is fixed in `ExtentManager.CSS`: long titles no longer overlap the chips, tags are not repeated on every phase row).
At the end of every class the console and the log print a plain summary (cases / passed / FAILED / skipped and the paths). A FAIL on a negative case is a finding:
the application accepted input it should reject, showed no (or a misleading, or a raw database) message, or a visible value differs from what was entered.
Two runs in the same second (or at the same clock time 12 hours apart) get a `_2` suffix, a folder is never overwritten.

## 5. Test cases

Every case has an id; the report shows id, title, input, expected result and the steps with screenshots. Every `@Test` method in the two classes has a comment saying what it tests.
Re-run one edge case: `mvn clean test -Dsuite=testng-client-edge-cases.xml -Dcases=<ID>` (the set-up steps run on demand). Cases marked *set-up* write their own row the first time they are needed.

`ClientOnboardingEdgeCases` runs in this order: login negatives -> unauthenticated URLs -> Register Client form (admin) -> **R-60 registers THE edge client** -> welcome mail ->
first-login password rules -> client on admin URLs / session -> Organization Details -> Documents -> Points of Contact -> wizard flow (refresh, Back, re-login, Review, back-and-edit)
-> client API health -> wrong password of the edge client (L-09) -> admin-side checks -> Clients list (search, filters, pagination, sort, columns) -> MSA search -> sign-out case (B-24, last).
The wizard is never confirmed, so the edge client stays In-Progress. Before every case the state is checked and healed (signed in? right wizard step? baseline values?),
so one failing case never breaks the next one.

### Happy flow - `ClientOnboardingE2EHappyFlow` (7 test cases)

| ID | What it tests | Expected |
|---|---|---|
| E2E-02 | Client portal: heading, profile button and My Profile show the registered details | Welcome heading = company name; profile button shows the contact name and e-mail; My Profile shows company name, e-mail and phone |
| E2E-03 | Admin client profile: heading, status, e-mail and summary tiles | Heading = company name + (type); status Active; e-mail, name, phone, company no (= CIN) and MSA id equal the data entered |
| E2E-04 | Admin client profile > Company Details: organization, mailing address and legal details | Each value equals what was entered in the Organization Details step; mailing address equals the registered address (the box was ticked) |
| E2E-05 | Admin client profile: the CIN and GST documents uploaded during onboarding are shown | Each row shows the uploaded document (file name or a link to open it), not 'Not uploaded' |
| E2E-06 | Admin client profile > Point of Contact: Business, Finance and HR contacts | Each value equals what was entered in the Points of Contact step |
| E2E-07 | Admin client profile > MSA: the signed MSA can be previewed and downloaded | The card is listed, Preview shows a PDF with content, Download is enabled |
| TC-E2E-01 | Client onboarding E2E happy flow: register -> portal setup -> MSA -> EOR approval -> Active | The journey completes and the client ends Active (17 phases) |

### Edge cases - `ClientOnboardingEdgeCases` (207 test cases)

#### Login (signed out)

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| L-01 | Both fields empty | Email and password empty, Sign in clicked | Sign-in is refused, the user stays on the login page, and a message explains the problem |
| L-02 | Password empty | Valid-format yopmail address, password empty | Sign-in is refused, the user stays on the login page, and a message explains the problem |
| L-03 | Email empty | Email empty, password entered | Sign-in is refused, the user stays on the login page, and a message explains the problem |
| L-04 | Email without @ | Email = plainaddress, password entered | Sign-in is refused, the user stays on the login page, and a message explains the problem |
| L-05 | Non-existent user | Unknown yopmail address with a password | Sign-in is refused, the user stays on the login page, and a message explains the problem |
| L-06 | SQL-injection style input | Email = admin' OR '1'='1, password = ' OR '1'='1 | Sign-in is refused, the user stays on the login page, and a message explains the problem |
| L-07 | Very long email (300+ chars) | Email = 300 x 'a' + @yopmail.com, password entered | Sign-in is refused, the user stays on the login page, and a message explains the problem |
| L-08 | Whitespace-only password | Unknown yopmail address, password = 3 spaces | Sign-in is refused, the user stays on the login page, and a message explains the problem |
| L-09 | Existing client, wrong password | The edge client's e-mail with a wrong password (single attempt) | Sign-in is refused, the user stays on the login page, and a message explains the problem |
| L-20 | Password field is masked | Text typed into the password field | Input type is 'password' (characters hidden) |

#### Direct URLs without a session

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| B-01 | Opening /clients without a session shows the login page | Fresh browser session (no cookies / storage), open https://sandbox.kompglobal.com/clients | The user is sent to the login page and no protected content is shown |
| B-02 | Opening /clients/register without a session shows the login page | Fresh browser session (no cookies / storage), open https://sandbox.kompglobal.com/clients/register | The user is sent to the login page and no protected content is shown |
| B-03 | Opening /contracts without a session shows the login page | Fresh browser session (no cookies / storage), open https://sandbox.kompglobal.com/contracts | The user is sent to the login page and no protected content is shown |
| B-04 | Opening /portal without a session shows the login page | Fresh browser session (no cookies / storage), open https://sandbox.kompglobal.com/portal | The user is sent to the login page and no protected content is shown |
| B-05 | Opening /portal/onboarding without a session shows the login page | Fresh browser session (no cookies / storage), open https://sandbox.kompglobal.com/portal/onboarding | The user is sent to the login page and no protected content is shown |
| B-06 | Opening /documents/management without a session shows the login page | Fresh browser session (no cookies / storage), open https://sandbox.kompglobal.com/documents/management | The user is sent to the login page and no protected content is shown |
| B-07 | Opening /candidates without a session shows the login page | Fresh browser session (no cookies / storage), open https://sandbox.kompglobal.com/candidates | The user is sent to the login page and no protected content is shown |
| B-08 | Opening /appraisal without a session shows the login page | Fresh browser session (no cookies / storage), open https://sandbox.kompglobal.com/appraisal | The user is sent to the login page and no protected content is shown |
| B-09 | Unknown URL shows a login or a friendly 'not found' page, not a blank page | Fresh session, open https://sandbox.kompglobal.com/no-such-page-xyz | Login page or a readable not-found message; never a blank page or raw error |

#### Register Client form (admin)

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| R-01 | All fields empty | Nothing entered, Register Client clicked | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-02 | Legal entity name empty | Valid data, Legal Entity Name left empty | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-03 | Client type not selected | Valid data, Client Type not selected | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-04 | Country not selected | Valid data, Country not selected | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-05 | Point of contact name empty | Valid data, POC name left empty | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-06 | Email empty | Valid data, Email left empty | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-07 | Contact number empty | Valid data, Contact Number left empty | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-08 | Legal entity name whitespace only | Legal Entity Name = 3 spaces | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-09 | Email without @ | Email = plainaddress | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-10 | Email without domain | Email = user@ | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-11 | Email without local part | Email = @yopmail.com | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-12 | Email with two @ | Email = user@@yopmail.com | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-13 | Email containing a space | Email = 'us er@yopmail.com' | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-14 | Email longer than 254 characters | Email = 250-char local part + @yopmail.com | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-15 | Contact number with letters | Contact Number = abcdefghij (field is expected to refuse letters) | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-16 | Contact number too short (5 digits) | Contact Number = 12345 | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-17 | Contact number too short (9 digits) | Contact Number = 987654321 | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-18 | Contact number with symbols | Contact Number = +91-98765 (symbols are expected to be refused) | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-19 | Legal entity name 300 characters | Legal Entity Name = 300 x 'A' (max length unknown; 300 is far beyond a sane limit) | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-20 | Point of contact name whitespace only | POC name = 3 spaces (client-side check was seen to let this pass) | Form is not submitted, stays on Register Client, no client is created, and a clear validation message is shown |
| R-30 | Duplicate legal entity name | Legal Entity Name = name of an existing client (same case) | Registration is refused with a duplicate message; no second client is created |
| R-31 | Duplicate legal entity name, different case + trailing space (not run in a full run: would create an extra client) | Legal Entity Name = an existing client's name in UPPER CASE + a trailing space | Registration is refused with a duplicate message; no second client is created |
| R-32 | Duplicate email | Email = e-mail of an existing client | Registration is refused with a duplicate message; no second client is created |
| R-33 | Duplicate email, different case | Email = existing client e-mail in UPPER CASE | Registration is refused with a duplicate message; no second client is created |
| R-40 | Contact number field limits length | Contact Number typed = 98765432101 (11 digits); nothing is submitted | Field keeps at most 10 characters |
| R-50 | HTML/script in legal entity name (not run in a full run: may create an extra client) | Legal Entity Name = <b>Bold</b> <script>alert(1)</script> Ltd | Either rejected with a message, or accepted and shown literally with no script executed |
| R-51 | Unicode and emoji in legal entity name (not run in a full run: may create an extra client) | Legal Entity Name = Unicode letters, Japanese characters and an emoji + Ltd | Either rejected with a message, or accepted and shown literally with no script executed |
| R-52 | SQL-like text in legal entity name (not run in a full run: may create an extra client) | Legal Entity Name = Robert'); DROP TABLE clients;-- Ltd | Either rejected with a message, or accepted and shown literally with no script executed |
| R-60 | Double-click on Register Client creates one client | Valid data (the edge client, an anime name); Register Client clicked twice in the same instant | Exactly one client is created |

#### First login and password rules

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| P-00 | Setup: register the edge client and read its welcome e-mail | Valid unique data, all yopmail addresses (the edge client's e-mail) | Client registered, welcome mail with the temporary password received |
| P-01 | Login with a wrong temporary password | The edge client's e-mail + a wrong password (single attempt) | Sign-in refused with a message; the user stays on the login page |
| P-02 | Setup: first login with the temporary password | Temporary password from the welcome e-mail (masked) | 'Set a new password' dialog shown |
| P-10 | All password fields empty | Nothing entered | Password change refused with a message; the dialog stays open and the password is unchanged |
| P-11 | Current password empty | Valid new password + confirmation, current empty | Password change refused with a message; the dialog stays open and the password is unchanged |
| P-12 | Wrong current password | Wrong current password with a valid new password (single attempt) | Password change refused with a message; the dialog stays open and the password is unchanged |
| P-13 | New password too short | New = Ab1! (4 chars) | Password change refused with a message; the dialog stays open and the password is unchanged |
| P-14 | Confirmation does not match | New and confirmation differ | Password change refused with a message; the dialog stays open and the password is unchanged |
| P-15 | Confirmation empty | New password entered, confirmation empty | Password change refused with a message; the dialog stays open and the password is unchanged |
| P-16 | New password whitespace only | New = 8 spaces | Password change refused with a message; the dialog stays open and the password is unchanged |
| P-17 | New password equals the temporary password | New = the temporary password | Password change refused with a message; the dialog stays open and the password is unchanged |
| P-18 | New password digits only | New = 12345678 (policy: upper, lower, number, symbol) | Password change refused with a message; the dialog stays open and the password is unchanged |
| P-19 | New password without a digit | New = Abcdefgh! (policy requires a number) | Password change refused with a message; the dialog stays open and the password is unchanged |
| P-20 | New password without a symbol | New = Abcdefg12 (policy requires a symbol) | Password change refused with a message; the dialog stays open and the password is unchanged |
| P-21 | New password without an uppercase letter | New = abcdefg1! (policy requires an uppercase letter) | Password change refused with a message; the dialog stays open and the password is unchanged |
| P-22 | New password without a lowercase letter | New = ABCDEFG1! (policy requires a lowercase letter) | Password change refused with a message; the dialog stays open and the password is unchanged |
| P-23 | New password 300 characters (no maximum length) | New = Aa1! + 296 x 'x' | Password change refused with a message; the dialog stays open and the password is unchanged |
| P-30 | Setup: set the permanent password and log in | Valid common client password (masked) | Login with the new password reaches the portal home |

#### The client on admin URLs, session and reload

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| B-10 | Client cannot open admin page /clients | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/clients | The client is redirected away or told access is denied; no admin content is shown |
| B-11 | Client cannot open admin page /clients/register | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/clients/register | The client is redirected away or told access is denied; no admin content is shown |
| B-12 | Client cannot open admin page /contracts | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/contracts | The client is redirected away or told access is denied; no admin content is shown |
| B-13 | Client cannot open admin page /documents/templates | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/documents/templates | The client is redirected away or told access is denied; no admin content is shown |
| B-14 | Client cannot open admin page /documents/management | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/documents/management | The client is redirected away or told access is denied; no admin content is shown |
| B-15 | Client cannot open admin page /candidates | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/candidates | The client is redirected away or told access is denied; no admin content is shown |
| B-16 | Client cannot open admin page /appraisal | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/appraisal | The client is redirected away or told access is denied; no admin content is shown |
| B-17 | Client cannot open admin page /financials/eor/attendance | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/financials/eor/attendance | The client is redirected away or told access is denied; no admin content is shown |
| B-18 | Client opening the root URL / lands in the client portal | Signed in as the edge client, open https://sandbox.kompglobal.com/ | Client portal home (/portal), not the admin dashboard |
| B-19 | Signed-in client opening /login is sent back to the portal | Signed in as the edge client, open https://sandbox.kompglobal.com/login | Redirect to the portal (a signed-in user is not shown the login form) |
| B-20 | Unknown URL as a signed-in client shows a readable page | Signed in as the edge client, open https://sandbox.kompglobal.com/no-such-page-xyz | Not-found message or redirect to the portal; never a blank page |
| B-21 | Client session survives a reload of the portal URL | Signed in as the edge client, open https://sandbox.kompglobal.com/portal again (full page load) | Still signed in on the portal; no login prompt |
| B-26 | Refresh (F5) on the client portal keeps the client signed in | Signed in as the edge client on /portal, press F5 | The portal home is shown again, no login prompt |

#### Organization Details (wizard step 1)

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| O-00 | Setup: open Organization Details and fill valid data + documents | Valid organization data and the CIN / GST documents | Form accepts the values and both documents show |
| O-01 | Years in industry empty | Valid form, only 'years_in_industry' set to '' | Continue is refused with a message; the wizard stays on Organization Details |
| O-02 | Years in industry negative (-5) | Valid form, only 'years_in_industry' set to '-5' | Continue is refused with a message; the wizard stays on Organization Details |
| O-03 | Years in industry text (abc) | Valid form, only 'years_in_industry' set to 'abc' | Continue is refused with a message; the wizard stays on Organization Details |
| O-04 | Years in industry 1000 | Valid form, only 'years_in_industry' set to '1000' | Continue is refused with a message; the wizard stays on Organization Details |
| O-05 | Website empty | Valid form, only 'website_link' set to '' | Continue is refused with a message; the wizard stays on Organization Details |
| O-06 | Website is not a URL (not a url) | Valid form, only 'website_link' set to 'not a url' | Continue is refused with a message; the wizard stays on Organization Details |
| O-07 | Website with script (javascript:alert(1)) | Valid form, only 'website_link' set to 'javascript:alert(1)' | Continue is refused with a message; the wizard stays on Organization Details |
| O-08 | Description empty | Valid form, only 'product_or_service_description' set to '' | Continue is refused with a message; the wizard stays on Organization Details |
| O-09 | Pincode empty | Valid form, only 'registered_address.pincode' set to '' | Continue is refused with a message; the wizard stays on Organization Details |
| O-10 | Pincode 5 digits | Valid form, only 'registered_address.pincode' set to '12345' | Continue is refused with a message; the wizard stays on Organization Details |
| O-11 | Pincode 7 digits | Valid form, only 'registered_address.pincode' set to '1234567' | Continue is refused with a message; the wizard stays on Organization Details |
| O-12 | Pincode letters | Valid form, only 'registered_address.pincode' set to 'abcdef' | Continue is refused with a message; the wizard stays on Organization Details |
| O-13 | Pincode 000000 | Valid form, only 'registered_address.pincode' set to '000000' | Continue is refused with a message; the wizard stays on Organization Details |
| O-14 | Address empty | Valid form, only 'registered_address.address' set to '' | Continue is refused with a message; the wizard stays on Organization Details |
| O-15 | Registered email empty (skipped: the field is pre-filled and read-only) | Valid form, only the registered e-mail set to '' (empty) | Continue is refused with a message; the wizard stays on Organization Details |
| O-16 | Registered email invalid (plainaddress) (skipped: the field is pre-filled and read-only) | Valid form, only the registered e-mail set to 'plainaddress' | Continue is refused with a message; the wizard stays on Organization Details |
| O-17 | Registered mobile empty (skipped: the field is pre-filled and read-only) | Valid form, only the registered mobile set to '' (empty) | Continue is refused with a message; the wizard stays on Organization Details |
| O-18 | Registered mobile too short (12345) (skipped: the field is pre-filled and read-only) | Valid form, only the registered mobile set to '12345' | Continue is refused with a message; the wizard stays on Organization Details |
| O-19 | Registered mobile letters (skipped: the field is pre-filled and read-only) | Valid form, only the registered mobile set to 'abcdefghij' | Continue is refused with a message; the wizard stays on Organization Details |
| O-20 | CIN empty | Valid form, only 'company_registration_number' set to '' | Continue is refused with a message; the wizard stays on Organization Details |
| O-21 | CIN invalid format (INVALID123) | Valid form, only 'company_registration_number' set to 'INVALID123' | Continue is refused with a message; the wizard stays on Organization Details |
| O-22 | CIN too long (40 chars) | Valid form, only 'company_registration_number' set to 'U12345MH2020PTC123456XXXXXXXXXXXXXXXXXXX' | Continue is refused with a message; the wizard stays on Organization Details |
| O-23 | GSTIN empty | Valid form, only 'gst_number' set to '' | Continue is refused with a message; the wizard stays on Organization Details |
| O-24 | GSTIN invalid format (INVALIDGST) | Valid form, only 'gst_number' set to 'INVALIDGST' | Continue is refused with a message; the wizard stays on Organization Details |
| O-25 | GSTIN too long (30 chars) | Valid form, only 'gst_number' set to '27ABCDE1234F1Z5999999999999999' | Continue is refused with a message; the wizard stays on Organization Details |

#### Documents (CIN / GST uploads)

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| D-01 | Text file (.txt) as CIN document | CIN slot, file 'not_a_document.txt' (56 bytes) | Upload is refused with a message and no document is attached |
| D-02 | Executable (.exe) as CIN document | CIN slot, file 'fake_program.exe' (46 bytes) | Upload is refused with a message and no document is attached |
| D-03 | Empty (0 byte) PDF as CIN document | CIN slot, file 'empty.pdf' (0 bytes) | Upload is refused with a message and no document is attached |
| D-04 | Text content renamed to .pdf as CIN document | CIN slot, file 'text_renamed.pdf' (38 bytes) | Upload is refused with a message and no document is attached |
| D-05 | Oversized (21 MB) PDF as CIN document | CIN slot, file 'oversize_21MB.pdf' (22020096 bytes) | Upload is refused with a message and no document is attached |
| D-06 | PDF with a 180-character file name as CIN document | CIN slot, file 'LLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLL... (180 chars)' (24905 bytes) | Upload is refused with a message and no document is attached |
| D-07 | Text file (.txt) as GST document | GST slot, file 'not_a_document.txt' (56 bytes) | Upload is refused with a message and no document is attached |
| D-08 | Executable (.exe) as GST document | GST slot, file 'fake_program.exe' (46 bytes) | Upload is refused with a message and no document is attached |
| D-10 | Valid PDF with spaces and symbols in its name is accepted | Copy of the CIN pdf named 'cin & test (1) #final.pdf' | Upload accepted and shown with a Remove control |
| D-11 | Remove a document, then upload it again | Valid CIN pdf uploaded, removed, uploaded again | File disappears after Remove and can be uploaded again |
| D-20 | Continue with no CIN and no GST document | Valid organization data, both documents removed (assumes documents are mandatory - confirm with product) | Continue is refused with a message asking for the documents |

#### Points of Contact (wizard step 2)

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| C-00 | Setup: reach Points of Contact with valid data | Valid organization data + documents, valid three contacts | Wizard reaches step 2 and accepts the valid contacts |
| C-01 | Business contact name empty | Valid form, only business.name set to '' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-02 | Business contact designation empty | Valid form, only business.designation set to '' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-03 | Business contact email empty | Valid form, only business.email set to '' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-04 | Business contact email invalid (abc@) | Valid form, only business.email set to 'abc@' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-05 | Business contact phone empty | Valid form, only business.contact_no set to '' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-06 | Business contact phone too short (12345) | Valid form, only business.contact_no set to '12345' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-07 | Business contact phone letters | Valid form, only business.contact_no set to 'abcdefghij' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-08 | Finance contact name empty | Valid form, only finance.name set to '' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-09 | Finance contact designation empty | Valid form, only finance.designation set to '' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-10 | Finance contact email empty | Valid form, only finance.email set to '' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-11 | Finance contact email invalid (abc@) | Valid form, only finance.email set to 'abc@' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-12 | Finance contact phone empty | Valid form, only finance.contact_no set to '' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-13 | Finance contact phone too short (12345) | Valid form, only finance.contact_no set to '12345' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-14 | Finance contact phone letters | Valid form, only finance.contact_no set to 'abcdefghij' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-15 | Hr contact name empty | Valid form, only hr.name set to '' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-16 | Hr contact designation empty | Valid form, only hr.designation set to '' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-17 | Hr contact email empty | Valid form, only hr.email set to '' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-18 | Hr contact email invalid (abc@) | Valid form, only hr.email set to 'abc@' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-19 | Hr contact phone empty | Valid form, only hr.contact_no set to '' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-20 | Hr contact phone too short (12345) | Valid form, only hr.contact_no set to '12345' | Continue is refused with a message; the wizard stays on Points of Contact |
| C-21 | Hr contact phone letters | Valid form, only hr.contact_no set to 'abcdefghij' | Continue is refused with a message; the wizard stays on Points of Contact |

#### Wizard flow: refresh, Back, re-login, Review page, back-and-edit

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| B-30 | Refresh (F5) inside the wizard keeps the client on the same step | Signed in as the edge client, open the onboarding wizard, press F5 | Still signed in, still on /portal/onboarding, on the same step - or on the furthest step already saved when the client had gone Back (recorded as an observation: the app resumes at the saved progress); never an earlier step |
| B-31 | Browser Back from the wizard keeps the client signed in and shows a page | Signed in as the edge client, in the wizard press the browser Back button | A readable portal page (not the login page, not blank) |
| B-32 | Saved organization values survive ending the session and signing in again | Edge client: Continue (saves step 1), Back, read the Organization Details form, end the session (cookies/storage cleared), sign in again, read the form again | Every saved text value is identical after signing in again (documents are covered by B-37) |
| B-33 | Review page shows exactly what was entered in Organization Details and Points of Contact | Edge client: valid Organization Details (documents attached) and valid Business/Finance/HR contacts, Continue twice, read the Review page (Confirm is NOT clicked) | Every value on the Review page equals what was entered; mailing address equals registered address |
| B-34 | Back from the Review step returns to Points of Contact with the entered contacts kept | Edge client on the Review step, click the wizard's Back button | Points of Contact is shown again with the same Business/Finance/HR values |
| B-35 | Back-and-edit: values changed on Organization Details after going Back are the ones on the Review page | Edge client on Points of Contact: Back to Organization Details, change years in industry / website / address, Continue twice, read the Review page (Confirm is NOT clicked) | The earlier values are still there after Back; the Review page shows the NEW values (not the old ones); the mailing address follows the registered address |
| B-36 | Back-and-edit: contacts changed on Points of Contact after going Back from Review are the ones on the Review page | Edge client on the Review step: Back, change the Finance contact name / e-mail / phone, Continue, read the Review page (Confirm is NOT clicked) | The Review page shows the NEW Finance contact; the Business and HR contacts are unchanged |
| B-37 | Documents uploaded in step 1 are still attached after Back from Points of Contact | Edge client on Points of Contact (CIN and GST were uploaded in step 1): click Back, look at the document slots | Both documents (CIN, GST) are still shown with a Remove control |

#### Admin session and EOR status of an ACTIVE client

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| B-22 | Admin session survives opening a deep link (/clients) | Signed in as admin, open https://sandbox.kompglobal.com/clients by URL (full page load) | The Clients page is shown, not the login page or the dashboard |
| B-23 | Signed-in admin opening /login is sent back to the console | Signed in as admin, open https://sandbox.kompglobal.com/login | Redirect to the console (a signed-in user is not shown the login form) |
| B-24 | After signing out, browser Back does not show protected content | Signed in as admin on /clients, sign out via the profile menu, press the browser Back button | Login page (or a signed-out state); the Clients list is not shown |
| B-25 | Refresh (F5) on the Clients page keeps the Clients page | Signed in as admin, open Clients from the sidebar, press F5 | The Clients page is shown again (not the dashboard / login) |
| B-40 | An ACTIVE MSA does not offer 'Approve & Activate' again | Signed in as admin/EOR, MSA & Contract > MSA, search an ACTIVE client, click its status badge (Approve is never clicked) | The status badge is not actionable, or the dialog offers no 'Approve & Activate' |
| B-41 | Refresh sign status on an ACTIVE MSA keeps it ACTIVE | Signed in as admin/EOR, MSA list, search an ACTIVE client, click the Refresh sign status icon | Status stays ACTIVE (and the start date does not change) |

#### Clients list: search

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| X-01 | Search by the full company name returns only matching clients | Clients > search box = the full name of an existing client | Only rows whose company or POC contains the term; the client is listed; other clients are filtered out |
| X-02 | Search by a partial company name (last characters) | Clients > search box = the last 8 characters of an existing client name | The client is listed; every row matches the fragment |
| X-03 | Search by the point of contact name | Clients > search box = the contact name of an existing client | The client is listed; every row matches the POC name |
| X-04 | Search ignores upper / lower case | Clients > search box = an existing client name in UPPER CASE | Same client found as with the original case |
| X-05 | Leading and trailing spaces in the search are ignored | Clients > search box = an existing client name with 3 spaces before and after | Same client found as without the spaces |
| X-06 | A search with no match shows an empty-state message | Clients > search box = 'zzz-no-such-client-<number>' | No client rows and a readable empty-state message (not the full list, not a blank table) |
| X-07 | Search text '<script>alert(1)</script>' is handled safely | Clients > search box = '<script>alert(1)</script>' | No script runs, no server error (5xx), the list shows no match or only matching rows, the page stays usable |
| X-08 | Search text '%' is handled safely | Clients > search box = '%' | No script runs, no server error (5xx), the list shows no match or only matching rows, the page stays usable |
| X-09 | Search text ''' is handled safely | Clients > search box = ''' | No script runs, no server error (5xx), the list shows no match or only matching rows, the page stays usable |
| X-10 | Search text '"; DROP TABLE clients;--' is handled safely | Clients > search box = '"; DROP TABLE clients;--' | No script runs, no server error (5xx), the list shows no match or only matching rows, the page stays usable |
| X-11 | Search text '\' is handled safely | Clients > search box = '\' | No script runs, no server error (5xx), the list shows no match or only matching rows, the page stays usable |
| X-12 | Search text 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA... (300 chars)' is handled safely | Clients > search box = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA... (300 chars)' | No script runs, no server error (5xx), the list shows no match or only matching rows, the page stays usable |
| X-13 | Clearing the search restores the full list | Search for a client, then clear the search box | The same number of rows and the same 'Showing ... of N' total as before searching |

#### Clients list: filters

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| X-20 | Country filter = India lists exactly the India clients | Clients > Country > tick 'India' | Every row has Country India and 'of N' equals the number of India clients in the full list |
| X-21 | Country filter = Brazil lists exactly the Brazil clients (or the empty state) | Clients > Country > tick 'Brazil' | Every row has Country Brazil, 'of N' equals their number; with none, 'No clients found' is shown |
| X-22 | Country filter = United States lists exactly the US clients (or the empty state) | Clients > Country > tick 'United States' | Every row has Country United States, 'of N' equals their number; with none, 'No clients found' is shown |
| X-23 | Client Type filter = AOR lists exactly the AOR clients | Clients > Client Type > tick 'AOR' | Every row is AOR and 'of N' equals the number of AOR clients in the full list |
| X-24 | Client Type filter = EOR lists exactly the EOR clients | Clients > Client Type > tick 'EOR' | Every row is EOR and 'of N' equals the number of EOR clients in the full list |
| X-25 | Country filter accepts several countries (India + Brazil) | Clients > Country > tick 'India' and 'Brazil' | Rows from either country; 'of N' equals the number of India + Brazil clients |
| X-26 | Country and Client Type filters combine (India AND AOR) | Clients > Country 'India' + Client Type 'AOR' | Only clients that are India AND AOR; 'of N' equals their number |
| X-27 | The search field inside the Country filter narrows its options | Clients > Country > type 'bra', then 'brazzz' | 'bra' shows only Brazil; a term without match shows 'No results.' |
| X-28 | A filter and the search box combine (AND) | Client Type 'AOR' + search the full name of an existing client (the client is EOR), then Client Type 'EOR' + the same search | With the other type: no rows and an empty-state message; with its own type: the client is listed |
| X-29 | 'Clear all' resets the Country and Client Type filters | Clients > Country 'India' + Client Type 'AOR', then click 'Clear all' | Both filters read 'Any' again and the full list ('of N' = all clients) is shown |
| X-30 | Status tile 'Active' filters the list by status | Clients > click the 'Active' tile | Only rows with that status; 'of N' equals the number of such clients; no clients -> 'No clients found' |
| X-31 | Status tile 'In-Progress' filters the list by status | Clients > click the 'In-Progress' tile | Only rows with that status; 'of N' equals the number of such clients; no clients -> 'No clients found' |
| X-32 | Status tile 'Inactive' filters the list by status | Clients > click the 'Inactive' tile | Only rows with that status; 'of N' equals the number of such clients; no clients -> 'No clients found' |
| X-33 | Status tile 'Total' filters the list by status | Clients > click the 'Total' tile | Only rows with that status; 'of N' equals the number of such clients; no clients -> 'No clients found' |
| X-34 | A filter stays applied on page 2 | Clients > Country 'India', then page 2 | Page 2 shows only India clients and 'Showing 11-20 of N' |
| X-35 | Applying a filter while on page 2 returns to page 1 | Clients > page 2, then Client Type 'AOR' (few clients) | The first page of the AOR clients is shown ('Showing 1-...'), not an empty page 2 |

#### Clients list: pagination, sort, columns

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| X-40 | Page 2 shows different clients than page 1 | Clients > click page '2' of the pagination | Different rows, and the 'Showing' text moves to 'Showing 11-20 of N' |
| X-41 | Page size 5 shows 5 clients per page | Clients > page size '5 / page' | min(5, N) rows and 'Showing 1-5 of N clients' |
| X-42 | Page size 10 shows 10 clients per page | Clients > page size '10 / page' | min(10, N) rows and 'Showing 1-10 of N clients' |
| X-43 | Page size 20 shows 20 clients per page | Clients > page size '20 / page' | min(20, N) rows and 'Showing 1-20 of N clients' |
| X-44 | Page size 30 shows 30 clients per page | Clients > page size '30 / page' | min(30, N) rows and 'Showing 1-30 of N clients' |
| X-45 | Page size 40 shows 40 clients per page | Clients > page size '40 / page' | min(40, N) rows and 'Showing 1-40 of N clients' |
| X-46 | Page size 50 shows 50 clients per page | Clients > page size '50 / page' | min(50, N) rows and 'Showing 1-50 of N clients' |
| X-47 | Previous / Next buttons are enabled only where there is a page to go to | Clients (10 per page): check Previous on page 1, click Next to the last page, check Next, click Previous | Previous is disabled on page 1; Next is disabled on the last page ('Showing ...-N of N'); Previous goes back one page |
| X-48 | Stat tiles (Total / Active / Inactive / In-Progress) match the clients in the list | Clients > read the four tiles and compare them with all clients (50 per page) | Total = number of clients; each tile = number of clients with that status; the tiles add up to Total |
| X-50 | Sorting by 'Company': ascending, descending, then back to the default order | Clients > click the 'Company' header three times (10 per page) | 1st click ascending, 2nd descending, 3rd the original order |
| X-51 | Sorting by 'POC Name': ascending, descending, then back to the default order | Clients > click the 'POC Name' header three times (10 per page) | 1st click ascending, 2nd descending, 3rd the original order |
| X-52 | Sorting by 'Client Type': ascending, descending, then back to the default order | Clients > click the 'Client Type' header three times (10 per page) | 1st click ascending, 2nd descending, 3rd the original order |
| X-53 | Sorting by 'Country': ascending, descending, then back to the default order | Clients > click the 'Country' header three times (10 per page) | 1st click ascending, 2nd descending, 3rd the original order |
| X-54 | Sorting by 'Status': ascending, descending, then back to the default order | Clients > click the 'Status' header three times (10 per page) | 1st click ascending, 2nd descending, 3rd the original order |
| X-55 | Sorting by Company orders ALL clients, not only the 10 rows of the current page | Clients (10 per page) > click the 'Company' header once and compare page 1 with the first 10 of ALL companies sorted | Page 1 holds the 10 alphabetically first companies of the whole list |
| X-60 | Columns menu: hide and show the 'Company' column | Clients > Columns > untick 'Company', then tick it again | The column header and the cells disappear, then come back; the other columns are untouched |
| X-61 | Columns menu: hide and show the 'POC Name' column | Clients > Columns > untick 'POC Name', then tick it again | The column header and the cells disappear, then come back; the other columns are untouched |
| X-62 | Columns menu: hide and show the 'Client Type' column | Clients > Columns > untick 'Client Type', then tick it again | The column header and the cells disappear, then come back; the other columns are untouched |
| X-63 | Columns menu: hide and show the 'Country' column | Clients > Columns > untick 'Country', then tick it again | The column header and the cells disappear, then come back; the other columns are untouched |
| X-64 | Columns menu: hide and show the 'Mobile Number' column | Clients > Columns > untick 'Mobile Number', then tick it again | The column header and the cells disappear, then come back; the other columns are untouched |
| X-65 | Columns menu: hide and show the 'Status' column | Clients > Columns > untick 'Status', then tick it again | The column header and the cells disappear, then come back; the other columns are untouched |

#### MSA search and API health

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| X-70 | MSA & Contract search by client name filters the list | MSA & Contract > MSA, search box = the name of an ACTIVE client | Only that client's row(s) are listed |
| X-71 | MSA & Contract search with no match shows no rows and a message | MSA & Contract > MSA, search box = 'zzz-no-such-client-<number>' | No rows and a readable empty-state message |
| X-72 | MSA search text '<script>alert(1)</script>' is handled safely | MSA & Contract > MSA, search box = '<script>alert(1)</script>' | No script runs, no server error, no match or only matching rows |
| X-80 | Browsing the admin console produces no 5xx API responses | Signed in as admin: Dashboard, Clients, MSA & Contract > MSA, Candidates, Appraisal (read-only) | Every API call returns 2xx/3xx (no 500/502/503, no failed requests) |
| X-81 | Browsing the client portal produces no 5xx API responses (incl. notifications) | Signed in as the edge client (masked): portal home, then the wizard via Continue (read-only), then back to home | Every API call returns 2xx/3xx; GET .../notifications/push_api/user-notification/ is 200 |

## 6. Application bugs found so far

- **Register Client:** over-long e-mail / legal name -> HTTP 500 and raw database text (R-14, R-19); a whitespace-only contact name is only refused by the server, with the technical
  field name and no inline message (R-20); the duplicate legal-name check is case- and whitespace-sensitive (UPPER CASE + trailing space registers a duplicate, R-31);
  a double click on Register shows a raw duplicate-key error that includes the e-mail (R-60).
- **First login:** a 300-character password is accepted (P-23).
- **Organization Details:** years in industry accepts -5 / abc / 1000 (O-02..O-04); pincode format is not validated (O-10..O-13); CIN and GSTIN format / length are not validated
  (O-21, O-22, O-24, O-25); an invalid website is refused only with a generic toast that does not name the field (O-06, O-07).
- **Documents:** any file type, empty or fake files, a 21 MB file and a 180-character file name are accepted (D-01..D-08); Continue works with no documents (D-20, to be confirmed with the product team);
  the document slots are empty after Back from step 2 (B-37); **the admin client profile shows both uploaded documents as "Not uploaded"** (E2E-05).
- **Access / session:** `/candidates` stays on a blank page for a client (B-15); `/login` is shown again to a signed-in client or admin (B-19, B-23); the admin deep link `/clients` lands on the dashboard (B-22).
- **Clients list:** the column sort only re-orders the 10 rows of the current page (X-55); the status tiles do not add up to Total because "Pending" has no tile (X-48, to be confirmed).
- **API (seen in earlier executions, not re-runnable without a new client):** `POST msa-contract/api/status/` answers 404 while the MSA is generated (3 of 3 runs, the journey still completes);
  `GET notifications/push_api/user-notification/` answered 500 once (intermittent).

## 7. Code layout

- `pages/` page objects (locators verified against the live sandbox), `flow/ClientOnboardingFlow` one method per journey phase, `base/` (`BaseTest` lifecycle + helpers, `BasePage`, `DriverFactory`),
  `reporting/` (`TestReporter.phase()` runs a phase + screenshot + Extent node; `defect()` = soft failure; `finish(status, throwable)` lets a set-up case write its own row; a SkipException inside a phase
  is recorded as SKIP; `ExecutionInfo` = the dated run folder; `ExtentManager`), `api/NetworkCapture` (method / path / status only), `utils/` (`ConfigManager`, `SecretMasker`, `ToastWatcher`,
  `TestDataGenerator`, `FormFeedback`, `PageProbe`), `model/ClientData` (public mutable fields).
- `BaseTest`: one browser per class (a window closed by hand is replaced automatically), teardown always runs, the temporary Chrome profile is deleted, `mustReject` verdict rules
  (rejected with a relevant message = PASS; accepted / silent / misleading / raw database text = FAIL), `-Dcases` filter.
- `ClientOnboardingEdgeCases`: sections in run order. `ensureMailRead / ensureDialog / ensurePassword / ensureOrgStep / ensurePocStep / ensureReviewStep` = set-up on demand (own report cases
  P-00, P-02, P-30, O-00, C-00 the first time) and silent healing afterwards; `prepare(...)` writes a SKIP / FAIL row when a case cannot be prepared; `runCase(...)` / `mustReject(...)` start the report case.
- `ClientOnboardingE2EHappyFlow`: TC-E2E-01 runs the 17 phases through `ClientOnboardingFlow`; E2E-02..07 compare the profile pages (`pages/ClientProfilePage`, `ClientPortalHomePage`) with the saved data.
- Secrets: `config/secrets.local.properties` or `KOMP_*` env vars. `tools/recon/` is a dev helper that attaches to a Chrome started with `--remote-debugging-port=9222`. Dev probes belong in the scratchpad, not in the repo.

## 8. Live facts about the application (verified on the sandbox)

- Register Client is a PAGE (`/clients/register`, reached from the sidebar), not a dialog. Inline errors are `<p class="text-destructive">`; server errors arrive as sonner toasts
  (`[data-sonner-toast]`) that can stay and cover buttons: close them first, click Continue via JS.
- Clients list: Country / Client Type = Radix popovers with a searchable multi-select (`[data-slot=popover-trigger]`, `[cmdk-item]`, "Clear all"); the Total / Active / Inactive / In-Progress tiles
  filter by status (no tile for "Pending"); page size is a native `<select>` (5..50); headers sort; Columns is a dropdown of checkbox items; rows are `tr[data-row-id]`; the search is case-insensitive,
  so names that differ only in case match together (click the EXACT row).
- **Admin client profile** (`/clients/<id>`, click a row): header `h1` "<name>(<type>)" + status badge + e-mail; tiles NAME / PHONE NO. / COMPANY NO (= CIN) / MSA ID; tabs "Company Details"
  (cards About Organization, Mailing Address, Legal Details as `dl/dt/dd`), "Point of Contact" (Business / Finance / HR), "MSA" (signed document card: Preview opens a full-screen
  "Signed MSA Document" PDF viewer that must be closed before "Back to Clients"). Every card has a pencil (edit) button. An empty value is a dash.
- **Client portal** (`/portal`): nav Home / Employees / Projects / MSA; profile menu (needs a real click) = My Profile (a dialog: company name, e-mail, phone) / Change Password / Log out.
  A client with unfinished onboarding gets a "Finish setting up your company" dialog with "Continue Setup" on the portal home that covers the page (it blocks clicks on Start / Continue).
- Wizard: Organization Details -> "Continue Setup" prompt -> Points of Contact -> Review -> Sign MSA. The portal home shows `Start` for a new client and `Continue` once the wizard was used;
  the wizard has a `Back` button. The registered e-mail / mobile on step 1 are pre-filled and read-only.
- The app rate-limits sign-ins ("Too many sign-in attempts", HTTP 429): `LoginPage` waits and retries; sign in once per role where possible.
- Welcome e-mail (Yopmail): subject "Welcome to KOMP - Your client portal is ready", body has "Temporary password: ...". The first login shows "Set a new password" (policy: 8+ characters with
  upper, lower, number and symbol) and then signs the client out. Signing the MSA completes by itself in the sandbox; the client is then signed out and sees 67% until the EOR approves.

## 9. Rules learned

- Do not guess selectors or API contracts: inspect the live app first (scratchpad probe). Prefer explicit waits; no `Thread.sleep` except the documented ones (e-mail poll, rate-limit wait, list re-render).
- The "mailing same as registered" checkbox is ticked by default: only click when not already ticked.
- MSA generation is async: wait on `iframe[title='Sign MSA']` with a `blob:` src, not a fixed delay.
- API validation is "NOT CONFIGURED" until the user supplies contracts; calls are recorded as observations only.
- In test classes do not name a field `org` (it shadows the `org.openqa...` package); the edge class uses `orgPage`, `pocPage` ...
- Restore a form value AFTER the phase screenshot, so the evidence shows the rejected state.
- A set-up / prerequisite step must run BEFORE the case's `TestReporter.start(...)`, or use `TestReporter.finish(status, throwable)` - otherwise two cases overlap.
- Never list a side-effect case (R-31, R-50..R-52) in `-Dcases` just to "test something": if the app accepts it, it creates an extra client.
- A list that "stopped changing for 1.5 s" is NOT necessarily settled: a slow search leaves the OLD rows in place for a while (X-28 and X-71 failed once because of it). After typing a search term, wait until
  the list is empty or shows the term (`ClientsPage.matchesSearchBox`, `msaRows()`), never only until it is stable.
- The set-up step that opens the login page (`LoginPage.freshLogin`) reloads once when the form does not appear within 20 s (the sandbox was seen loading it very slowly once, B-01).
- The Clients / MSA table sizes itself from the window height (`--table-max-h`, 300 ms animation): anything that resizes the window (the full-page screenshot) must wait for the layout to settle afterwards.
- A table row with fewer than 6 cells is the empty-state / loading row, not a client: skip it before reading cells (`ClientsPage.firstYopmailClient` threw `Index 1 out of bounds` on it and failed R-30 / R-32 / R-33
  when the list was empty at that moment).
- When a run shows dozens of unrelated failures at the same minute, read the first error in `logs/execution.log` before anything else: `net::ERR_...` = the machine / network, not the application.
- After a page-changing step never trust "the first poll": wait for a condition, and treat a one-off timeout as a possible slow page (reload once) before calling it a defect. Re-run the failing case alone
  (`-Dcases=<ID>`) to tell a flaky wait from an application bug; X-28, X-71 and B-01 were flaky waits, not findings.
- The VS Code Java language server can show stale or invented errors (its copy of a file drifts after external edits). The truth is `mvn -q -DskipTests test-compile` (silent = clean).
  To clear the red marks: close and reopen the file, or run "Java: Clean Java Language Server Workspace" / "Developer: Reload Window". Do not "fix" code that Maven compiles cleanly.
