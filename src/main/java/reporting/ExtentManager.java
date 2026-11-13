package reporting;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;
import com.aventstack.extentreports.reporter.configuration.Theme;

import utils.ConfigManager;
import utils.SecretMasker;

/** The Extent report of the CURRENT test class (see {@link ExecutionInfo}); {@link #reset()} closes it when the class ends. */
public final class ExtentManager {

    private static ExtentReports extent;

    /**
     * Layout fixes for the Spark template (checked in a browser with a 2-line title):
     * a title that wraps onto a second line no longer runs into the date chips and the tag chips, and the tags
     * (module + group, e.g. "Client-Onboarding", "E2E-verification") are shown once in the header of a case, not repeated on every phase row.
     */
    private static final String CSS =
            ".detail-head .info, .detail-head .info > * { height: auto !important; min-height: 0 !important; }"
                    + ".detail-head .info h5.test-status { margin-bottom: 8px; line-height: 1.35; }"
                    + ".detail-head .info .badge { margin-top: 2px; margin-bottom: 2px; }"
                    + ".node-attr { display: none !important; }"
                    // verdict colours of the scenario reports: NOT RUN / NOT JUDGED grey, BLOCKED orange
                    + ".skip-bg, .badge.skip, .status.skip, .test-status.skip { background-color: #9e9e9e !important; color: #fff !important; }"
                    + ".text-skip { color: #757575 !important; }"
                    + ".warning-bg, .badge.warning, .status.warning, .test-status.warning { background-color: #ef6c00 !important; color: #fff !important; }"
                    + ".text-warning { color: #ef6c00 !important; }"
                    // summary entries: the Status and Timestamp columns of the log table are hidden, the summary uses the whole width (its own time stands at its top right)
                    + "table:has(.komp-summary) > thead > tr > :nth-child(-n+2), table:has(.komp-summary) > tbody > tr > :nth-child(-n+2), table:has(.komp-summary) > tr > :nth-child(-n+2) { display: none !important; }"
                    + "table:has(.komp-summary) > thead > tr > :nth-child(3), table:has(.komp-summary) > tbody > tr > :nth-child(3), table:has(.komp-summary) > tr > :nth-child(3) { width: 100% !important; }"
                    // the Status and Timestamp columns of every log table are hidden (the verdict stands in the title row, the time at the top of the entry): Details gets the whole width
                    + ".detail-body table.table-sm > thead > tr > :nth-child(-n+2), .detail-body table.table-sm > tbody > tr > :nth-child(-n+2), .card-body table.table-sm > thead > tr > :nth-child(-n+2), .card-body table.table-sm > tbody > tr > :nth-child(-n+2) { display: none !important; }"
                    + ".detail-body table.table-sm > thead > tr > :nth-child(3), .detail-body table.table-sm > tbody > tr > :nth-child(3), .card-body table.table-sm > thead > tr > :nth-child(3), .card-body table.table-sm > tbody > tr > :nth-child(3) { width: 100% !important; }"
                    + ".detail-body table.table-sm > thead, .card-body table.table-sm > thead { display: none !important; }"
                    + ".card-toolbar.node-info li:nth-child(2) { display: none !important; }"
                    // the readable head of a scenario entry (title, severity / priority / area, steps, expected, actual, artifacts)
                    + ".komp-case { font-size: 13px; line-height: 1.5; }"
                    + ".komp-case .kc-title { font-size: 16px; font-weight: 700; margin: 2px 0 4px; }"
                    + ".komp-case .kc-meta { color: #555; margin-bottom: 6px; }"
                    + ".komp-case h6 { margin: 12px 0 3px; font-weight: 700; font-size: 13px; }"
                    + ".komp-case ol { margin: 0 0 0 20px; padding: 0; }"
                    + ".komp-case p { margin: 0 0 4px; }"
                    + ".komp-case .kc-art a img { max-width: 300px; max-height: 190px; border: 1px solid #ccc; margin: 3px 6px 3px 0; vertical-align: top; }"
                    + ".komp-case .kc-small { color: #777; font-size: 11px; margin-top: 10px; }"
                    // the API calls of an entry: one row per call in the order they happened; a click opens Payload and Response
                    + ".kapi { border: 1px solid #ddd; border-radius: 4px; margin-top: 6px; }"
                    + ".kapi .kapi-row { display: grid; grid-template-columns: 2.5em 3.5em 4.5em minmax(0, 1fr) 5em 5.5em; column-gap: 10px; align-items: baseline; padding: 4px 8px; }"
                    + ".kapi .kapi-head { background: #f1f3f5; font-weight: 700; }"
                    + ".kapi summary.kapi-row { cursor: pointer; list-style: none; border-top: 1px solid #eee; }"
                    + ".kapi summary.kapi-row::-webkit-details-marker { display: none; }"
                    + ".kapi summary.kapi-row:hover { background: #f8f9fa; }"
                    + ".kapi details[open] > summary.kapi-row { background: #eef3ff; }"
                    + ".kapi .ep { word-break: break-all !important; overflow-wrap: anywhere !important; }"
                    + ".kapi .ok { color: #2e7d32; font-weight: 700; } .kapi .warn { color: #ef6c00; font-weight: 700; } .kapi .bad { color: #c62828; font-weight: 700; }"
                    + ".kapi .kapi-body { padding: 6px 12px 10px 28px; background: #fcfcfc; }"
                    + ".kapi .kapi-sub { margin: 4px 0; }"
                    + ".kapi .kapi-sub > summary { cursor: pointer; font-weight: 700; padding: 2px 0; }"
                    + ".kapi pre { white-space: pre-wrap; word-break: break-all !important; overflow-wrap: anywhere !important; max-height: 340px; overflow: auto; background: #f6f8fa; border: 1px solid #e3e6ea; border-radius: 3px; padding: 6px 8px; margin: 4px 0; font-size: 11px; }"
                    // font 2 points smaller everywhere (zoom 0.875 = 14px -> 12px) and words are never broken in the middle (wrapping only at spaces)
                    + ".test-content > *, .test-list > * { zoom: 0.875; }"
                    + "body, td, th, p, span, h1, h2, h3, h4, h5, h6, div { word-break: normal !important; overflow-wrap: normal !important; hyphens: none !important; }"
                    // the table of scenarios that did not pass: every column but the title stays on ONE line, the title takes the rest of the width and wraps at spaces
                    + ".komp-scroll { overflow-x: auto; max-width: 100%; }"
                    + "table.komp-np { border-collapse: collapse; width: 100%; }"
                    + "table.komp-np th, table.komp-np td { white-space: nowrap; vertical-align: top; }"
                    + "table.komp-np th:nth-child(5), table.komp-np td:nth-child(5) { white-space: normal; min-width: 22em; width: 100%; }";

    private ExtentManager() {
    }

    public static synchronized ExtentReports get() {
        if (extent == null) {
            ExtentSparkReporter spark = new ExtentSparkReporter(ExecutionInfo.reportFile().toString());
            spark.config().setDocumentTitle(ExecutionInfo.PROJECT + " - " + ExecutionInfo.className());
            spark.config().setReportName(ExecutionInfo.testName() + " (" + ExecutionInfo.className() + ")");
            spark.config().setTheme(Theme.STANDARD);
            spark.config().setEncoding("UTF-8");
            spark.config().setCss(CSS);
            extent = new ExtentReports();
            extent.attachReporter(spark);
            extent.setSystemInfo("Project", ExecutionInfo.PROJECT);
            extent.setSystemInfo("Application", "KOMP Global");
            extent.setSystemInfo("Test class", ExecutionInfo.className());
            extent.setSystemInfo("Test suite", ExecutionInfo.testName());
            extent.setSystemInfo("Environment", ConfigManager.env());
            extent.setSystemInfo("Web address of the application", ConfigManager.baseUrl());
            extent.setSystemInfo("Run folder", ExecutionInfo.id());
            if (ExecutionInfo.isClientModule()) {
                extent.setSystemInfo("Client type", ConfigManager.get("client.type", "EOR"));
            } else {
                extent.setSystemInfo("Module", ExecutionInfo.module());
                extent.setSystemInfo("Test", ExecutionInfo.testName());
            }
            extent.setSystemInfo("OS", System.getProperty("os.name"));
            extent.setSystemInfo("Java", System.getProperty("java.version"));
            addLogins(extent);
        }
        return extent;
    }

    /**
     * The logins of the run, for whoever reads the report (test accounts of the sandbox). Left out while masking is on
     * (-Dmask.secrets=true, or a production run) and never allowed to break the report when a value is missing.
     */
    private static void addLogins(ExtentReports e) {
        if (SecretMasker.enabled()) {
            return;
        }
        if (!ExecutionInfo.isClientModule()) {
            // the brief of the other modules: never a password in a report. The e-mail of the account that ran the test is enough to know who it was.
            try {
                String module = ExecutionInfo.module();
                if (module.startsWith("Employee-AOR")) {
                    e.setSystemInfo("Signed in as (AOR user)", ConfigManager.aorEmail());
                } else if (module.startsWith("Employee-EOR")) {
                    e.setSystemInfo("Signed in as (EOR user)", ConfigManager.eorEmail());
                } else {
                    e.setSystemInfo("Signed in as (administrator)", ConfigManager.adminEmail());
                }
            } catch (RuntimeException ex) {
                // not configured: the report simply does not list it
            }
            return;
        }
        try {
            e.setSystemInfo("Admin login", ConfigManager.adminEmail() + " / " + ConfigManager.adminPassword());
            e.setSystemInfo("EOR login", ConfigManager.eorEmail() + " / " + ConfigManager.eorPassword());
            e.setSystemInfo("Client password (after first login)", ConfigManager.clientPassword());
        } catch (RuntimeException ex) {
            // a login is not configured for this environment: the report simply does not list it
        }
    }

    public static synchronized void flush() {
        if (extent != null) {
            extent.flush();
        }
    }

    /** Writes the HTML file and forgets the report, so the next test class starts its own. */
    public static synchronized void reset() {
        flush();
        extent = null;
    }
}
