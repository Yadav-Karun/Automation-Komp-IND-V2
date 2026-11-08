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
                    + ".node-attr { display: none !important; }";

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
                e.setSystemInfo("Signed in as (EOR user)", ConfigManager.eorEmail());
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
