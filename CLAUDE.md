# Client Onboarding - test guide

Selenium 4 + TestNG + Maven (Java 17) + Extent Reports automation for the KOMP Global **client onboarding** module (sandbox).
Independent of the old `C:\Testing\KOMP IND - Java` repo: never modify that repo.

## 1. What is in the project (client onboarding: 2 test classes, 2 XML suites; project management: 2 more, see section 10)

| XML suite (repo root) | Test class (`src/test/java/tests/client/`, package `tests.client`) | What it does | Clients created |
|---|---|---|---|
| `testng-client-happy-flow.xml` | `ClientOnboardingE2EHappyFlow` | **8 test cases.** TC-E2E-01 = the whole happy journey (register -> ... -> Active, 17 phases in ONE case), then TC-E2E-02..08 = re-check the finished client in this order: MSA list -> Clients list -> admin profile -> Point of Contact tab -> MSA tab -> client sign-in + My Profile -> every client-portal tab; one table of values per page | **1** |
| `testng-client-edge-cases.xml` | `ClientOnboardingEdgeCases` | **201 test cases** (73 test methods, many data-driven): every negative / edge case of the module, on ONE client, plus read-only admin / Clients-list checks | **1** |

The shared lifecycle (browser, report, helpers) is `src/main/java/base/BaseTest.java`, kept OUT of `tests/` so that the folder `tests/client/` holds only the two client-onboarding classes (tests of other modules get their own folder next to it).
**No load testing; nothing that could break or flood the shared environment.**
The **Project Management module** (projects of an existing client, AOR + EOR) lives next to it in its own folder `src/test/java/tests/project/` with its own two XML suites: see **section 10**. It creates no client, so the 2-client rule below is not touched.

## 2. Working rules (follow always)

- **2-CLIENT RULE: the module never creates more than 2 clients** (1 happy flow + 1 edge cases). All edge cases share one client: enter details, move next, go back,
  re-enter different values ... Do not register extra clients (also not while debugging): use `-Dedge.client.email=<yopmail>` (+ `-Dedge.client.fresh=true
  -Dedge.client.name="<name>"` for a never-logged-in leftover Pending client) or `-Dhappy.client.data=<test-data.json>`. The cases that could register an EXTRA client when the app
  accepts their input (R-31 duplicate legal name in UPPER CASE + trailing space, R-50..R-52 special characters in the legal name) and P-01 (wrong temporary password) were REMOVED from the
  suite on 2026-11-08 at the user's request, so no case is skipped because of the client limit any more. Leftover test clients cannot be deleted by automation.
- The user is a QA, not a developer: the focus is FINDING BUGS. The suites must be robust ("run it any time"): self-healing state, retries for the sign-in rate limit,
  automatic browser recovery, clear messages. Keep it that way.
  **A lost network connection is not a result** (`utils/Connectivity`, `BaseTest.ensureBrowserAlive`): a step cut off by `net::ERR_INTERNET_DISCONNECTED` / `ERR_NAME_NOT_RESOLVED` ... (or a browser left on
  Chrome's "This site can't be reached" page) is reported as SKIP "network lost - run the case again", never FAIL; before the next case the suite waits until the browser can open the login page again
  (polls every 10 s, `-Dnetwork.wait.max.seconds`, default 600; after one outage that never ended later waits are 30 s), goes on from a clean page and signs in again when a case needs a session.
  (Seen: a 13-second outage made 35 cases fail and 85 more skip for 13 minutes because the suite kept clicking on the error page.)
- Run with the browser VISIBLE and maximized (no `-DHeadless=true` unless asked). Long runs may use the harness' background-task mechanism, the browser is still visible.
- Use ONLY `@yopmail.com` addresses wherever an e-mail is needed (user, 2026-11-11: a deliberately MALFORMED address in a negative case - "plainaddress", "user@@yopmail.com" - is fine; every well-formed address must be @yopmail.com).
- **Client names are ANIME names** for the two clients the tests create (110 anime in `utils/ClientNames`, each with 4 characters):
  client (legal entity) name = the anime ("Dragon Ball Z"); contact person at registration = its MAIN CHARACTER ("Son Goku") with e-mail = name in lower case + `@yopmail.com`
  (`songoku@yopmail.com`, also the client's login address); Business / Finance / HR contacts of the wizard = three other characters of the same anime ("Vegeta" `vegeta@yopmail.com`, "Son Gohan", "Piccolo").
  No e-mail is shared by two anime (checked offline). `testdata/existing-clients.txt` is the registry of anime already used
  (`name | e-mail | created by | date time`, comment lines start with `#`): before a client is registered the file is READ and an anime that is not listed is picked
  (`ClientNames.pick()`, applied with `TestDataGenerator.applyPick`), right after the registration the client is APPENDED (`ClientNames.record`). If the application still says the name or an e-mail exists
  ("Client with this organization name is already exist!") the anime is added to the file and the next one is used (up to 5 tries). When all 110 are used the names get a number
  ("Naruto 2", e-mails `narutouzumaki2@yopmail.com`). Delete a line to free a name. This registry is SEPARATE from the duplicate-name test cases (R-30, R-32, R-33), which use a client that exists in the Clients list.
  The throw-away data of the negative cases (`newData()` / `TestDataGenerator.generateScratch()`) keeps timestamped non-anime names. Override the file with `-Dclient.names.file=<path>`.
  Yopmail inboxes are public: addresses like `songoku@yopmail.com` may hold other people's mails; the welcome mail is found by its subject and checked for the client name and e-mail.
- **REPORT WORDING = PLAIN LANGUAGE (standing rule, user 2026-11-08: "too technical, make it for a layman / non-technical person ... apply it all over the reports and to future cases").**
  Every report (client happy flow, client edge cases, project happy flow, project edge cases, and every module added later) is read by managers and product owners, not by developers.
  Every sentence that reaches a report - case titles, "what should happen", "what happened", problem titles, skip reasons, table rows, notes - is written in everyday words:
  say what the application DID and what it SHOULD have done ("The application refused the entry and showed this message: ...", "The client can see a page of the admin area"), never HTTP codes
  (500 / 5xx / 4xx), "API", "toast", "inline message", "DOM", "selector", "validation", "payload", "wizard", "dialog", "empty-state", "badge", "deep link", "session", "XSS", "assertion", "exception" or field keys such as `years_in_industry`.
  Quote the application's own words in double quotes (they are evidence and stay exactly as shown). Technical evidence (error traces, server requests, the technical message of a step the test could not complete)
  is kept, but only inside the folded "Technical details (only for developers - click to open)" block of `TestReporter`. How: use `Outcome.describe()` / `Landing.describe()` for what the application did,
  `PlainText` (`count`, `some`, `quote`, `assertion`) for sentences, `TestReporter.defect(title, expected, actual)` with a PLAIN SENTENCE as the title, and plain `Assert` messages (they become "PROBLEM FOUND - ..." in the report).
  Safety net: `PlainText.simplify` translates a technical term that slipped into a report sentence and the end-of-class summary / `execution.log` then lists the sentence ("Report wording: N sentence(s) used technical words") -
  an empty list is the goal: reword the sentence at the source. A new case is not finished until its report reads well to someone who has never seen the application's code.
- Every finding needs evidence: the HTML report has steps, "what should happen" vs "what happened", screenshots, log and (folded, for developers) the server requests per case.
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
**Progress bar in the browser** (`reporting/RunBanner`, user request 2026-11-08): a small box on the visible browser shows "Total TC - N / Ran so far - N / Remaining - N / Time - MM:SS" and a thin progress line. It is for the person
watching the run only: drawn outside the page body, click-through except its drag grip (the dotted handle on the left: drag it anywhere in the window, the position is remembered), top window only, every error swallowed;
`-Drun.banner=false` switches it off. No blinking: the numbers change in place (the line moves with a transition), the timer runs inside the browser, the bar is updated BEFORE the report file is written (that gets slower with every case),
and it is hidden only for the instant of a report picture (never in the evidence). The total is counted just before the first case (data-driven cases = one per data row; with `-Dcases` = the number of ids); the log prints
"Test cases planned for this run: N" and, at the end, planned vs finished.
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
      ClientOnboardingE2EHappyFlow.html   Extent report (steps, what should happen vs what happened, screenshots; server requests folded); share the whole folder (links are relative)
      screenshots/                         NN_<phase>_PASS|FAIL|DEFECT|SKIP.png  (this run only)
      logs/execution.log
      api-evidence/                        network-<caseId>.json  (method + path + status only: no headers, cookies or bodies)
      test-data.json                       the client data of the run (no passwords) - feeds -Dhappy.client.data / -Dedge.client.email
  ClientOnboardingEdgeCases/
    2026-11-07_07.14.10/ ...
```
**Screenshots are FULL-PAGE** (`reporting/ScreenshotManager`): the KOMP pages scroll inside their own panel, so a normal screenshot cuts off everything below the fold (the TC-E2E-04 evidence - the
"Not uploaded" document rows - was invisible). For a page whose CONTENT is taller than the window the browser view is enlarged for a moment (DevTools device-metrics override), the page gets time to settle
(the app recomputes the height of its tables and animates it for 300 ms: a capture taken at once shows half the rows), the real end of the content is measured (sidebar, sticky and fixed parts ignored) and the
page is captured down to that point (no blank space); the view is then restored and settled again, scroll positions kept, so the next step meets the page as it was (~1.3 s extra per such screenshot).
Pages that fit, pages that only overflow in the sidebar, and pages with something open on top (dialog, menu, list, PDF viewer / iframe, full-screen overlay - resizing would blank the PDF) get the normal screenshot.
`-Dscreenshot.fullpage=false` switches it off, `-Dscreenshot.max.height=6000` caps the height (px), `-Dscreenshot.settle.ms=350` is the minimum wait after each resize.
The Yopmail welcome-mail screenshot scrolls the temporary password into view and outlines it in red.
**The report HTML must never execute test data.** Extent writes test names unescaped (HTML sidebar + the report's own script block): a case title such as "Search text '<script>alert(1)</script>'" (X-07, X-72) ran the
script when the report was opened. `TestReporter.plain()` puts a zero-width space after every `<` in names / descriptions / node names; log and step texts go through `esc()`. Check a new report by opening it in a browser: no alert.
**A case title says what the case CHECKS, not what happened** ("... documents are shown"). The verdict is the status label (Pass / Fail / Skip), and under the title of every case stands one plain line:
a failed case "FAILED because: <the problems in plain sentences> | What was checked: <what the case checks>", a skipped case "NOT RUN because: <reason> | What was checked: ...", a passed case what was checked.
The steps of a case are "Step 1 - ...", each with "What should happen" and "What happened" (a step that found a problem says "PROBLEM FOUND - ...", a step the test could not carry out says "THE TEST COULD NOT COMPLETE THIS STEP - ...");
the last node "Summary of this test" holds the result in words (Passed / Failed / Not run), the step table, the list of problems and - folded - the server requests. A step that found a problem is marked FAIL
(with the DEFECT screenshot), never PASS; a case with a problem is never PASS.
The two chips under a case title are its tags: the module ("Client-Onboarding") and its group ("Check-of-the-finished-client" = the cases TC-E2E-02..08, "Client-journey" = the journey; the edge cases use "A1 Register client", "B2 What a client must not see", ...), used by
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
**Skips in a run:** O-15..O-19 always (read-only pre-filled fields: nothing to type) and R-60 only when an existing client is reused with `-Dedge.client.email` (nothing is registered);
the first-login password cases P-00, P-02, P-10..P-23, P-30 are not run at all then. Nothing is skipped because of the client limit.

### Happy flow - `ClientOnboardingE2EHappyFlow` (8 test cases, always numbered from TC-E2E-01)

After the journey the finished client is re-checked **in this order, one case per page**. Every value of a page is one row of ONE table in the report (where / value /
expected / shown on the page / OK or WRONG, wrong rows in red and listed first) and the page gets ONE screenshot, so a failure reads "2 of 28 values are wrong - the other 26
are correct" instead of several cases that show the same page. When an existing client is re-checked (`-Dhappy.client.data`) TC-E2E-01 is written as SKIP, so the numbering still starts at 1.

| ID | What it tests | Expected |
|---|---|---|
| TC-E2E-01 | Client onboarding from start to finish: register, set up in the client portal, sign the MSA, approval, the client becomes Active | The whole journey is completed and the client ends up Active (17 steps); not run when an existing client is only re-checked |
| TC-E2E-02 | MSA & Contract: sign in as the admin, search the client in the MSA list | The row shows the client, its MSA id, a start date, its country and the status **ACTIVE** |
| TC-E2E-03 | Clients list: search the client | The row shows company, e-mail, contact person, client type, country, mobile number and the status **Active** |
| TC-E2E-04 | Admin client profile (click the row): every value of the page in one table | Heading + type, status, e-mail, the four tiles (name, phone, company no = CIN, MSA id), About Organization, Mailing Address, Legal Details incl. the CIN and GST documents all equal what was entered; the documents are shown, not 'Not uploaded' (KNOWN BUG: the two document rows fail, the other values are correct) |
| TC-E2E-05 | Admin client profile > Point of Contact tab | Name, designation, e-mail and mobile number of the Business, Finance and HR contacts equal what was entered |
| TC-E2E-06 | Admin client profile > MSA tab | The signed MSA card is listed, Preview opens a PDF with content, Download is enabled (never clicked) |
| TC-E2E-07 | Sign in as the client: portal home and My Profile | Welcome heading = company name, Company Onboarding 100%, the profile button shows the contact name and e-mail, My Profile shows company name, e-mail and phone |
| TC-E2E-08 | Client portal, every tab of the left menu in turn (one step + table + screenshot each): Home, Employees, Projects, MSA | Each tab opens with its heading and content; Home shows 100% and the three steps; Employees / Projects show their columns and the list or the empty message (a new client has none); MSA shows this client's name, status ACTIVE, MSA id and the amendment row; no request a tab sends to the server fails (no server error) |

### Edge cases - `ClientOnboardingEdgeCases` (201 test cases)

#### Login (signed out)

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| L-01 | Both fields empty | Email and password empty, Sign in clicked | The application should refuse the sign-in, keep the user on the login page, and show a message that explains the problem |
| L-02 | Password empty | Valid-format yopmail address, password empty | The application should refuse the sign-in, keep the user on the login page, and show a message that explains the problem |
| L-03 | Email empty | Email empty, password entered | The application should refuse the sign-in, keep the user on the login page, and show a message that explains the problem |
| L-04 | Email without @ | Email = plainaddress, password entered | The application should refuse the sign-in, keep the user on the login page, and show a message that explains the problem |
| L-05 | Non-existent user | Unknown yopmail address with a password | The application should refuse the sign-in, keep the user on the login page, and show a message that explains the problem |
| L-06 | SQL-injection style input | Email = admin' OR '1'='1, password = ' OR '1'='1 | The application should refuse the sign-in, keep the user on the login page, and show a message that explains the problem |
| L-07 | Very long email (300+ chars) | Email = 300 x 'a' + @yopmail.com, password entered | The application should refuse the sign-in, keep the user on the login page, and show a message that explains the problem |
| L-08 | Whitespace-only password | Unknown yopmail address, password = 3 spaces | The application should refuse the sign-in, keep the user on the login page, and show a message that explains the problem |
| L-09 | Existing client, wrong password | The edge client's e-mail with a wrong password (single attempt) | The application should refuse the sign-in, keep the user on the login page, and show a message that explains the problem |
| L-20 | Password field is masked | Text typed into the password field | The characters typed into the password box should be hidden |

#### Opening pages directly when nobody is signed in

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| B-01 | Opening /clients without being signed in shows the login page | A new browser window where nobody is signed in: the address https://sandbox.kompglobal.com/clients is opened | The user should be sent to the login page and must not see any protected page |
| B-02 | Opening /clients/register without being signed in shows the login page | A new browser window where nobody is signed in: the address https://sandbox.kompglobal.com/clients/register is opened | The user should be sent to the login page and must not see any protected page |
| B-03 | Opening /contracts without being signed in shows the login page | A new browser window where nobody is signed in: the address https://sandbox.kompglobal.com/contracts is opened | The user should be sent to the login page and must not see any protected page |
| B-04 | Opening /portal without being signed in shows the login page | A new browser window where nobody is signed in: the address https://sandbox.kompglobal.com/portal is opened | The user should be sent to the login page and must not see any protected page |
| B-05 | Opening /portal/onboarding without being signed in shows the login page | A new browser window where nobody is signed in: the address https://sandbox.kompglobal.com/portal/onboarding is opened | The user should be sent to the login page and must not see any protected page |
| B-06 | Opening /documents/management without being signed in shows the login page | A new browser window where nobody is signed in: the address https://sandbox.kompglobal.com/documents/management is opened | The user should be sent to the login page and must not see any protected page |
| B-07 | Opening /candidates without being signed in shows the login page | A new browser window where nobody is signed in: the address https://sandbox.kompglobal.com/candidates is opened | The user should be sent to the login page and must not see any protected page |
| B-08 | Opening /appraisal without being signed in shows the login page | A new browser window where nobody is signed in: the address https://sandbox.kompglobal.com/appraisal is opened | The user should be sent to the login page and must not see any protected page |
| B-09 | Unknown URL shows a login or a friendly 'not found' page, not a blank page | Nobody is signed in; the address https://sandbox.kompglobal.com/no-such-page-xyz is opened | Login page or a readable not-found message; never a blank page or raw error |

#### Register Client form (admin)

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| R-01 | All fields empty | Nothing entered, Register Client clicked | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-02 | Legal entity name empty | Valid data, Legal Entity Name left empty | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-03 | Client type not selected | Valid data, Client Type not selected | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-04 | Country not selected | Valid data, Country not selected | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-05 | Point of contact name empty | Valid data, POC name left empty | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-06 | Email empty | Valid data, Email left empty | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-07 | Contact number empty | Valid data, Contact Number left empty | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-08 | Legal entity name whitespace only | Legal Entity Name = 3 spaces | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-09 | Email without @ | Email = plainaddress | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-10 | Email without domain | Email = user@ | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-11 | Email without local part | Email = @yopmail.com | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-12 | Email with two @ | Email = user@@yopmail.com | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-13 | Email containing a space | Email = 'us er@yopmail.com' | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-14 | Email longer than 254 characters | Email = 250-char local part + @yopmail.com | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-15 | Contact number with letters | Contact Number = abcdefghij (field is expected to refuse letters) | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-16 | Contact number too short (5 digits) | Contact Number = 12345 | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-17 | Contact number too short (9 digits) | Contact Number = 987654321 | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-18 | Contact number with symbols | Contact Number = +91-98765 (symbols are expected to be refused) | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-19 | Legal entity name 300 characters | Legal Entity Name = 300 x 'A' (max length unknown; 300 is far beyond a sane limit) | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-20 | Point of contact name whitespace only | POC name = 3 spaces (client-side check was seen to let this pass) | The application should refuse the entry, stay on the Register Client page, create no client, and show a clear message that explains what is wrong |
| R-30 | Duplicate legal entity name | Legal Entity Name = name of an existing client (same case) | The application should refuse the registration with a message that the client already exists, and create no second client |
| R-32 | Duplicate email | Email = e-mail of an existing client | The application should refuse the registration with a message that the client already exists, and create no second client |
| R-33 | Duplicate email, different case | Email = existing client e-mail in UPPER CASE | The application should refuse the registration with a message that the client already exists, and create no second client |
| R-40 | Contact number field limits length | Contact Number typed = 98765432101 (11 digits); nothing is submitted | Field keeps at most 10 characters |
| R-60 | Double-click on Register Client creates one client | Valid data (the edge client, an anime name); Register Client clicked twice in the same instant | Exactly one client is created |

#### First login and password rules

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| P-00 | Setup: register the test client and read its welcome e-mail | Valid unique data, all yopmail addresses (the edge client's e-mail) | Client registered, welcome mail with the temporary password received |
| P-02 | Setup: first login with the temporary password | Temporary password from the welcome e-mail (masked) | The 'Set a new password' window should open |
| P-10 | All password fields empty | Nothing entered | The application should refuse the password change with a message, keep the window open, and leave the password unchanged |
| P-11 | Current password empty | Valid new password + confirmation, current empty | The application should refuse the password change with a message, keep the window open, and leave the password unchanged |
| P-12 | Wrong current password | Wrong current password with a valid new password (single attempt) | The application should refuse the password change with a message, keep the window open, and leave the password unchanged |
| P-13 | New password too short | New = Ab1! (4 chars) | The application should refuse the password change with a message, keep the window open, and leave the password unchanged |
| P-14 | Confirmation does not match | New and confirmation differ | The application should refuse the password change with a message, keep the window open, and leave the password unchanged |
| P-15 | Confirmation empty | New password entered, confirmation empty | The application should refuse the password change with a message, keep the window open, and leave the password unchanged |
| P-16 | New password whitespace only | New = 8 spaces | The application should refuse the password change with a message, keep the window open, and leave the password unchanged |
| P-17 | New password equals the temporary password | New = the temporary password | The application should refuse the password change with a message, keep the window open, and leave the password unchanged |
| P-18 | New password digits only | New = 12345678 (policy: upper, lower, number, symbol) | The application should refuse the password change with a message, keep the window open, and leave the password unchanged |
| P-19 | New password without a digit | New = Abcdefgh! (policy requires a number) | The application should refuse the password change with a message, keep the window open, and leave the password unchanged |
| P-20 | New password without a symbol | New = Abcdefg12 (policy requires a symbol) | The application should refuse the password change with a message, keep the window open, and leave the password unchanged |
| P-21 | New password without an uppercase letter | New = abcdefg1! (policy requires an uppercase letter) | The application should refuse the password change with a message, keep the window open, and leave the password unchanged |
| P-22 | New password without a lowercase letter | New = ABCDEFG1! (policy requires a lowercase letter) | The application should refuse the password change with a message, keep the window open, and leave the password unchanged |
| P-23 | New password 300 characters (no maximum length) | New = Aa1! + 296 x 'x' | The application should refuse the password change with a message, keep the window open, and leave the password unchanged |
| P-30 | Setup: set the permanent password and log in | Valid common client password (masked) | Login with the new password reaches the portal home |

#### The client on admin URLs, session and reload

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| B-10 | Client cannot open admin page /clients | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/clients | The client should be sent away or told that access is denied, and must not see any admin page |
| B-11 | Client cannot open admin page /clients/register | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/clients/register | The client should be sent away or told that access is denied, and must not see any admin page |
| B-12 | Client cannot open admin page /contracts | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/contracts | The client should be sent away or told that access is denied, and must not see any admin page |
| B-13 | Client cannot open admin page /documents/templates | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/documents/templates | The client should be sent away or told that access is denied, and must not see any admin page |
| B-14 | Client cannot open admin page /documents/management | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/documents/management | The client should be sent away or told that access is denied, and must not see any admin page |
| B-15 | Client cannot open admin page /candidates | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/candidates | The client should be sent away or told that access is denied, and must not see any admin page |
| B-16 | Client cannot open admin page /appraisal | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/appraisal | The client should be sent away or told that access is denied, and must not see any admin page |
| B-17 | Client cannot open admin page /financials/eor/attendance | Signed in as the edge client (masked), open https://sandbox.kompglobal.com/financials/eor/attendance | The client should be sent away or told that access is denied, and must not see any admin page |
| B-18 | Client opening the root URL / lands in the client portal | Signed in as the edge client, open https://sandbox.kompglobal.com/ | The client portal home (/portal) should open, not the admin dashboard |
| B-19 | Signed-in client opening /login: what the application shows | Signed in as the edge client, open https://sandbox.kompglobal.com/login | A readable page. Best: the client is sent back to the portal. If the login form is shown again it is only noted (the sign-in is still valid) |
| B-20 | Unknown URL as a signed-in client shows a readable page | Signed in as the edge client, open https://sandbox.kompglobal.com/no-such-page-xyz | Not-found message or redirect to the portal; never a blank page |
| B-21 | The client stays signed in after the portal page is reloaded | Signed in as the edge client, open https://sandbox.kompglobal.com/portal again (full page load) | Still signed in on the portal; no login prompt |
| B-26 | Refresh (F5) on the client portal keeps the client signed in | Signed in as the edge client on /portal, press F5 | The portal home is shown again, no login prompt |

#### Organization Details (wizard step 1)

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| O-00 | Setup: open Organization Details and fill valid data + documents | Valid organization data and the CIN / GST documents | Form accepts the values and both documents show |
| O-01 | Years in industry empty | All fields are filled in correctly except one: Years in industry = (left empty) | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-02 | Years in industry negative (-5) | All fields are filled in correctly except one: Years in industry = '-5' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-03 | Years in industry text (abc) | All fields are filled in correctly except one: Years in industry = 'abc' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-04 | Years in industry 1000 | All fields are filled in correctly except one: Years in industry = '1000' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-05 | Website empty | All fields are filled in correctly except one: Website = (left empty) | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-06 | Website is not a URL (not a url) | All fields are filled in correctly except one: Website = 'not a url' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-07 | Website with script (javascript:alert(1)) | All fields are filled in correctly except one: Website = 'javascript:alert(1)' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-08 | Description empty | All fields are filled in correctly except one: Product or service description = (left empty) | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-09 | Pincode empty | All fields are filled in correctly except one: Pincode = (left empty) | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-10 | Pincode 5 digits | All fields are filled in correctly except one: Pincode = '12345' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-11 | Pincode 7 digits | All fields are filled in correctly except one: Pincode = '1234567' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-12 | Pincode letters | All fields are filled in correctly except one: Pincode = 'abcdef' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-13 | Pincode 000000 | All fields are filled in correctly except one: Pincode = '000000' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-14 | Address empty | All fields are filled in correctly except one: Address = (left empty) | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-15 | Registered email empty (skipped: the field is pre-filled and read-only) | All fields are filled in correctly except one: Registered e-mail = (left empty) | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-16 | Registered email invalid (plainaddress) (skipped: the field is pre-filled and read-only) | All fields are filled in correctly except one: Registered e-mail = 'plainaddress' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-17 | Registered mobile empty (skipped: the field is pre-filled and read-only) | All fields are filled in correctly except one: Registered mobile number = (left empty) | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-18 | Registered mobile too short (12345) (skipped: the field is pre-filled and read-only) | All fields are filled in correctly except one: Registered mobile number = '12345' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-19 | Registered mobile letters (skipped: the field is pre-filled and read-only) | All fields are filled in correctly except one: Registered mobile number = 'abcdefghij' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-20 | CIN empty | All fields are filled in correctly except one: CIN (company registration number) = (left empty) | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-21 | CIN invalid format (INVALID123) | All fields are filled in correctly except one: CIN (company registration number) = 'INVALID123' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-22 | CIN too long (40 chars) | All fields are filled in correctly except one: CIN (company registration number) = 'U12345MH2020PTC123456XXXXXXXXXXXXXXXXXXX' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-23 | GSTIN empty | All fields are filled in correctly except one: GSTIN (GST number) = (left empty) | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-24 | GSTIN invalid format (INVALIDGST) | All fields are filled in correctly except one: GSTIN (GST number) = 'INVALIDGST' | The application should refuse to continue, show a message, and stay on the Organization Details step |
| O-25 | GSTIN too long (30 chars) | All fields are filled in correctly except one: GSTIN (GST number) = '27ABCDE1234F1Z5999999999999999' | The application should refuse to continue, show a message, and stay on the Organization Details step |

#### Documents (CIN / GST uploads)

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| D-01 | Text file (.txt) as CIN document | CIN slot, file 'not_a_document.txt' (56 bytes) | The application should refuse the file with a message and attach no document |
| D-02 | Executable (.exe) as CIN document | CIN slot, file 'fake_program.exe' (46 bytes) | The application should refuse the file with a message and attach no document |
| D-03 | Empty (0 byte) PDF as CIN document | CIN slot, file 'empty.pdf' (0 bytes) | The application should refuse the file with a message and attach no document |
| D-04 | Text content renamed to .pdf as CIN document | CIN slot, file 'text_renamed.pdf' (38 bytes) | The application should refuse the file with a message and attach no document |
| D-05 | Oversized (21 MB) PDF as CIN document | CIN slot, file 'oversize_21MB.pdf' (22020096 bytes) | The application should refuse the file with a message and attach no document |
| D-06 | PDF with a 180-character file name as CIN document | CIN slot, file 'LLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLL... (180 chars)' (24905 bytes) | The application should refuse the file with a message and attach no document |
| D-07 | Text file (.txt) as GST document | GST slot, file 'not_a_document.txt' (56 bytes) | The application should refuse the file with a message and attach no document |
| D-08 | Executable (.exe) as GST document | GST slot, file 'fake_program.exe' (46 bytes) | The application should refuse the file with a message and attach no document |
| D-10 | Valid PDF with spaces and symbols in its name is accepted | Copy of the CIN pdf named 'cin & test (1) #final.pdf' | Upload accepted and shown with a Remove control |
| D-11 | Remove a document, then upload it again | Valid CIN pdf uploaded, removed, uploaded again | File disappears after Remove and can be uploaded again |

#### Points of Contact (wizard step 2)

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| C-00 | Setup: reach Points of Contact with valid data | Valid organization data + documents, valid three contacts | The onboarding form should reach step 2 (Points of Contact) and accept the valid contacts |
| C-01 | Business contact name empty | All fields are filled in correctly except one: Business contact name = (left empty) | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-02 | Business contact designation empty | All fields are filled in correctly except one: Business contact designation = (left empty) | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-03 | Business contact email empty | All fields are filled in correctly except one: Business contact e-mail = (left empty) | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-04 | Business contact email invalid (abc@) | All fields are filled in correctly except one: Business contact e-mail = 'abc@' | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-05 | Business contact phone empty | All fields are filled in correctly except one: Business contact phone number = (left empty) | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-06 | Business contact phone too short (12345) | All fields are filled in correctly except one: Business contact phone number = '12345' | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-07 | Business contact phone letters | All fields are filled in correctly except one: Business contact phone number = 'abcdefghij' | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-08 | Finance contact name empty | All fields are filled in correctly except one: Finance contact name = (left empty) | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-09 | Finance contact designation empty | All fields are filled in correctly except one: Finance contact designation = (left empty) | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-10 | Finance contact email empty | All fields are filled in correctly except one: Finance contact e-mail = (left empty) | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-11 | Finance contact email invalid (abc@) | All fields are filled in correctly except one: Finance contact e-mail = 'abc@' | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-12 | Finance contact phone empty | All fields are filled in correctly except one: Finance contact phone number = (left empty) | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-13 | Finance contact phone too short (12345) | All fields are filled in correctly except one: Finance contact phone number = '12345' | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-14 | Finance contact phone letters | All fields are filled in correctly except one: Finance contact phone number = 'abcdefghij' | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-15 | Hr contact name empty | All fields are filled in correctly except one: HR contact name = (left empty) | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-16 | Hr contact designation empty | All fields are filled in correctly except one: HR contact designation = (left empty) | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-17 | Hr contact email empty | All fields are filled in correctly except one: HR contact e-mail = (left empty) | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-18 | Hr contact email invalid (abc@) | All fields are filled in correctly except one: HR contact e-mail = 'abc@' | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-19 | Hr contact phone empty | All fields are filled in correctly except one: HR contact phone number = (left empty) | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-20 | Hr contact phone too short (12345) | All fields are filled in correctly except one: HR contact phone number = '12345' | The application should refuse to continue, show a message, and stay on the Points of Contact step |
| C-21 | Hr contact phone letters | All fields are filled in correctly except one: HR contact phone number = 'abcdefghij' | The application should refuse to continue, show a message, and stay on the Points of Contact step |

#### Wizard flow: refresh, Back, re-login, Review page, back-and-edit

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| B-30 | Refresh (F5) inside the onboarding steps keeps the client on the same step | Signed in as the edge client, open the onboarding wizard, press F5 | Still signed in, still on /portal/onboarding, on the same step - or on the furthest step already saved when the client had gone Back (recorded as an observation: the app resumes at the saved progress); never an earlier step |
| B-31 | The browser's Back button in the onboarding steps keeps the client signed in and shows a page | Signed in as the edge client, in the wizard press the browser Back button | A readable portal page (not the login page, not blank) |
| B-32 | Saved organization values survive signing out and signing in again | Edge client: Continue (saves step 1), Back, read the Organization Details form, end the session (cookies/storage cleared), sign in again, read the form again | Every saved text value is identical after signing in again (documents are covered by B-37) |
| B-33 | Review page shows exactly what was entered in Organization Details and Points of Contact | Edge client: valid Organization Details (documents attached) and valid Business/Finance/HR contacts, Continue twice, read the Review page (Confirm is NOT clicked) | Every value on the Review page should equal what was entered, and the mailing address should equal the registered address |
| B-34 | Back from the Review step returns to Points of Contact with the entered contacts kept | Edge client on the Review step, click the wizard's Back button | Points of Contact is shown again with the same Business/Finance/HR values |
| B-35 | Back-and-edit: values changed on Organization Details after going Back are the ones on the Review page | Edge client on Points of Contact: Back to Organization Details, change years in industry / website / address, Continue twice, read the Review page (Confirm is NOT clicked) | The earlier values are still there after Back; the Review page shows the NEW values (not the old ones); the mailing address follows the registered address |
| B-36 | Back-and-edit: contacts changed on Points of Contact after going Back from Review are the ones on the Review page | Edge client on the Review step: Back, change the Finance contact name / e-mail / phone, Continue, read the Review page (Confirm is NOT clicked) | The Review page shows the NEW Finance contact; the Business and HR contacts are unchanged |
| B-37 | Documents uploaded in step 1 are still attached after Back from Points of Contact | Edge client on Points of Contact (CIN and GST were uploaded in step 1): click Back, look at the document slots | Both documents (CIN, GST) are still shown with a Remove control |

#### Admin session and EOR status of an ACTIVE client

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| B-22 | The admin stays signed in when the /clients address is opened directly | Signed in as admin, open https://sandbox.kompglobal.com/clients by URL (full page load) | The Clients page is shown, not the login page or the dashboard |
| B-23 | Signed-in admin opening /login: what the application shows | Signed in as admin, open https://sandbox.kompglobal.com/login | A readable page. Best: the admin is sent back to the console. If the login form is shown again it is only noted (the sign-in is still valid) |
| B-24 | After signing out, browser Back does not show protected content | Signed in as admin on /clients, sign out via the profile menu, press the browser Back button | Login page (or a signed-out state); the Clients list is not shown |
| B-25 | Refresh (F5) on the Clients page keeps the Clients page | Signed in as admin, open Clients from the sidebar, press F5 | The Clients page is shown again (not the dashboard / login) |
| B-40 | An ACTIVE MSA does not offer 'Approve & Activate' again | Signed in as admin/EOR, MSA & Contract > MSA, search an ACTIVE client, click its status badge (Approve is never clicked) | The ACTIVE status label should not be clickable, or the window that opens should not offer 'Approve & Activate' |
| B-41 | Refresh sign status on an ACTIVE MSA keeps it ACTIVE | Signed in as admin/EOR, MSA list, search an ACTIVE client, click the Refresh sign status icon | Status stays ACTIVE (and the start date does not change) |

#### Clients list: search

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| X-01 | Search by the full company name returns only matching clients | Clients > search box = the full name of an existing client | Only clients whose company or contact person contains the search text should be listed, including this client |
| X-02 | Search by a partial company name (last characters) | Clients > search box = the last 8 characters of an existing client name | The client should be listed, and every listed client should contain the search text |
| X-03 | Search by the point of contact name | Clients > search box = the contact name of an existing client | The client should be listed, and every listed client should have that contact person |
| X-04 | Search ignores upper / lower case | Clients > search box = an existing client name in UPPER CASE | Same client found as with the original case |
| X-05 | Leading and trailing spaces in the search are ignored | Clients > search box = an existing client name with 3 spaces before and after | Same client found as without the spaces |
| X-06 | A search with no match shows a 'nothing found' message | Clients > search box = 'zzz-no-such-client-<number>' | No clients should be listed and a readable message should say so (not the full list, not an empty table) |
| X-07 | Search text '<script>alert(1)</script>' is handled safely | Clients > search box = '<script>alert(1)</script>' | The search text should be treated as plain text: nothing runs, no server error appears, the list shows no match or only matching rows, and the page keeps working |
| X-08 | Search text '%' is handled safely | Clients > search box = '%' | The search text should be treated as plain text: nothing runs, no server error appears, the list shows no match or only matching rows, and the page keeps working |
| X-09 | Search text ''' is handled safely | Clients > search box = ''' | The search text should be treated as plain text: nothing runs, no server error appears, the list shows no match or only matching rows, and the page keeps working |
| X-10 | Search text '"; DROP TABLE clients;--' is handled safely | Clients > search box = '"; DROP TABLE clients;--' | The search text should be treated as plain text: nothing runs, no server error appears, the list shows no match or only matching rows, and the page keeps working |
| X-11 | Search text '\' is handled safely | Clients > search box = '\' | The search text should be treated as plain text: nothing runs, no server error appears, the list shows no match or only matching rows, and the page keeps working |
| X-12 | Search text 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA... (300 chars)' is handled safely | Clients > search box = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA... (300 chars)' | The search text should be treated as plain text: nothing runs, no server error appears, the list shows no match or only matching rows, and the page keeps working |
| X-13 | Clearing the search restores the full list | Search for a client, then clear the search box | The same clients and the same total as before the search should be shown again |

#### Clients list: filters

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| X-20 | Country filter = India lists exactly the India clients | Clients > Country > tick 'India' | Every listed client should be in India, and the total in 'Showing ... of N' should equal the number of India clients in the full list |
| X-21 | Country filter = Brazil lists exactly the Brazil clients (or a 'no clients' message) | Clients > Country > tick 'Brazil' | Every listed client should be in Brazil and the total in 'Showing ... of N' should equal their number; with none, the message 'No clients found' should be shown |
| X-22 | Country filter = United States lists exactly the US clients (or a 'no clients' message) | Clients > Country > tick 'United States' | Every listed client should be in the United States and the total in 'Showing ... of N' should equal their number; with none, the message 'No clients found' should be shown |
| X-23 | Client Type filter = AOR lists exactly the AOR clients | Clients > Client Type > tick 'AOR' | Every listed client should be an AOR client, and the total in 'Showing ... of N' should equal the number of AOR clients in the full list |
| X-24 | Client Type filter = EOR lists exactly the EOR clients | Clients > Client Type > tick 'EOR' | Every listed client should be an EOR client, and the total in 'Showing ... of N' should equal the number of EOR clients in the full list |
| X-25 | Country filter accepts several countries (India + Brazil) | Clients > Country > tick 'India' and 'Brazil' | Clients from either country should be listed, and the total in 'Showing ... of N' should equal the number of India + Brazil clients |
| X-26 | Country and Client Type filters combine (India AND AOR) | Clients > Country 'India' + Client Type 'AOR' | Only clients that are in India AND are AOR clients should be listed, and the total should equal their number |
| X-27 | The search field inside the Country filter narrows its options | Clients > Country > type 'bra', then 'brazzz' | Typing 'bra' should offer only Brazil; a text without a match should show 'No results.' |
| X-28 | A filter and the search box combine (AND) | Client Type 'AOR' + search the full name of an existing client (the client is EOR), then Client Type 'EOR' + the same search | With the other client type no clients should be listed and a message should say so; with its own client type the client should be listed |
| X-29 | 'Clear all' resets the Country and Client Type filters | Clients > Country 'India' + Client Type 'AOR', then click 'Clear all' | Both filters should read 'Any' again and the full list of clients should be shown |
| X-30 | Status tile 'Active' filters the list by status | Clients > click the 'Active' tile | Only clients with that status should be listed, the total in 'Showing ... of N' should equal the number of such clients, and with none the message 'No clients found' should be shown |
| X-31 | Status tile 'In-Progress' filters the list by status | Clients > click the 'In-Progress' tile | Only clients with that status should be listed, the total in 'Showing ... of N' should equal the number of such clients, and with none the message 'No clients found' should be shown |
| X-32 | Status tile 'Inactive' filters the list by status | Clients > click the 'Inactive' tile | Only clients with that status should be listed, the total in 'Showing ... of N' should equal the number of such clients, and with none the message 'No clients found' should be shown |
| X-33 | Status tile 'Total' filters the list by status | Clients > click the 'Total' tile | Only clients with that status should be listed, the total in 'Showing ... of N' should equal the number of such clients, and with none the message 'No clients found' should be shown |
| X-34 | A filter stays applied on page 2 | Clients > Country 'India', then page 2 | Page 2 should show only India clients and the text 'Showing 11-20 of N' |
| X-35 | Applying a filter while on page 2 returns to page 1 | Clients > page 2, then Client Type 'AOR' (few clients) | The first page of the AOR clients should be shown ('Showing 1-...'), not an empty page 2 |

#### Clients list: pagination, sort, columns

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| X-40 | Page 2 shows different clients than page 1 | Clients > click page '2' of the pagination | Different clients should be listed, and the text should change to 'Showing 11-20 of N' |
| X-41 | Page size 5 shows 5 clients per page | Clients > page size '5 / page' | min(5, N) rows and 'Showing 1-5 of N clients' |
| X-42 | Page size 10 shows 10 clients per page | Clients > page size '10 / page' | min(10, N) rows and 'Showing 1-10 of N clients' |
| X-43 | Page size 20 shows 20 clients per page | Clients > page size '20 / page' | min(20, N) rows and 'Showing 1-20 of N clients' |
| X-44 | Page size 30 shows 30 clients per page | Clients > page size '30 / page' | min(30, N) rows and 'Showing 1-30 of N clients' |
| X-45 | Page size 40 shows 40 clients per page | Clients > page size '40 / page' | min(40, N) rows and 'Showing 1-40 of N clients' |
| X-46 | Page size 50 shows 50 clients per page | Clients > page size '50 / page' | min(50, N) rows and 'Showing 1-50 of N clients' |
| X-47 | Previous / Next buttons are enabled only where there is a page to go to | Clients (10 per page): check Previous on page 1, click Next to the last page, check Next, click Previous | Previous should be disabled on page 1, Next should be disabled on the last page, and Previous should go back one page |
| X-48 | Summary tiles (Total / Active / Inactive / In-Progress) match the clients in the list | Clients > read the four tiles and compare them with all clients (50 per page) | The Total tile should equal the number of clients, each other tile should equal the number of clients with that status, and the tiles should add up to Total |
| X-50 | Sorting by 'Company': ascending, descending, then back to the default order | Clients > click the 'Company' header three times (10 per page) | The 1st click sorts A to Z, the 2nd click sorts Z to A, the 3rd click brings back the original order |
| X-51 | Sorting by 'POC Name': ascending, descending, then back to the default order | Clients > click the 'POC Name' header three times (10 per page) | The 1st click sorts A to Z, the 2nd click sorts Z to A, the 3rd click brings back the original order |
| X-52 | Sorting by 'Client Type': ascending, descending, then back to the default order | Clients > click the 'Client Type' header three times (10 per page) | The 1st click sorts A to Z, the 2nd click sorts Z to A, the 3rd click brings back the original order |
| X-53 | Sorting by 'Country': ascending, descending, then back to the default order | Clients > click the 'Country' header three times (10 per page) | The 1st click sorts A to Z, the 2nd click sorts Z to A, the 3rd click brings back the original order |
| X-54 | Sorting by 'Status': ascending, descending, then back to the default order | Clients > click the 'Status' header three times (10 per page) | The 1st click sorts A to Z, the 2nd click sorts Z to A, the 3rd click brings back the original order |
| X-55 | Sorting by Company orders ALL clients, not only the 10 rows of the current page | Clients (10 per page) > click the 'Company' header once and compare page 1 with the first 10 of ALL companies sorted | Page 1 should show the 10 companies that come first alphabetically in the whole list |
| X-60 | Columns menu: hide and show the 'Company' column | Clients > Columns > untick 'Company', then tick it again | The column heading and its cells should disappear and then come back, and the other columns should stay as they are |
| X-61 | Columns menu: hide and show the 'POC Name' column | Clients > Columns > untick 'POC Name', then tick it again | The column heading and its cells should disappear and then come back, and the other columns should stay as they are |
| X-62 | Columns menu: hide and show the 'Client Type' column | Clients > Columns > untick 'Client Type', then tick it again | The column heading and its cells should disappear and then come back, and the other columns should stay as they are |
| X-63 | Columns menu: hide and show the 'Country' column | Clients > Columns > untick 'Country', then tick it again | The column heading and its cells should disappear and then come back, and the other columns should stay as they are |
| X-64 | Columns menu: hide and show the 'Mobile Number' column | Clients > Columns > untick 'Mobile Number', then tick it again | The column heading and its cells should disappear and then come back, and the other columns should stay as they are |
| X-65 | Columns menu: hide and show the 'Status' column | Clients > Columns > untick 'Status', then tick it again | The column heading and its cells should disappear and then come back, and the other columns should stay as they are |

#### MSA search and API health

| ID | What it tests | Input / data | Expected |
|---|---|---|---|
| X-70 | MSA & Contract search by client name filters the list | MSA & Contract > MSA, search box = the name of an ACTIVE client | Only that client's row(s) are listed |
| X-71 | MSA & Contract search with no match shows no rows and a message | MSA & Contract > MSA, search box = 'zzz-no-such-client-<number>' | No rows should be listed and a readable message should say so |
| X-72 | MSA search text '<script>alert(1)</script>' is handled safely | MSA & Contract > MSA, search box = '<script>alert(1)</script>' | The search text should be treated as plain text: nothing runs, no server error appears, and the list shows no match or only matching rows |
| X-80 | Browsing the admin console causes no server errors | Signed in as admin: Dashboard, Clients, MSA & Contract > MSA, Candidates, Appraisal (read-only) | None of the page's requests to the server should fail or end in a server error |
| X-81 | Browsing the client portal causes no server errors (including the notifications) | Signed in as the edge client (masked): portal home, then the wizard via Continue (read-only), then back to home | None of the page's requests to the server should fail, and the request for the notifications should succeed |

## 6. Application bugs found so far

- **Register Client:** over-long e-mail / legal name -> HTTP 500 and raw database text (R-14, R-19); a whitespace-only contact name is only refused by the server, with the technical
  field name and no inline message (R-20); the duplicate legal-name check is case- and whitespace-sensitive (UPPER CASE + trailing space registers a duplicate: seen when the since removed case R-31 was run on purpose, no longer re-checked);
  a double click on Register shows a raw duplicate-key error that includes the e-mail (R-60).
- **First login:** a 300-character password is accepted (P-23).
- **Organization Details:** years in industry accepts -5 / abc / 1000 (O-02..O-04); pincode format is not validated (O-10..O-13); CIN and GSTIN format / length are not validated
  (O-21, O-22, O-24, O-25); an invalid website is refused only with a generic toast that does not name the field (O-06, O-07).
- **Documents:** any file type, empty or fake files, a 21 MB file and a 180-character file name are accepted (D-01..D-08); (the two document boxes have no asterisk = optional, so Continue without documents is correct: case D-20 was removed);
  the document slots are empty after Back from step 2 (B-37); **the admin client profile shows both uploaded documents as "Not uploaded"** (TC-E2E-04).
- **Access / session:** `/candidates` stays on a blank page for a client (B-15); (the login form shown again to a signed-in client or admin, B-19 / B-23, is only a note: the sign-in is still valid); the admin deep link `/clients` lands on the dashboard (B-22).
- **Clients list:** the column sort only re-orders the 10 rows of the current page (X-55); the status tiles do not add up to Total because "Pending" has no tile (X-48, to be confirmed).
- **API (seen in earlier executions, not re-runnable without a new client):** `POST msa-contract/api/status/` answers 404 while the MSA is generated (3 of 3 runs, the journey still completes);
  `GET notifications/push_api/user-notification/` answered 500 once (intermittent).
- **Projects (section 10; found 2026-11-08, scenario ids of `ProjectCreationEdgeCases`):**
  - **Duplicates:** a project with the same name for the same client is accepted, also in UPPER CASE or with a space at the end; a code that differs only in case is accepted (the code check is case-sensitive);
    the same AOR reference id is accepted twice; a digits-only city (12345) and a city that is not in its state (Kerala + Mumbai) are accepted for EOR (AOR-P05, EOR-P05; to be confirmed with product: the
    duplicate-name rule may be intended). An exact duplicate code IS refused with a clear message (AOR: "aor project with this project code already exists", EOR: "Same client cannot have same project code ...").
  - **Double click:** on EOR a double click on Create Project created TWO identical projects (same name, code and client, EOR-P01); on AOR it creates one and shows the error toast "project code ... already exists"
    next to "Project created.".
  - **Empty start date (AOR):** the message is only "Invalid input" (AOR-V01, AOR-V06); EOR says "Project start date is required".
  - **End date in the past:** EOR refuses it ("Project end date cannot be before today."), AOR accepts it and lists the project as **Active**, never Expired (AOR-P04 / AOR-Z01) - inconsistent, and the Expired tab
    can then not be filled by creating projects.
  - **Search:** spaces before / after the search text are not ignored: 0 projects found where 3 match (Q04, AOR and EOR; the Clients list has the same expectation in X-05).
  - **Listing sort:** the column sort only re-orders the rows of the current page (G07, AOR and EOR; same bug as X-55 of the Clients list) and is case-sensitive (capital letters before lower case, observation in G06).
  - **Messages:** over-long values are refused by the server with a toast that names the technical field ("Ref id format: Ensure this field has no more than 50 characters."), not by an inline message.
  - No script ran from any typed text (HTML / script / SQL / Unicode / emoji in name, code, reference id, end client, city were shown as plain text, also in the search), no HTTP 5xx and no raw database text was
    seen in the project module; the wildcard characters `%` and `_` in the search are not wildcards.

## 7. Code layout

- `pages/` page objects (locators verified against the live sandbox), `flow/ClientOnboardingFlow` one method per journey phase, `base/` (`BaseTest` lifecycle + helpers, `BasePage`, `DriverFactory`),
  `reporting/` (`TestReporter.phase()` runs a phase + screenshot + Extent node; `defect()` = soft failure; `finish(status, throwable)` lets a set-up case write its own row; a SkipException inside a phase
  is recorded as SKIP; `ExecutionInfo` = the dated run folder; `ExtentManager`), `api/NetworkCapture` (method / path / status only), `utils/` (`ConfigManager`, `SecretMasker`, `ToastWatcher`,
  `TestDataGenerator`, `FormFeedback`, `PageProbe`, `PlainText` = the wording helpers and the plain-language safety net of every report), `model/ClientData` (public mutable fields).
- `BaseTest`: one browser per class (a window closed by hand is replaced automatically), teardown always runs, the temporary Chrome profile is deleted, `mustReject` verdict rules
  (rejected with a relevant message = PASS; accepted / silent / misleading / raw database text = FAIL), `-Dcases` filter.
- `ClientOnboardingEdgeCases`: sections in run order. `ensureMailRead / ensureDialog / ensurePassword / ensureOrgStep / ensurePocStep / ensureReviewStep` = set-up on demand (own report cases
  P-00, P-02, P-30, O-00, C-00 the first time) and silent healing afterwards; `prepare(...)` writes a SKIP / FAIL row when a case cannot be prepared; `runCase(...)` / `mustReject(...)` start the report case.
- `ClientOnboardingE2EHappyFlow`: TC-E2E-01 runs the 17 phases through `ClientOnboardingFlow`; TC-E2E-02..08 compare the pages (`pages/EorMsaPage`, `ClientsPage`, `ClientProfilePage`,
  `ClientPortalHomePage`) with the saved data. A page's values are collected in a `reporting/ValueChecks` table through `TestReporter.phaseChecks(...)` (verdict from the table; `guard(...)`
  turns a tab that cannot be read into ONE red row so the tabs after it still run).
- Secrets: `config/secrets.local.properties` or `KOMP_*` env vars. `tools/recon/` is a dev helper that attaches to a Chrome started with `--remote-debugging-port=9222`. Dev probes belong in the scratchpad, not in the repo.
- Project module: `pages/ProjectsPage` (sidebar + listings: rows read in one script call, every action waits until the table changed and agrees with "Showing a-b of N"), `pages/AddProjectPage` (both forms, date input, Radix
  selects, double click), `flow/ProjectCreationFlow` (happy-flow phases with the step ids of the brief), `model/ProjectData`, `utils/ProjectDataGenerator` (names, codes, reference id, dates, states with real cities, seed),
  `utils/ProjectBudget` (the project limit), `BaseTest.module()` (module tag from the package), `TestReporter.start(..., headerLine)` (case header without a client / password).

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
- A case that registers an extra client when the app accepts its input does not belong in the suite: R-31 and R-50..R-52 were removed for exactly this reason (they were only ever skipped in a full run).
- A list that "stopped changing for 1.5 s" is NOT necessarily settled: a slow search leaves the OLD rows in place for a while (X-28 and X-71 failed once because of it). After typing a search term, wait until
  the list is empty or shows the term (`ClientsPage.matchesSearchBox`, `msaRows()`), never only until it is stable.
- **Evidence must show the RESULT of a search, not the screen before it.** "The client's row is visible" proves nothing: the row is in the unfiltered list too, so TC-E2E-03's screenshot once showed the
  full list ("Showing 1-10 of 41") with the search text already typed. `ClientsPage.findClient` and `EorMsaPage.searchClient` now wait until EVERY data row contains the searched name (unchanged for 1 s,
  max 12 s) and the TC-E2E-02 / 03 tables carry a row "Search result is on the screen" (the list footer, e.g. "Showing 1-1 of 1 clients"). Any new search step needs the same wait before values are read or a screenshot is taken.
- The set-up step that opens the login page (`LoginPage.freshLogin`) reloads once when the form does not appear within 20 s (the sandbox was seen loading it very slowly once, B-01).
- The Clients / MSA table sizes itself from the window height (`--table-max-h`, 300 ms animation): anything that resizes the window (the full-page screenshot) must wait for the layout to settle afterwards.
- A table row with fewer than 6 cells is the empty-state / loading row, not a client: skip it before reading cells (`ClientsPage.firstYopmailClient` threw `Index 1 out of bounds` on it and failed R-30 / R-32 / R-33
  when the list was empty at that moment).
- When a run shows dozens of unrelated failures at the same minute, read the first error in `logs/execution.log` before anything else: `net::ERR_...` = the machine / network, not the application.
- After a page-changing step never trust "the first poll": wait for a condition, and treat a one-off timeout as a possible slow page (reload once) before calling it a defect. Re-run the failing case alone
  (`-Dcases=<ID>`) to tell a flaky wait from an application bug; X-28, X-71 and B-01 were flaky waits, not findings.
- The VS Code Java language server can show stale or invented errors (its copy of a file drifts after external edits). The truth is `mvn -q -DskipTests test-compile` (silent = clean).
  To clear the red marks: close and reopen the file, or run "Java: Clean Java Language Server Workspace" / "Developer: Reload Window". Do not "fix" code that Maven compiles cleanly.
- **Mandatory = a field with an asterisk (*)** (user, 2026-11-08). A field without an asterisk may be left empty and the form may be continued (e.g. the CIN / GST document boxes); never write a case that expects a refusal for it. Check the asterisks on the live form before assuming a rule. A case whose app behaviour is only a design preference is a NOTE (passes), not a FAIL.
- **A case's picture must show its evidence**: when the app accepts a bad value it moves to the next page and the step picture shows that next page, so cases with typed values take an extra picture of the form BEFORE Continue (`TestReporter.evidence(label, caption)`). Check that no two failed cases share the same picture.
- Report wording (see the standing rule in section 2): a message of the application goes into a report in double quotes; a sentence about a form field uses the field's plain name ("Years in industry", not `years_in_industry`:
  `fieldLabel` / `contactFieldLabel` in the edge class); a comparison reads "should be \"X\" but the page shows \"Y\"" (`ValueChecks`); a check that finds nothing wrong still says what was seen ("10 clients are listed ...").
  `PlainText.simplify` must never touch case ids (it once turned "TC-E2E-02" into "TC-end-to-end-02": ids stay out of its term list) and never touches text inside double quotes.
  Two project test classes (`tests/project/*.java`) use Windows line endings (CRLF), every other file LF: a script that edits them must keep the line endings (read / write bytes).

## 10. Project Management module (`tests.project`) - projects of an EXISTING client, AOR + EOR

Own folder `src/test/java/tests/project/` (package `tests.project`), own XML suites, same `BaseTest` (the module tag of the report comes from the package: `Project-Management`).
It creates **no client**; the client is picked from the Client Name dropdown.
**PROJECT LIMIT (user's rule, 2026-11-08: "too many projects"): the happy flow creates 1 AOR + 1 EOR project per run, the edge cases at most 5 AOR + 5 EOR per run.**
`utils/ProjectBudget` enforces it: every click on Create Project asks `reserve()` first (limit reached = the click does not happen, the step is SKIPPED with the reason) and every accepted
project is booked with `created()` - also one that the application wrongly accepted. Projects cannot be deleted by automation (the earliest exploratory edge runs left ~70 "Edge AOR ..." projects on the sandbox).
**LOCATION IS ALWAYS INDIA (user's rule): never select the United States or Brazil**, not even in an edge case; a scenario that needs another country is not in the classes. Reports: `reports/ProjectCreationHappyFlow/<run>/` and `reports/ProjectCreationEdgeCases/<run>/` (same layout as section 4,
plus `project-data-<AOR|EOR>.json` / `created-projects-<AOR|EOR>.txt`).

| XML suite (repo root) | Test class | Cases |
|---|---|---|
| `testng-project-happy-flow.xml` | `ProjectCreationHappyFlow` | **2 separate cases** (no random AOR/EOR pick, each type is always tested): TC-PRJ-01 = AOR, TC-PRJ-02 = EOR. ~1 min, creates 1 AOR + 1 EOR project |
| `testng-project-edge-cases.xml` | `ProjectCreationEdgeCases` | **2 cases, each holding ALL scenarios of its type as report steps** (user's rule: "all possible TCs in 1 TC, one for AOR and one for EOR"): TC-PRJ-03 = AOR (63 steps), TC-PRJ-04 = EOR (62 steps); at most 5 projects each |

```bash
mvn clean test -Dsuite=testng-project-happy-flow.xml                          # TC-PRJ-01 + TC-PRJ-02
mvn clean test -Dsuite=testng-project-happy-flow.xml -Dcases=TC-PRJ-02        # only EOR
mvn clean test -Dsuite=testng-project-edge-cases.xml                          # TC-PRJ-03 + TC-PRJ-04 (~15 min each)
mvn clean test -Dsuite=testng-project-edge-cases.xml -Dcases=TC-PRJ-03        # all AOR scenarios
mvn clean test -Dsuite=testng-project-edge-cases.xml -Dcases=AOR-V03,EOR-D01  # only these scenarios (S00 and the baseline P01 always run)
# re-check the listing scenarios WITHOUT creating anything (P01 = an existing project):
mvn clean test -Dsuite=testng-project-edge-cases.xml -Dcases=AOR-Q01,AOR-K04 -Dproject.base.name="Edge AOR 081430 01" -Dproject.base.code=EA08143001 -Dproject.base.client="AOR Test Client"
-Dproject.client="Bleach"   # use this client (must be in the dropdown)      -Dproject.seed=<n>   # repeat the random choices (client, date, state, city, end client)
```

**Happy flow (the brief):** log in as the EOR user (`ConfigManager.eorEmail()/eorPassword()`; falls back to the admin login, add `sandbox.eor.email/password` to `config/secrets.local.properties` for a
dedicated user) -> sidebar Projects -> AOR or EOR -> Add Project -> client from the dropdown -> project name `<client> - <topic>` (the first of 12 topics not in the listing yet, so a re-run never
repeats a name) -> code = first letter of each word of the name, no number (`Bleach - Contract Management` = BCM) -> AOR: Reference ID `<prefix>-IND-<4 digits>`, no end client, no city; EOR: End Client
Name (a real company), no reference id; start date random before 1 Jan 2025, end date empty, India + random state (EOR: + a real city of that state, for the project AND for the end client) ->
Create Project -> back in the listing search the EXACT project name -> the row must show the name, code, client, start date and the status **Active**. Step ids in the report: 1, 2-3, 4, 5-7,
AOR-1..9 / EOR-1..13. Screenshots: login, listing, form, client + name + code, completed form, creation confirmation, final search result. Page-level checks of the brief's "important rules"
are part of step 4 (AOR has no End Client Name / City, EOR has no Reference ID, EOR has two locations).

**Edge cases (at most 5 projects per type):** one scenario = one report step (phase) with its own screenshot; a scenario never stops the case (a finding is a red DEFECT of that step, the case
FAILS at the end). Scenario ids `<AOR|EOR>-<letter><nn>`, in run order:
- **S00** sign in, read the Client Name dropdown. **P01 = project 1 of 5**: the baseline (valid, start date today, AOR: India without a state), Create Project DOUBLE-clicked -> exactly one row.
- **Refused input creates nothing**, so each is its own step (valid baseline data with ONE thing changed; must be refused with a message that names the problem, no raw database text, no HTTP 5xx):
  V01 all empty (a message per required field), V02 client, V03 name, V04 code, V05 reference id (AOR), V06 start date, V07 / V08 name / code = spaces; F01 name 201 characters, F02 code 51, F03 reference id 51
  (AOR); T01 end date before start, T02 end = start, T03 (EOR only: EOR refuses an end date before today, AOR accepts it - see P04); D01 the code of P01.
- **The accepted variants are folded into 4 more planned projects** (each checks several things; Z01 checks them in the listing):
  P02 maximum lengths + special characters (name 200 with HTML / script / Unicode / emoji / quotes / % _ & # \, code 50 with spaces and symbols, AOR reference id 50 with an `<img onerror>` tag, EOR script in
  the end client and the city; start 29 Feb 2096, end 31 Dec 2099; India); P03 minimum values ('  Z  ', a free 1-character lower-case code, AOR reference id 'r', start 1 Jan 1900, India without a state, EOR no end
  client); P04 the name of P01 for ANOTHER client, start + end in the past (must NOT be Active), India / Tamil Nadu; **P05** duplicates of P01 that must be REFUSED (name UPPER CASE + space, code lower case,
  AOR the same reference id; EOR a digits-only city and a city that is not in its state) - it only becomes project 5 when the application wrongly accepts it.
- **Nothing is submitted:** V10 asterisks, L01 State disabled until a country, L02 India offered and selectable, L03 every Indian state selectable, B01 Cancel, B02 F5, B03 browser Back, B04 reopened form empty.
- **Z** what the listing really holds (Z01 every created project exactly as entered + status; Z02 refused / cancelled attempts created nothing) | **Q** search (Q01 name, Q02 code, Q03 UPPER CASE, Q04 spaces
  around, Q05 partial, Q06 no match, Q07-Q13 script / % ' SQL \ _ 300 characters, Q14 clear, Q15 search typed on page 2) | **K** tabs and client filter (K01 Active, K02 Expired / Inactive, K03 known statuses, K04
  client, K05 Clear all, K06 filter AND search, K07 tab chosen on page 2 -> page 1) | **G** table (G01-G04 page size 5 / 10 / 20 / 50, G05 Previous / Next, G06 sort asc / desc, G07 sort across pages, G08
  "Showing" text) | **U** access without being signed in (U01 listing, U02 /add, U03 Back after sign out; last, it ends the session).
- **Not in the class (not possible within 5 projects / India only):** a blank reference id on its own (it was ACCEPTED in the exploratory run), over-long end client names / cities, every accepted variant on
  its own, changing the country (the state reset), projects outside India.
Expected search / tab / filter results are computed from the full list (read once, all pages), so a `%` that works as a wildcard, a page that keeps its offset or a filter that is ignored shows up as a wrong result.

**Rules learned in this module**
- Screenshot / step slugs must be UNIQUE per case: both cases write into one run folder, and equal slugs made TC-PRJ-02 overwrite the pictures of TC-PRJ-01 (found on the first live run). They are prefixed with the case id.
- Reports of this module never print a password (the brief: "never expose passwords"): `TestReporter.start(..., headerLine)` replaces the client header line, `ExtentManager.addLogins` shows the EOR e-mail only. (The client module keeps showing logins by the user's own decision, section 2.)
- Date inputs: type the digits in the browser's own order (`Intl.DateTimeFormat` parts), verify the ISO `.value`, fall back to the native setter + `input` event. ChromeDriver cannot type emoji (non-BMP): `AddProjectPage.setText` falls back to the native setter.
- Navigate through the sidebar (`ProjectsPage.openListing`); a click on the link of the listing that is already open does NOT rebuild it (filters, search text, sort, page size and the PAGE
  INDEX stay). A listing scenario starts from `freshListing()` (open Add Project, Cancel = the listing is rebuilt: page 1, no filter / sort).
- The listing keeps its old rows while the next page / tab / filter is fetched, and a page-size change keeps the page offset ("51-60" after 50 -> 10): `ProjectsPage` waits until the table CHANGED and
  agrees with its "Showing a-b of N" text, and `allRows()` starts on page 1 itself (two runs read "all projects" from page 2 and reported projects as missing).
- Do not trust "mvn ... | tail; echo $?" - that is the exit code of tail: a broken compile looked clean once and the next run died on it. Redirect to a file and print Maven's own exit code.
- Sorting is only compared on names made of plain letters / digits / spaces (the collation of symbols is the application's business).

**Live facts (sandbox, verified 2026-11-08):** sidebar `Projects` expands to `/projects/aor` and `/projects/eor` (heading "AOR Projects" / "EOR Projects"). AOR clients in the dropdown: "AOR Test Client", "Bleach"; EOR: four or more
EOR clients. Listing: tabs All / Active / Expired (AOR) or Inactive (EOR), search "Search by project name or code", Client filter (searchable popover, `Clear all`), table AOR = project name, code,
client, start date, status; EOR = project name, code, client, work location (shows the country), start date, status; dates print dd-mm-yyyy; empty state "No projects found / Try adjusting your filters, or
add your first project."; page size is a native select; headers sort (ascending, descending). Add Project (AOR): Client Name (Radix select), Project Name, Project Code, Reference ID Format (placeholder
HDFC-IND-000000, 4 digits are accepted), Project Start Date (*), Project End Date, Country, State; (EOR): Project Name, Project Code, Client Name, End Client Name (optional), dates, Project Location and
End Client Location each with Country, State and a free-text City. Success = toast "Project created." and the listing opens. Inline errors: "Client is required", "Project name is required", "Project code is
required", "Reference ID format is required"; an empty start date shows only "Invalid input"; "Project end date must be after the start date" (also for end = start). Server limits (toasts "Project name:
Ensure this field has no more than 200 characters." + "Could not create the project."): name 200, code 50, reference id 50. The code is unique per type and case-sensitive ("Project code: aor project with
this project code already exists."); project names and reference ids may repeat (see the findings). Whether a project whose end date is over leaves "Active" is checked by P04 / Z01 (in the first
exploratory run every project, also one that ended on 31 Dec 2022, was listed as Active).

## 11. Employee - AOR onboarding and finance (`tests.employee.aor`) - happy flow only

Folder `src/test/java/tests/employee/aor/`, class `AorOnboardingAndFinance` (the spec name "AOR-OnboardingAndFinanace.java" is not a valid Java name), suite `testng-aor-onboarding-finance.xml`, module tag `Employee-AOR`,
reports `reports/AorOnboardingAndFinance/<run>/` (+ `uploads/` with the filled workbooks, `aor-run-data.json`). **Two cases in order: TC-AOR-01 = employee bulk upload, TC-AOR-02 = AOR Finance for the same employees**
(skipped when TC-AOR-01 did not upload). No edge cases yet (user: "just create happy flow for now").
```bash
mvn clean test -Dsuite=testng-aor-onboarding-finance.xml                       # 5 new employees (-Daor.employees=N, 1..10), ~3 min
mvn clean test -Dsuite=testng-aor-onboarding-finance.xml -Dcases=TC-AOR-02 -Daor.run.data=reports/AorOnboardingAndFinance/<run>/aor-run-data.json [-Daor.session.reuse=true]   # finance again, no new employees
```
- **Login:** `sandbox.aor.email / sandbox.aor.password` in `config/secrets.local.properties` (never in code or reports). Workbooks (only COPIED, originals untouched): `aorBulkUploadExcelPath`, `aorPayeeSheetExcelPath` in
  `config/documents.properties` = `...\Bulk Upload\AOR\AOR - Bulk Upload.xlsx` and `AOR Payroll - Calculation.xlsx` (the path in the spec lacked the `\AOR\` folder). Aadhaar / PAN: only the 20 pairs the user supplied
  (`config/aor-identities.local.txt`, git-ignored, round-robin, masked in reports). Employee / DP ids / names are never reused: `testdata/aor-used-ids.txt` (`AorRunHistory`).
- **Data rules** (all checked in the file that was really written, TC-AOR-01 step 4): client "Bleach", project "The Substitute Shinigami" (sheet "Bleach", other sheets and unused rows are removed from the copy), random state + its capital as hub,
  Hub Name = capital + "-" + Company Employee Id, Name As Per Company = Dp Name = Name, Marge Name = "AOR-" + Name, onboarding before 1 Jan 2025, joining before 1 Jan 2024, age >= 18, e-mail `<NameNoSpaces>.BTSS@yopmail.com`
  (pattern of the owner's example sheet), manager "Karun Yadav", Area Manager e-mail `Karun.Yadav.AOR@yopmail.com`, one Area Manager + Supervisor name per run, prefilled template values kept.
- **Spec vs. example workbooks:** the Payee Sheet column "Wm Name" gets the employee's NAME (the owner's example sheets and the payroll list's AGENT NAME do), not the Company Employee Id of the written spec (`-Daor.payee.wmname=employeeId`
  switches). Final Payout is a number. Run date = today, used for every date (pay period start = end = run date).
- **Live facts (sandbox 2026-11-10):** Employees > AOR (`/aor-employees`, "AOR Agents"; the list shows the Delivery Partner Id; search by name / id). Bulk Upload window -> after Upload a SECOND, floating panel
  ("Creating Bulk Employees ... Bulk onboard completed! 2 created, 0 updated") is a plain `div.fixed` under body, not a dialog: `AorDialog` follows it until "completed". Same for the Payee Sheet ("Uploading Payout Sheet ... Records - Total, Created,
  Updated" or "Errors (n) ... FHRID ... is not onboarded for this client/project"). AOR Financials: Payroll (`/financials/aor/payroll`: Upload Pay Sheet, Download Bank Sheet, Download DA Invoices, Generate Invoice, Refresh Report,
  Download Report) and Invoices. Payroll list column "EMPLOYEE ID" = the Delivery Partner Id (FHR id). Session ids `PAYIN-<ddMMyy><ddMMyy>-<nnnn>`. Downloads go to `~/Downloads/AOR` (CDP download behaviour), renamed `<runId>_<purpose>_<name>`;
  a download of an existing name overwrites it, so "new" means new OR changed.
- **Rules learned:** verify what a dropdown SHOWS after choosing (a click in the long project list picked "QA AOR Project"; `AorDialog.choose` retries); never Ctrl+A in a date box (it selects the whole page); after a Payee Sheet upload reload the
  Payroll page before the next windows (they offered only the old sessions); the invoice groups several states under one entry (Gujarat + Kerala + Meghalaya under "Maharashtra"): only the TOTAL is compared, as agreed.
- **Added 2026-11-11 (AOR):** TC-AOR-01 step AOR-08 opens every new employee (Action > View) and checks the header + the five tabs (Basic Details, Personal Info, Agent Bank Details, Identification Information, Area Manager/Supervisor) one after the other.
  TC-AOR-02 now ends with the PAYROLL tab per-employee invoices (View + PDF into Downloads/AOR, no grand total) and the INVOICE tab (Refresh Report, every invoice of the session via Action > View > Download, Download Report with State = "All ...", combined state-wise total vs the Payee Sheet total, difference 0),
  then the DELETE checks (user's explicit permission 2026-11-11, only sessions created by the run, exact session id + employees verified before the click): delete session 1 on the Payroll tab (must also vanish from the Invoice tab), upload a second Payee Sheet + invoice,
  delete session 2 on the Invoice tab (must also vanish from Payroll); other sessions must stay unchanged. Lists are read only after the table settled and compared with the "of N" total.
  Progress box: the list of test cases is collapsed by default (arrow in the box); a JavaScript error in `RunBanner.JS` is swallowed silently (the box just disappears) - test the script in a browser after editing it.
- **AOR rules of 2026-11-11 (user):** (1) the onboarding workbook is filled ONLY in these columns: State, Delivery Partner Id, Company Employee Id, Name, Name As Per Company, Marge Name, Dp Name As Per Company, Contact Number, Father Name,
  Date Of Onboarding, Hub Location, Hub Name, Da Status, Date Of Joining, Date Of Birth, Personal Email Id, Aadhar Number, Pan Number, Account Number, Ifsc Code, Driving Licence Number, Vehicle Insurance - Address, Bank Name, Manager,
  Area Manager and Supervisor keep the TEMPLATE's own values (the Bleach sheet has 30 prefilled rows). (2) Aadhaar / PAN: only the pairs of `config/aor-identities.local.txt` (44 valid pairs; pair 24 left out: Greek look-alike letters in its PAN);
  pairs no employee used yet come first (`AorDataGenerator.pickPairs`, booked as kind AADHAAR in `testdata/aor-used-ids.txt`). (3) LIMITS: happy flow 5 employees per run; edge cases NO LIMIT (user, 2026-11-11: the Aadhaar / PAN pairs supplied are the only supply, unused pairs first, then the list is used again;
  `testdata/aor-edge-employees.txt` only counts; `-Daor.edge.employees.max` can set one); a failed / refused upload creates nobody anyway. REPORTS: a class report lists EVERY scenario and case of the class - one that is not run (filter, missing base) is a SKIP row with the reason. (4) Four files in `C:\Testing\KOMP IND - Automation File\Bulk Upload\AOR` hold the data of the latest run until the next run replaces it:
  `AOR - Bulk Upload.xlsx` + `AOR Payroll - Calculation.xlsx` (happy flow) and the two `...EdgeCases.xlsx` (edge cases). The PRISTINE templates are the copies in `testdata/aor-templates/` (`aorBulkUploadTemplatePath`, `aorPayeeSheetTemplatePath`): only read.
  (5) Edge class `AorEdgeCases` (suite `testng-aor-edge-cases.xml`, TC-AOR-EDGE-01 bulk upload, TC-AOR-EDGE-02 Payee Sheet): see its Javadoc; scenario workbooks are kept in the run folder `uploads/`.
- **Lessons 2026-11-11 (edge workbooks / progress box):** (1) a workbook is written to a `.part` file and only a FINISHED one replaces the target (`AorWorkbooks.writeEmployeeWorkbook/writePayeeWorkbook`): the old R00 copied the template over the retained
  edge workbook first and then failed on an accepted date written as text ("11-Nov-2031"), which left the file empty. (2) The edge workbook keeps rows exactly as they were uploaded (`writeRawRows`, rows of the file + this run's `uploads/`), never rebuilt from objects
  (the old rebuild applied the "changes" of a duplicate-id scenario and put another employee's delivery partner id into a saved row). (3) The progress box must be REGISTERED for new pages before it is drawn: the first update runs on `chrome://new-tab-page`, which refuses scripts,
  and the exception skipped the registration, so the box was missing for the whole first case; `RunBanner.ensure()` also redraws it before every step. (4) Dates of rows beyond the template's prefilled rows get a date format (else they read back as serial numbers).
- **Findings so far:** (1) CONFIRMED 2026-11-11 (user: keep it failing): the Bank Sheet of ONE payroll session also lists the payees of OTHER sessions of the same date (10 rows instead of 5, in TC-AOR-02 step 6 and again with two sessions of the run on the same date); the sheet amounts are the final payout less 1% (to be confirmed with the client); (2) after a Payee Sheet upload the next windows do not offer the new session until the page is reloaded.

## 12. REPORTING STANDARD (user, 2026-11-11: applies to every module and every future suite)

- **One scenario = one report entry** in the left panel (`TestReporter.startScenario` + `finishScenario` / `finishAuto`), named `<parent TC> | <scenario id> | <title>`, in execution order, with its own steps, expected / actual, test data,
  screenshots, application messages, impact line, tags and defect references. Reference implementations: `AorEdgeCases` (71 scenarios) and `ProjectCreationEdgeCases` (`entry(...)`, every `scenario(...)` is an entry).
- **Verdicts** (`reporting/ScenarioBoard`): PASS (green) / FAIL (red = confirmed application defect) / NOT RUN (grey) / BLOCKED (orange, prerequisite or network missing) / NOT JUDGED (grey, the application did not reach the checked
  stage or the test itself could not carry it out = AUTOMATION FAILURE, never counted as an application defect). A skipped case is never PASS.
- **Summaries:** every report starts with the entry "SUMMARY | All test cases of this run" (`BaseTest` creates and fills it; counts, unique defects with their scenarios, per-test-case table, non-passing list); a class with scenario entries
  also gets one summary entry per parent TC. Unique defects: the same defect reference on several scenarios counts once as a defect and every scenario as failed. Known defects get a reference with `ScenarioBoard.defect(...)`;
  without one the same problem sentence gets the same `AUTO-nnn` reference (`ScenarioBoard.autoRef`).
- **Classes that still use `TestReporter.start(...)` cases** (client onboarding, happy flows, journeys) get the standard automatically in `TestReporter.finish`: verdict, kind tag, defect reference, summary rows and the full API record. A new suite must use scenario
  entries from the start. A journey / happy flow stays ONE case with its phases (its phases depend on each other); an independent check must be its own scenario entry.
- **API calls** of every entry are recorded in full (`-Dcapture.api.full=false` switches it off): endpoint, method, URL, headers, payload, status, response body, timestamps, duration -> folded node "API calls observed (n)" + `api-evidence/`.
  Per the user's decision nothing is masked in these records (passwords, tokens, Aadhaar, PAN, bank accounts) except with `-Dmask.secrets=true`; a production run always masks.

## 13. Employee - EOR onboarding (`tests.employee.eor`) - happy flow only

Folder `src/test/java/tests/employee/eor/`, class `EOROnboarding`, suite `testng-eor-onboarding.xml`, reports `reports/EOROnboarding/<run>/` (+ `uploads/` with the workbook, `eor-run-data.json` without passwords).
**Five cases in order, each needs the one before**: TC-EOR-01 bulk upload (admin: Employees > EOR > Bulk Upload, every employee found in Candidates), TC-EOR-02 every employee accepts the Letter of Intent from the
Yopmail mail, reads the "Candidate Login" password and fills the candidate portal, TC-EOR-03 admin verifies (Employees > EOR > View > Verify) and sends the offer letter (Candidates), TC-EOR-04 every employee accepts the
offer, TC-EOR-05 Mark as Joined and the employee is Active in Employees > EOR. An employee whose step failed is left out of the later steps, the others go on.
```bash
mvn clean test -Dsuite=testng-eor-onboarding.xml                    # TWO new employees (user, 2026-11-13: the 1st a Fresher, the 2nd Experienced; -Deor.employees=N, 1..5)
# tuning on ONE employee that was uploaded earlier and has accepted the Letter of Intent (no new employee; the later cases start where the earlier run stopped):
mvn clean test -Dsuite=testng-eor-onboarding.xml "-Deor.existing=<e-mail>|<password>" -Dcases=TC-EOR-02      # or TC-EOR-03,TC-EOR-04,TC-EOR-05
```
- **Data:** the QA owner's template `employeeBulkUploadExcelPath` (config/documents.properties) is only COPIED; its first sheet's example row (client "Dragon Ball Z", project, manager, salary, formulas) is repeated per employee and only
  State, names, Gender, Email, Work Experience, Mobile, Working Location, End Client, Designation, Job Description are replaced (`utils/EorWorkbooks`, `utils/EorDataGenerator`; the first employee is Experienced, the second a Fresher,
  so both paths run). **E-mail addresses and the login are ALL LOWER CASE** (`firstnamelastname.m|f@yopmail.com`, user 2026-11-12); used addresses are kept in `testdata/eor-used-emails.txt`. Login = `ConfigManager.eorEmail()/eorPassword()`.
- **Candidate portal rules (user, 2026-11-12):** saved data stays and can be edited; a section whose key field already holds a value is only "Save & Continue"d (when that button is enabled); the permanent address is either the box
  "Permanent address is the same as current address" ticked (employee 1, 3, 5) or a separate address (employee 2, 4); an experienced employee fills "Work Experience" (previous employer, designation, joining MONTH (`type=month`), last working
  date, key responsibilities, document); every drop-down (Country -> State -> City load slowly) is closed and opened again when the entry does not appear (`chooseAt`, `-Deor.dropdown.attempts`); an already attached document shows a preview instead of a file box (skipped);
  the toast "... saved." can cover "Save & Continue" (toasts are closed, then a JavaScript click as fallback). The steps of the left menu unlock one after the other, so every run walks Personal > Contact > Banking > Professional > Education > Identification.
- **Lessons:** the Candidates list has a column "LOI STATUS" AND "OFFER LETTER": wait on the right column (`columnShows`), "Accepted" in LOI STATUS is not the offer; after "Accept Offer" wait for "You have accepted this offer" (the first click can do nothing while the contract preview loads);
  the panel "Creating Bulk Employees" stays on the page after an upload and covers the sidebar (dismiss it); the Yopmail subject fragments are "Letter of intent" and "Candidate Login".

- **Added 2026-11-13 (after joining):** TC-EOR-06 = Employees > EOR > eye button (title "View"; a candidate before verification has Actions > View instead): "Unmask all" first (user rule), then every tab (Personal & Contact, Professional,
  Statutory Details, Documents, Employment Documents) compared with what was entered (`flow/EorProfileChecks`, ~80 values per employee). TC-EOR-07 = every block of every tab has an Edit button (shown on hover only: found / clicked by script):
  every changeable value is changed (digits count up, " Ed" suffix toggled, another list entry; NOT gender, nationality, e-mail, work experience, files, marital status; **the country is always India**, a country that is not India is set back to India),
  Submit, and the profile must show the new value (it is read for up to 8 s, then after a reload: "the old values stayed on the page until it was loaded again" is a recorded finding, seen in a different block on most runs).
  TC-EOR-08 = Employees > EOR search shows the changed values, MSA & Contract > Contract (`/contracts/eor`): list row, contract page, Salary Breakup after "Unmask all" (compared with the workbook row), Edit Contract step 1, "Save & Continue", step 2
  (step 3 Letter of Intent is NOT opened, nothing is saved on step 2). `-Deor.run.data=<eor-run-data.json>` runs TC-EOR-06..08 on the employees of an earlier run (TC-EOR-08 needs the data saved by TC-EOR-07: names / designation / joining date).
  **Rate limit (user, 2026-11-12):** "Too many sign-in attempts" is not waited out: the browser is closed and a new one opened (`RateLimitedLogin`, `BaseTest.restartBrowser`, `EorOnboardingFlow.withRestart`).
  **Yopmail** can put a Cloudflare window "Please confirm you're human" over the inbox: `EorMailbox` waits up to `-Dyopmail.human.wait.seconds` (300) for it to clear or to be clicked by the person watching; it is not bypassed.
  **Not built yet:** TC-EOR-09 (Yopmail "Welcome Aboard - Your Employee Login Details" mail, employee login, check of the profile, upload of the dummy documents - the maternity form only for a female employee) and TC-EOR-10
  (admin: Employees > EOR > View > Documents shows the uploaded documents): the mailbox was blocked by the human check when this was started.
- **Accounts (user, 2026-11-13):** EOR employee onboarding and everything done / checked for EOR runs with the EOR user (`sandbox.eor.email / password` in config/secrets.local.properties = karun.yadav.eor@yopmail.com), AOR with the AOR user
  (`sandbox.aor.*`), **client and project tests with the administrator** (`ConfigManager.adminEmail()/adminPassword()`; the client / project flows no longer use `eorEmail()`). The report header shows who signed in (`ExtentManager.addLogins`).
  Seen with the EOR user: the contract page has NO "Salary Breakup" part (the administrator's has it) - recorded as a finding by `verifyContract`.
- **Mask and unmask (user, 2026-11-13: test it wherever it appears, EOR and AOR):** EOR profile (`EorOnboardingFlow.checkMasking`, first part of every profile check): phone numbers, account numbers, Aadhaar, PAN are hidden at first (X characters), "Unmask all" shows them in
  full (same length, the visible digits equal), "Mask all" hides them again, the eye button "Show Account No" shows only that value; the contract's Salary Breakup is hidden until its own "Unmask all" and hidden again by "Mask all". The AOR agent profile
  has NO mask controls (verified 2026-11-13: values are shown in clear), so there is nothing to test there; if a mask control appears in AOR, add the same cycle.
- **Yopmail human check:** it is NOT bypassed (a side-channel read of Yopmail was refused by the tool permissions as bot-check circumvention): `EorMailbox` waits for the check to clear or to be clicked by the person watching. TC-EOR-02 and the not yet built TC-EOR-09 need the mailbox.
- **Smooth scrolling (user feedback 2026-11-13: "you don't scroll while testing, the user can't see what is going on"):** `utils/SmoothScroll`: every click / type of `BasePage` and every former `scrollIntoView` call glides to the element (centre of the window,
  waits until the page stands still, skipped when the element is already well inside the window); `SmoothScroll.tour(driver)` glides to the bottom and back to the top of a long page: used on every profile tab and the contract pages.
  Write new page objects with `SmoothScroll.to / click`, never a bare `scrollIntoView`. `-Dscroll.smooth=false` = instant jumps, `-Dscroll.tour=false` = no tours (faster runs).
- **Complete negative / edge set of the AOR edge class (user 2026-11-13: "complete negative and edge testing including strings and integers"; written, compiled, NOT yet run):** TC-AOR-EDGE-01 gained C1-C4 (client / project name: empty, spaces, unknown, other client,
  case, symbols, script, SQL, very long; sheet named after an unknown client), T1-T16 (digits columns with 19 kinds of numbers / strings: negative, decimal, exponent, hex, thousands separator, Arabic / Devanagari / full-width digits, booleans, formulas, NaN / null;
  text columns with numbers, formulas, dates, markup, template and path injections, 256 - 32000 characters, tab / line break / zero-width / right-to-left; dates, e-mail, IFSC, PAN, Aadhaar, account number), D1-D4 + D10-D15 (duplicate Aadhaar / PAN / driving licence
  with an onboarded employee and inside one file), B1-B8 (age and date boundaries; exactly 18 and a leap day must be accepted), V03-V06 (valid edge values), HM-<column> (every required column missing, one scenario each), H1-H2 (duplicate heading, no heading row).
  TC-AOR-EDGE-02 gained Q20-Q5x (FHRID, final payout, Wm name, state, hub with the same kinds of values) and P06-P09 (pay period without start / end, in 1900, three years). Every value in an anchored batch must be REFUSED: a value that is legally valid
  belongs in a `single(..., true)` scenario. New defect reference AOR-D11 (client / project not checked). Not yet covered: search / filters / paging / sort / columns of the AOR Agents, Payroll and Invoice lists (needs a recon of those screens first),
  and the same Payee Sheet uploaded twice.
