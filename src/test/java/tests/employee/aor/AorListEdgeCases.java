package tests.employee.aor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.openqa.selenium.By;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import base.BaseTest;
import model.ClientData;
import pages.AorAgentsPage;
import pages.AorInvoicesPage;
import pages.AorListPage;
import pages.AorPayrollPage;
import pages.LoginPage;
import reporting.ExecutionInfo;
import reporting.ExtentManager;
import reporting.ScenarioBoard;
import reporting.TestReporter;
import reporting.ValueChecks;
import utils.ConfigManager;
import utils.Log;

/**
 * AOR LIST SCREENS - negative and edge cases (user, 2026-11-13): the three lists of the AOR user - AOR Agents, AOR Payroll and AOR Invoices - are checked the way the Clients list and
 * the project lists are: search (existing value, part of it, capital / small letters, spaces around, no match, symbols, script, SQL, very long, cleared), status tabs, the filter
 * "Client" (also together with the search, and "Clear all"), page sizes, pages, Previous / Next, sorting of every sortable heading, the Columns menu (Agents) and the date boxes
 * (Payroll, Invoices). Everything is READ-ONLY: nothing is created or changed. The expected results are computed from the whole list, which is read once (all pages, 50 per page).
 * One scenario = one report entry (the reporting standard); TC-AOR-LIST-01 = Agents, -02 = Payroll, -03 = Invoices.
 */
public class AorListEdgeCases extends BaseTest {

    private static final DateTimeFormatter[] DATE_FORMATS = {DateTimeFormatter.ofPattern("dd-MM-yyyy"), DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH), DateTimeFormatter.ofPattern("dd/MM/yyyy"), DateTimeFormatter.ofPattern("yyyy-MM-dd"), DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)};

    private ClientData holder;
    private com.aventstack.extentreports.ExtentTest summary;
    private TestReporter rep;
    private AorListPage list;
    private AorListPage.Spec spec;
    private String tc;
    private int ran;
    private List<Map<String, String>> full = new ArrayList<>();
    private List<String> original = new ArrayList<>();

    @Test(priority = 1)
    public void tc01_agents() {
        run("TC-AOR-LIST-01", "AOR Agents", new AorListPage.Spec("AOR Agents", () -> new AorAgentsPage(driver).open(), By.xpath("//main//input[@placeholder='Search by name or ID']"),
                new String[] {"All Agents", "In-Progress Agents", "Active Agents", "Inactive Agents"}, "STATUS", "CLIENT", null, new String[] {"AGENT", "STATE", "CLIENT", "STATUS"},
                new String[] {"AGENT"}, true, false));
    }

    @Test(priority = 2)
    public void tc02_payroll() {
        run("TC-AOR-LIST-02", "AOR Payroll", new AorListPage.Spec("AOR Payroll", () -> new AorPayrollPage(driver).open(), By.xpath("//main//input[starts-with(@placeholder,'Search')]") /* the list's own box, NOT the global search in the header */,
                new String[] {"All", "Paid", "Pending", "Hold"}, "STATUS", null, "PAY PERIOD", new String[] {"DELIVERY HUB", "AGENT NAME", "STATE", "STATUS"},
                new String[] {"AGENT NAME", "SESSION ID", "EMPLOYEE ID"}, false, true));
    }

    @Test(priority = 3)
    public void tc03_invoices() {
        run("TC-AOR-LIST-03", "AOR Invoices", new AorListPage.Spec("AOR Invoices", () -> new AorInvoicesPage(driver).open(), By.xpath("//main//input[starts-with(@placeholder,'Search invoices')]"),
                new String[] {"All Invoice", "Paid Invoice", "Pending Invoice"}, "STATUS", "CLIENT NAME", "INVOICE DATE", new String[] {"INVOICE ID", "CLIENT NAME", "STATE", "STATUS"},
                new String[] {"INVOICE ID", "SESSION ID", "CLIENT NAME"}, false, true));
    }

    // ================================================================================================================================ one screen

    private void run(String tcId, String screen, AorListPage.Spec s) {
        if (!isSelected(tcId) && !isSelected("TC-AOR-LIST")) {
            return;
        }
        this.tc = tcId;
        this.spec = s;
        this.list = new AorListPage(driver, s);
        this.ran = 0;
        this.full = new ArrayList<>();
        this.original = new ArrayList<>();
        holder = new ClientData();
        holder.legalName = screen;
        holder.clientType = "AOR";
        holder.email = ConfigManager.aorEmail();
        summary = ExtentManager.get().createTest("SUMMARY | " + tc + " | " + screen + " list: negative and edge cases").assignCategory(ExecutionInfo.module(), tc, "Summary");
        try {
            define(screen);
        } finally {
            List<ScenarioBoard.Outcome> mine = ScenarioBoard.outcomes(tc);
            boolean red = mine.stream().anyMatch(o -> o.verdict == ScenarioBoard.Verdict.FAIL);
            summary.log(red ? com.aventstack.extentreports.Status.FAIL : com.aventstack.extentreports.Status.PASS, ScenarioBoard.summaryHtml(tc, tc + " - " + screen + " list"));
        }
        long failed = ScenarioBoard.outcomes(tc).stream().filter(o -> o.verdict == ScenarioBoard.Verdict.FAIL).count();
        Assert.assertEquals(failed, 0L, failed + " scenario(s) of " + tc + " found problems in the application (see the scenario entries of the report)");
    }

    private String headline() {
        return "AOR list: " + (spec == null ? "" : spec.name) + " | signed in as " + utils.SecretMasker.maskEmail(ConfigManager.aorEmail()) + " | scenarios run: " + ran;
    }

    @FunctionalInterface
    private interface Body {
        void run(ValueChecks c) throws Exception;
    }

    /** One scenario = one report entry (the reporting standard). */
    private void s(String sid, String tag, String title, String expected, Body body) {
        ran++;
        TestReporter r = TestReporter.startScenario(tc, sid, List.of(tag), title, "What is checked: " + title, "The list \"" + spec.name + "\" as it is shown", expected, driver, holder, this::headline);
        r.captureFullApi();
        rep = r;
        Throwable error = null;
        SkipException skip = null;
        try {
            r.phaseChecks(tc.toLowerCase(Locale.ROOT).replace('-', '_') + "_" + sid.toLowerCase(Locale.ROOT), sid + " | " + title, expected, body::run);
        } catch (SkipException e) {
            skip = e;
        } catch (Throwable t) {
            error = t;
        }
        r.finishAuto(error, skip);
    }

    // ================================================================================================================================ the scenarios

    private void define(String screen) {
        s("S00", "Set-up", "Sign in as the AOR user and open " + screen, "The list opens and shows rows", c -> {
            new LoginPage(driver).freshLogin(ConfigManager.baseUrl());
            new LoginPage(driver).login(ConfigManager.aorEmail(), ConfigManager.aorPassword());
            list.open();
            c.check(screen, "The list shows rows", !list.rows().isEmpty(), "rows", list.rows().size() + " rows");
            c.check(screen, "The list says how many entries there are", list.total() > 0, "\"Showing a-b of N\"", list.showing());
        });
        s("L01", "Consistency", "The whole list is read: the number of rows equals the total of \"Showing\", and no row is shown on two pages", "Rows = total, no repeated row", c -> {
            list.open();
            int total = list.total();
            full = list.allRows();
            original = new ArrayList<>();
            for (Map<String, String> r : full) {
                original.add(String.join("|", r.values()));
            }
            c.check(screen, "Rows read on all pages equal the total", full.size() == total, String.valueOf(total), String.valueOf(full.size()));
            Set<String> unique = new LinkedHashSet<>(original);
            c.check(screen, "No row is shown twice", unique.size() == original.size(), "no repeated row", (original.size() - unique.size()) + " repeated rows");
            Assert.assertFalse(full.isEmpty(), "The list is empty: the other scenarios cannot be judged.");
        });

        // ---------------------------------------------------------------- search
        searchCase("L02", "The full value of an existing entry", "@FULL");
        searchCase("L03", "Part of an existing value (its last 5 characters)", "@PART");
        searchCase("L04", "An existing value in CAPITAL letters", "@UPPER");
        searchCase("L05", "An existing value in small letters", "@LOWER");
        searchCase("L06", "An existing value with 3 spaces before and after", "@SPACES");
        s("L07", "Search", "A search without a match shows a message, not the full list", "No rows and a readable message", c -> {
            list.open();
            String term = "zzz-no-such-entry-" + System.currentTimeMillis() % 100000;
            List<Map<String, String>> rows = list.search(term);
            c.check(screen, "No row is listed", rows.isEmpty(), "no rows", rows.size() + " rows");
            String text = list.pageText().toLowerCase(Locale.ROOT);
            c.check(screen, "A readable message says that nothing was found", text.contains("no ") && (text.contains("found") || text.contains("result") || text.contains("match")), "a message like \"No ... found\"",
                    shorten(list.pageText()));
            list.search("");
        });
        String[][] odd = {{"L08", "<script>alert(1)</script>"}, {"L09", "%"}, {"L10", "'"}, {"L11", "\"; DROP TABLE agents;--"}, {"L12", "\\"}, {"L13", "A".repeat(300)}, {"L14", "_"}, {"L15", "éñü"},
                {"L16", "😀"}, {"L17", "{{7*7}}"}, {"L18", "=1+1"}};
        for (String[] o : odd) {
            safeCase(o[0], o[1]);
        }
        s("L19", "Search", "Clearing the search brings the full list back", "The same total as before", c -> {
            list.open();
            int before = list.total();
            list.search(keyOf(full.get(0)));
            list.search("");
            c.check(screen, "The total after clearing", list.total() == before, String.valueOf(before), String.valueOf(list.total()));
        });

        // ---------------------------------------------------------------- tabs
        for (int i = 0; i < spec.tabs.length; i++) {
            final String tab = spec.tabs[i];
            final boolean all = i == 0;
            s("T" + (i + 1), "Status tabs", "The tab \"" + tab + "\" shows only its own status and its total is right", all ? "The total of the whole list" : "One status; the total equals the entries of that status", c -> {
                list.open();
                list.clickTab(tab);
                List<Map<String, String>> rows = list.rows();
                int total = list.total();
                if (all) {
                    c.check(tab, "The total equals the whole list", total == full.size(), String.valueOf(full.size()), String.valueOf(total));
                    return;
                }
                Set<String> states = new LinkedHashSet<>();
                for (Map<String, String> r : rows) {
                    states.add(list.column(r, spec.statusHeader));
                }
                c.check(tab, "Every shown entry has the same status", states.size() <= 1, "one status", states.toString());
                if (!states.isEmpty()) {
                    String status = states.iterator().next();
                    long expected = full.stream().filter(r -> list.column(r, spec.statusHeader).equals(status)).count();
                    c.check(tab, "The total equals the entries with the status \"" + status + "\"", total == expected, String.valueOf(expected), String.valueOf(total));
                } else {
                    c.check(tab, "An empty tab says so", list.pageText().toLowerCase(Locale.ROOT).contains("no "), "a message", shorten(list.pageText()));
                }
                list.clickTab(spec.tabs[0]);
            });
        }

        // ---------------------------------------------------------------- client filter
        if (spec.clientHeader != null) {
            for (int i = 0; i < 2; i++) {
                final int n = i;
                s("F" + (i + 1), "Filters", "The filter \"Client\": entries of client number " + (i + 1) + " of the list only, and \"Clear all\" brings everything back", "Only that client; the right total", c -> {
                    list.open();
                    List<String> clients = new ArrayList<>();
                    for (Map<String, String> r : full) {
                        String cl = list.column(r, spec.clientHeader);
                        if (!cl.isBlank() && !clients.contains(cl)) {
                            clients.add(cl);
                        }
                    }
                    if (clients.size() <= n) {
                        throw new SkipException("Not run: the list has only " + clients.size() + " client(s).");
                    }
                    String client = clients.get(n);
                    list.selectClient(client);
                    List<Map<String, String>> rows = list.rows();
                    boolean only = rows.stream().allMatch(r -> list.column(r, spec.clientHeader).equals(client));
                    long expected = full.stream().filter(r -> list.column(r, spec.clientHeader).equals(client)).count();
                    c.check(client, "Every shown entry belongs to the client", only, "only \"" + client + "\"", only ? "yes" : "other clients are shown");
                    c.check(client, "The total equals the entries of the client", list.total() == expected, String.valueOf(expected), String.valueOf(list.total()));
                    c.check(client, "\"Clear all\" is offered", list.clearAllFilters(), "the button \"Clear all\"", "not offered");
                    c.check(client, "After \"Clear all\" the whole list is back", list.total() == full.size(), String.valueOf(full.size()), String.valueOf(list.total()));
                });
            }
            s("F3", "Filters", "The filter \"Client\" together with the search (both must apply)", "Only entries of the client that also contain the search text", c -> {
                list.open();
                String c1 = list.column(full.get(0), spec.clientHeader);
                Map<String, String> other = full.stream().filter(r -> !list.column(r, spec.clientHeader).equals(c1)).findFirst().orElse(null);
                if (other == null) {
                    throw new SkipException("Not run: the list has only one client.");
                }
                list.selectClient(c1);
                String term = keyOf(other);
                List<Map<String, String>> rows = list.search(term);
                boolean ok = rows.stream().allMatch(r -> list.column(r, spec.clientHeader).equals(c1) && contains(r, term));
                c.check(c1 + " + \"" + term + "\"", "Only entries that fit BOTH", ok, "client \"" + c1 + "\" and the text \"" + term + "\"", rows.size() + " rows");
                list.search("");
                list.clearAllFilters();
            });
        } else {
            s("F1", "Filters", "The filter \"Client\" narrows the list and \"Clear all\" brings everything back", "A smaller (or equal) total, then the whole list", c -> {
                list.open();
                List<String> options = list.clientOptions();
                c.check(spec.name, "The filter offers clients", !options.isEmpty(), "at least one client", options.size() + " clients");
                list.selectClient(options.get(0));
                c.check(spec.name, "The total is not larger than the whole list", list.total() <= full.size(), "at most " + full.size(), String.valueOf(list.total()));
                list.clearAllFilters();
                c.check(spec.name, "After \"Clear all\" the whole list is back", list.total() == full.size(), String.valueOf(full.size()), String.valueOf(list.total()));
            });
        }

        // ---------------------------------------------------------------- paging
        for (int size : new int[] {5, 10, 20, 30, 40, 50}) {
            s("P" + size, "Paging", "Page size " + size + ": " + size + " entries per page (or fewer on a short list), and the \"Showing\" text agrees", "min(" + size + ", N) rows", c -> {
                list.open();
                list.pageSize(size);
                int n = full.size();
                int expected = Math.min(size, n);
                c.check(spec.name, "Number of rows", list.rows().size() == expected, String.valueOf(expected), String.valueOf(list.rows().size()));
                c.check(spec.name, "\"Showing\" text", list.showing().replaceAll("\\s+", " ").contains("1") && list.showing().contains(String.valueOf(n)), "Showing 1-" + expected + " of " + n, list.showing());
            });
        }
        s("P2", "Paging", "Page 2 shows other entries than page 1 and the text moves on", "Different rows; \"Showing 11-20\"", c -> {
            list.open();
            list.pageSize(10);
            String first = String.join("|", list.rows().get(0).values());
            if (!list.gotoPage(2)) {
                throw new SkipException("Not run: the list has only one page.");
            }
            List<Map<String, String>> rows = list.rows();
            c.check(spec.name, "Page 2 has other entries", !rows.isEmpty() && !String.join("|", rows.get(0).values()).equals(first), "other entries", rows.isEmpty() ? "no rows" : "same first entry");
            c.check(spec.name, "\"Showing\" text moved on", list.showing().contains("11"), "Showing 11-20 of N", list.showing());
        });
        s("P3", "Paging", "Previous is off on the first page, Next is off on the last page, and Previous goes back one page", "Buttons enabled only where there is a page to go to", c -> {
            list.open();
            list.pageSize(50);
            list.gotoPage(1);
            c.check(spec.name, "Previous is disabled on page 1", !list.previousEnabled(), "disabled", list.previousEnabled() ? "enabled" : "disabled");
            int pages = 1;
            while (list.nextEnabled() && pages < 40) {
                list.clickNext();
                list.settle();
                pages++;
            }
            c.check(spec.name, "Number of pages equals ceil(N / 50)", pages == (int) Math.ceil(full.size() / 50.0), String.valueOf((int) Math.ceil(full.size() / 50.0)), String.valueOf(pages));
            c.check(spec.name, "Next is disabled on the last page", !list.nextEnabled(), "disabled", list.nextEnabled() ? "enabled" : "disabled");
            if (pages > 1) {
                String last = list.showing();
                list.clickPrevious();
                list.settle();
                c.check(spec.name, "Previous goes back one page", !list.showing().equals(last), "another page", list.showing());
            }
        });

        // ---------------------------------------------------------------- sort
        for (String header : spec.sortHeaders) {
            s("O-" + header.replace(' ', '-'), "Sorting", "Sorting by \"" + header + "\": A to Z, Z to A and back to the original order, over ALL pages (not only the 10 rows of the page)", "Page 1 holds the first 10 entries of the whole list in that order", c -> {
                list.open();
                list.pageSize(10);
                List<String> values = new ArrayList<>();
                for (Map<String, String> r : full) {
                    values.add(list.column(r, header));
                }
                if (values.stream().anyMatch(v -> !v.matches("[A-Za-z0-9 \\-]*"))) {
                    throw new SkipException("Not run: the values of this column contain symbols (their order is the application's own collation).");
                }
                List<String> asc = new ArrayList<>(values);
                asc.sort(String.CASE_INSENSITIVE_ORDER);
                list.sortBy(header);
                List<String> shown = column(header);
                c.check(header, "First click: A to Z over the whole list", shown.equals(asc.subList(0, Math.min(10, asc.size()))), asc.subList(0, Math.min(10, asc.size())).toString(), shown.toString());
                list.sortBy(header);
                List<String> desc = new ArrayList<>(asc);
                java.util.Collections.reverse(desc);
                shown = column(header);
                c.check(header, "Second click: Z to A over the whole list", shown.equals(desc.subList(0, Math.min(10, desc.size()))), desc.subList(0, Math.min(10, desc.size())).toString(), shown.toString());
                list.sortBy(header);
                shown = column(header);
                c.check(header, "Third click: back to the original order", shown.equals(values.subList(0, Math.min(10, values.size()))), values.subList(0, Math.min(10, values.size())).toString(), shown.toString());
            });
        }

        // ---------------------------------------------------------------- columns (Agents)
        if (spec.columnsMenu) {
            s("K1", "Columns", "The Columns menu hides and shows every column in turn; the others stay as they are", "The heading and its cells disappear and come back", c -> {
                list.open();
                List<String> items = list.columnMenuItems();
                c.check(spec.name, "The menu offers columns", !items.isEmpty(), "columns", items.toString());
                for (String item : items) {
                    boolean inTable = list.headers().stream().anyMatch(h -> h.equalsIgnoreCase(item) || h.toLowerCase(Locale.ROOT).contains(item.toLowerCase(Locale.ROOT)));
                    if (!inTable) {
                        c.check(item, "The menu entry belongs to a column of the table", false, "a heading \"" + item + "\" in the table", "the table has the headings " + list.headers());
                        continue;
                    }
                    int cells = list.firstRowCells();
                    List<String> headersBefore = list.headers();
                    list.toggleColumn(item);
                    c.check(item, "Hidden: the cells of the row become fewer", list.firstRowCells() == cells - 1, String.valueOf(cells - 1), String.valueOf(list.firstRowCells()));
                    c.check(item, "Hidden: the heading is gone", list.headers().size() == headersBefore.size() - 1, String.valueOf(headersBefore.size() - 1), String.valueOf(list.headers().size()));
                    list.toggleColumn(item);
                    c.check(item, "Shown again: everything is back", list.firstRowCells() == cells && list.headers().size() == headersBefore.size(), cells + " cells", list.firstRowCells() + " cells");
                }
            });
        }

        // ---------------------------------------------------------------- dates (Payroll, Invoices)
        if (spec.dateBoxes) {
            s("D1", "Dates", "One day chosen in both date boxes: only entries of that day", "Entries of that day only; the right total", c -> {
                list.open();
                LocalDate day = dateOf(full.get(0));
                if (day == null) {
                    throw new SkipException("Not run: the date format of the column \"" + spec.dateHeader + "\" is not understood (\"" + list.column(full.get(0), spec.dateHeader) + "\").");
                }
                list.setDates(day, day);
                List<Map<String, String>> rows = list.rows();
                long expected = full.stream().filter(r -> day.equals(dateOf(r))).count();
                boolean only = rows.stream().allMatch(r -> day.equals(dateOf(r)));
                c.check(day.toString(), "Every shown entry is of that day", only, "only " + day, only ? "yes" : "other days are shown");
                c.check(day.toString(), "The total equals the entries of that day", list.total() == expected, String.valueOf(expected), String.valueOf(list.total()));
                list.clearDates();
                c.check(day.toString(), "After clearing the dates the whole list is back", list.total() == full.size(), String.valueOf(full.size()), String.valueOf(list.total()));
            });
            s("D2", "Dates", "The first date after the second one (from later than to)", "No entries and a readable message (not the full list)", c -> {
                list.open();
                LocalDate day = LocalDate.now();
                list.setDates(day, day.minusDays(10));
                c.check(spec.name, "No entry is shown", list.rows().isEmpty(), "no rows", list.rows().size() + " rows");
                list.clearDates();
            });
            s("D3", "Dates", "Dates far in the future (year 2099) show nothing", "No entries", c -> {
                list.open();
                list.setDates(LocalDate.of(2099, 1, 1), LocalDate.of(2099, 12, 31));
                c.check(spec.name, "No entry is shown", list.rows().isEmpty(), "no rows", list.rows().size() + " rows");
                list.clearDates();
            });
            s("D4", "Dates", "Dates far in the past (year 1900) show nothing", "No entries", c -> {
                list.open();
                list.setDates(LocalDate.of(1900, 1, 1), LocalDate.of(1900, 12, 31));
                c.check(spec.name, "No entry is shown", list.rows().isEmpty(), "no rows", list.rows().size() + " rows");
                list.clearDates();
            });
        }
    }

    // ================================================================================================================================ helpers

    private String keyOf(Map<String, String> row) {
        String v = list.column(row, spec.searchHeaders[spec.searchHeaders.length > 1 ? 1 : 0]);
        if (spec.searchHeaders.length == 1) {
            String[] tokens = v.trim().split("\\s+");
            return tokens[tokens.length - 1];
        }
        return v.trim();
    }

    private boolean contains(Map<String, String> row, String term) {
        String t = term.trim().toLowerCase(Locale.ROOT);
        for (String h : spec.searchHeaders) {
            if (list.column(row, h).toLowerCase(Locale.ROOT).contains(t)) {
                return true;
            }
        }
        return false;
    }

    private void searchCase(String sid, String title, String term) {
        s(sid, "Search", "Search: " + title, "Every listed entry contains the text; every entry of the list that contains it is listed", c -> {
            list.open();
            String actual = resolve(term);
            List<Map<String, String>> rows = list.search(actual);
            long expected = full.stream().filter(r -> contains(r, actual)).count();
            boolean allContain = rows.stream().allMatch(r -> contains(r, actual));
            c.check("Search \"" + shorten(actual) + "\"", "Every listed entry contains the text", allContain, "only matching entries", allContain ? "yes" : "entries that do not contain the text are listed");
            c.check("Search \"" + shorten(actual) + "\"", "The total equals the entries that contain the text", list.total() == expected, String.valueOf(expected), String.valueOf(list.total()));
            list.search("");
        });
    }

    /** The markers of the search scenarios are turned into values of the real list at run time. */
    private String resolve(String term) {
        String key = keyOf(full.get(0));
        switch (term) {
            case "@FULL": return key;
            case "@PART": return key.length() > 5 ? key.substring(key.length() - 5) : key;
            case "@UPPER": return key.toUpperCase(Locale.ROOT);
            case "@LOWER": return key.toLowerCase(Locale.ROOT);
            case "@SPACES": return "   " + key + "   ";
            default: return term;
        }
    }

    /** A text that is no value of the list: it must be handled as plain text (nothing runs, no server error, only matching rows or none). */
    private void safeCase(String sid, String term) {
        s(sid, "Search", "Search text " + (term.length() > 20 ? term.substring(0, 12) + "... (" + term.length() + " characters)" : "\"" + term + "\"") + " is handled safely", "Plain text: nothing runs, no server error, only matching rows or none; the page keeps working", c -> {
            list.open();
            int calls = rep.observeApi().size();
            List<Map<String, String>> rows;
            try {
                rows = list.search(term);
            } catch (org.openqa.selenium.UnhandledAlertException alert) {
                c.check(spec.name, "Nothing the text contains is run", false, "the text is shown as plain text", "a script ran (an alert opened)");
                return;
            }
            long expected = full.stream().filter(r -> contains(r, term)).count();
            boolean allContain = rows.stream().allMatch(r -> contains(r, term));
            c.check(spec.name, "Only matching entries are listed (or none)", allContain, "matching entries only", allContain ? "yes" : rows.size() + " rows, some do not contain the text");
            c.check(spec.name, "The total equals the entries that contain the text", list.total() == expected || rows.isEmpty() && expected == 0, String.valueOf(expected), String.valueOf(list.total()));
            boolean serverError = rep.observeApi().stream().skip(0).anyMatch(a -> a.isServerError());
            c.check(spec.name, "No request ended in a server error", !serverError, "no server error", serverError ? "a request ended in a server error" : "none");
            c.check(spec.name, "The page keeps working (the search box is still there)", !driver.findElements(spec.search).isEmpty(), "the search box", "present");
            list.search("");
        });
    }

    private List<String> column(String header) {
        List<String> out = new ArrayList<>();
        for (Map<String, String> r : list.rows()) {
            out.add(list.column(r, header));
        }
        return out;
    }

    private LocalDate dateOf(Map<String, String> row) {
        String v = list.column(row, spec.dateHeader).trim();
        String first = v.split("\\s+(to|-)\\s+|\\s+–\\s+")[0].trim();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(first, f);
            } catch (DateTimeParseException ignored) {
                // the next format
            }
        }
        return null;
    }

    private static String shorten(String v) {
        return v.length() > 60 ? v.substring(0, 60) + "..." : v;
    }
}
