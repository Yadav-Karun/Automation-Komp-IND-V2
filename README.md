# KOMP IND Testing

Selenium 4 + TestNG + Maven + Extent Reports automation for **KOMP Global client onboarding**.
The whole client-onboarding module is covered by **two test classes and two XML suites - nothing else** (the project module below has its own two):

| XML suite | Test class (`src/test/java/tests/client/`) | What it does | Clients created |
|---|---|---|---|
| `testng-client-happy-flow.xml` | `ClientOnboardingE2EHappyFlow` | Register -> ... -> **Active** (17 phases), then opens the client profile (portal *and* admin) and matches every visible value with what was entered | **1** |
| `testng-client-edge-cases.xml` | `ClientOnboardingEdgeCases` | **Every** negative / edge case: register form, login, first-login password, organization details, documents, contacts, wizard (refresh / Back / re-enter different values), access and session, Clients list search / filters / pagination / sort / columns, MSA search, API health | **1** |

**Project Management module** (projects of an existing client; its own folder `src/test/java/tests/project/`, details in `CLAUDE.md` section 10) - **no client is created**, and the number of projects is limited
(`utils/ProjectBudget`), the location is always India:

| XML suite | Test class (`src/test/java/tests/project/`) | Cases | Projects created |
|---|---|---|---|
| `testng-project-happy-flow.xml` | `ProjectCreationHappyFlow` | TC-PRJ-01 **AOR**, TC-PRJ-02 **EOR**: sign in as the EOR user, create a project for an existing client, find it by its exact name in its listing: status **Active** | **1 AOR + 1 EOR** |
| `testng-project-edge-cases.xml` | `ProjectCreationEdgeCases` | TC-PRJ-03 **AOR**, TC-PRJ-04 **EOR**: every refused input, boundary and behaviour scenario of the Add Project form, what the listing really holds, search, tabs, client filter, page size, pages, sort, access without a session - all scenarios of a type are steps of ONE case | **at most 5 AOR + 5 EOR** |

```bash
mvn clean test -Dsuite=testng-project-happy-flow.xml     # ~1 minute
mvn clean test -Dsuite=testng-project-edge-cases.xml     # ~15 minutes; -Dcases=TC-PRJ-03 (AOR only) / AOR-Q01,AOR-K04 (some scenarios)
```

**Client names:** both clients get an **anime name** (e.g. "Dragon Ball Z"); the contact person is its main character ("Son Goku", `songoku@yopmail.com`) and the Business / Finance / HR contacts are three
other characters of the same anime. `testdata/existing-clients.txt` lists the anime already used: it is read before every registration (so one is never used twice) and the new client is appended right
after it is created. Delete a line to free a name.

**The 2-client rule:** the module never creates more than 2 clients (1 + 1). The edge-case class registers ONE client (case R-60, a double click on *Register Client*) and walks that client through the wizard:
enter details, move next, go back, re-enter different values ... It never confirms the wizard (`Confirm & Continue` starts the MSA) and never approves a client, so that client stays *In-Progress*. All e-mail addresses are `@yopmail.com`.

## Prerequisites
Java 17, Maven 3.9+, Google Chrome (driver is resolved automatically by Selenium Manager), internet access (sandbox + yopmail.com).

## Configuration
| File | Purpose | Committed |
|---|---|---|
| `config/config.properties` | environment, URLs, browser, timeouts, evidence flags, client type | yes (no secrets) |
| `config/documents.properties` | local paths of the client test documents (CIN.pdf, GSTIN.pdf) | yes |
| `config/secrets.local.properties` | admin/EOR logins, common client password | **no (gitignored)** |
| `config/secrets.example.properties` | template for the file above | yes |
| `testdata/client-onboarding.json` | static test-data template (unique values are generated per run) | yes |

Any key can be overridden with `-Dkey=value` or an env var `KOMP_<KEY_UPPER_WITH_UNDERSCORES>`. Reports, logs and screenshots show the logins (e-mails, client password, temporary password, admin / EOR logins) so the report can be handed over as it is;
`-Dmask.secrets=true` hides them (a production run always does). `config/secrets.local.properties`, `reports/` and `*.log` are gitignored.
First-time setup: copy `config/secrets.example.properties` to `config/secrets.local.properties` and fill it in.

## Run (the browser is visible and maximized; add `-DHeadless=true` only to hide it)
```bash
mvn clean test                                          # = the happy flow (default suite)
mvn clean test -Dsuite=testng-client-happy-flow.xml     # happy flow + page-by-page checks (creates 1 client, ~8 min)
mvn clean test -Dsuite=testng-client-edge-cases.xml     # all negative / edge cases        (creates 1 client, ~20 minutes)
```
Edge cases - options (all optional):
```bash
-Dcases=O-10,C-03                    # only these case ids (the set-up steps - register, mail, first login ... - run on demand)
-Dedge.client.email=<yopmail>        # use an existing client instead of registering (password: env KOMP_EDGE_CLIENT_PASSWORD, default = common client password;
                                     # the report folder keeps the used client in test-data.json - if the app accepted case P-23 its password is 'Aa1!' + 296 x 'x')
                                     # the client's legal name is looked up by this e-mail in testdata/existing-clients.txt (or give -Dedge.client.name="<legal name>")
-Dedge.client.fresh=true             # ... a client that NEVER logged in: its first-login password cases run too (needs the name: registry or -Dedge.client.name)
-Deor.active.client="<name>"         # ACTIVE client for B-40 / B-41 / X-70 (default: the first ACTIVE client of the list)
```
Happy flow - re-check the profile of the client of an earlier run **without creating a client**:
```bash
mvn clean test -Dsuite=testng-client-happy-flow.xml -Dhappy.client.data=reports/<happy-flow folder>/test-data.json
```
Production is refused unless `-Dallow.production=true`. Parallel execution is off on purpose. IDE: run the XML as a TestNG suite or the class itself (working directory = project root).
If the browser window is closed by hand during a run, a new one is started before the next case. If the internet connection drops, the case that was running is reported as SKIP
("network lost - run it again", never FAIL) and the run waits (`-Dnetwork.wait.max.seconds`, default 600) until the application answers again before it goes on. The application rate-limits sign-ins
("Too many sign-in attempts"): the runs wait and retry (up to `login.ratelimit.max.wait.seconds`, default 600).

## Reports - one folder per test CLASS, one dated folder per run (never overwritten)
```
reports/<ClassName>/<YYYY-MM-DD_hh.mm.ss>/        (hh = 12-hour clock, e.g. reports/ClientOnboardingEdgeCases/2026-11-07_07.14.10)
  <ClassName>.html              Extent report of that run (screenshot links are relative: share the whole folder)
  screenshots/                  NN_<phase>_PASS|FAIL|DEFECT|SKIP.png  (this run only)
  logs/execution.log
  api-evidence/                 network-<caseId>.json  (method + path + status only: no headers, cookies or bodies)
  test-data.json                the client data of the run, no passwords - feeds -Dhappy.client.data / -Dedge.client.email
```
Screenshots are full-page (the KOMP pages scroll inside their own panel, so the whole page is captured, not only the visible part; pages with a dialog / PDF viewer open get a normal screenshot).
`-Dscreenshot.fullpage=false` switches this off.
An XML suite with several classes produces one run folder per class (separate HTML and separate screenshots). At the end of every class the console
and the log print a plain summary (cases / passed / FAILED / skipped and the report paths).
A FAIL on a negative case means a finding: the application accepted input it should reject, showed no (or a misleading, or a raw database) message, or a visible value differs from what was entered.
**The reports are written for people who are not technical** (every module, also future ones): each step says "What should happen" and "What happened" in everyday words, a failed case starts with
"FAILED because: ..." in plain sentences, and the technical evidence (error traces, server requests) is folded into a "Technical details (only for developers)" block. The rule and the helper (`utils/PlainText`) are in `CLAUDE.md` section 2.

**The full guide - every test case with what it tests, rules, run options, live facts, bugs found - is in [`CLAUDE.md`](CLAUDE.md).**

## Edge-case ids (`ClientOnboardingEdgeCases`)
| Ids | Area |
|---|---|
| L-01..L-09, L-20 | Login (signed out; L-09 is the wrong password of the edge client, run late) |
| B-01..B-09 | Protected URLs without a session |
| R-01..R-20, R-30, R-32, R-33, R-40, R-60 | Register Client form (R-60 registers THE edge client) |
| P-00, P-02, P-10..P-23, P-30 | Welcome mail, first login, password rules, permanent password |
| B-10..B-21, B-26 | The client on admin URLs, session / reload |
| O-00..O-25 | Organization Details (step 1) |
| D-01..D-11 | Documents |
| C-00..C-21 | Points of Contact (step 2) |
| B-30..B-37 | Wizard: refresh, browser Back, re-login, Review page, **back-and-edit** (B-35 / B-36: change values after Back, they must reach the Review page), B-37 documents still attached after Back |
| B-22..B-25, B-40, B-41 | Admin session / deep link / sign-out, EOR status of an ACTIVE client |
| X-01..X-35 | Clients list search and filters (Country, Client Type, status tiles, Clear all) |
| X-40..X-65 | Pagination, page size, tiles vs list, sorting, Columns menu |
| X-70..X-72, X-80, X-81 | MSA search, API health (admin and client) |

Skips in a run: `O-15..O-19` (read-only pre-filled fields, nothing to type) and `R-60` only when an existing client is reused with `-Dedge.client.email` (nothing is registered; the first-login password cases are not run then either).
The cases that could register an EXTRA client (`R-31`, `R-50..R-52`) and `P-01` were removed from the suite, so nothing is skipped because of the client limit.
Happy-flow ids (always numbered from 1): `TC-E2E-01` (the journey; SKIP when an existing client is re-checked) and, in this order, `TC-E2E-02` MSA list shows ACTIVE, `TC-E2E-03` Clients list shows Active,
`TC-E2E-04` admin profile (every value in one table, incl. the documents), `TC-E2E-05` Point of Contact tab, `TC-E2E-06` MSA tab, `TC-E2E-07` client sign-in + My Profile, `TC-E2E-08` every client-portal tab
(Home, Employees, Projects, MSA). Each page is one case with ONE table of values (wrong ones in red) and one screenshot.

## Design notes
- `pages/` page objects, `flow/ClientOnboardingFlow` one method per journey phase, `base/BaseTest` the shared lifecycle (one browser and one report per class,
  teardown always runs, closed-browser recovery, `mustReject` verdict rules), `reporting/` Extent report + screenshots + logs, `api/` network observation, `utils/` config, data, masking.
- Edge cases are observation based: `mustReject` = rejected with a relevant message -> PASS; accepted / silent / misleading / raw database text -> FAIL.
- A phase failure stops a journey (screenshot, expected vs actual, stack trace, console errors); a **defect** (soft check) is recorded and the next checks still run.
- Each case prepares and heals its own state before it starts (signed in? right wizard step? baseline values?), so one failure never breaks the next case.

## Where the live application differs from the original brief
| Brief | Actual sandbox behaviour (used by the tests) |
|---|---|
| Point of Contact is a dropdown | Free-text "Point of Contact Name" |
| Welcome subject "Welcome to KOMP - Your Client Portal" | "Welcome to KOMP - Your client portal is ready" (configurable fragment) |
| Legal / Finance / HR contacts | **Business** / Finance / HR contacts |
| Separate Start / Continue pages | 4-step wizard + a "Continue Setup" prompt after step 1 |
| "Onboarding Successful" message after Done | Client is signed out after Done; home then shows 67% until EOR approves, 100% after |
| EOR search by client email | MSA list searches by **client name** |
| "Approve & Submit" | "Approve & Activate" |
| Separate EOR URL | Same portal; role is decided by the account (`<env>.eor.*` overrides, default = admin login) |
| State/city auto-fill check | Explicitly **not asserted** (pincode lookup is API driven); the profile check only compares the profile with what the form showed |

## Known limitations
- API validation is `NOT CONFIGURED`: no API contract was supplied, so calls are recorded as observations only.
- Not automated on purpose (they change a client's state): MSA signing edge cases, approving an unsigned client, AOR end to end, mid-generation sign-out.
- Findings seen in earlier executions that cannot be reproduced without a new client (`msa-contract/api/status` 404 during MSA generation, an intermittent notifications 500)
  are listed in `CLAUDE.md` (section 6) and kept in `testdata/evidence-observations.json`.
- AOR and preprod flows have not been executed yet.
