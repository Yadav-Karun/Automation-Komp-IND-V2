package model;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything ONE run of the AOR happy flow shares (spec section 13: "a single run context"): the run id and date, the client and project, the employees, the two
 * names that are the same for every employee, the payroll session that the Payee Sheet upload created, and the files the run produced.
 * The same employees are used for the employee upload and for the Payee Sheet: nothing is generated twice.
 */
public class AorRun {

    /** e.g. AOR-20261110-2215: one run, one id; it prefixes the files this run downloads. */
    public String runId;
    /** The business date of the run, ISO yyyy-MM-dd: captured ONCE and used for every date of the run. */
    public String runDate;
    public String client;
    public String project;
    public String areaManagerName;
    public String supervisorName;
    public List<AorEmployee> employees = new ArrayList<>();

    /** The payroll session created by the Payee Sheet upload, e.g. PAYIN-101126101126-0001 (filled in the finance case). */
    public String sessionId;

    /** The workbook that was uploaded for the employees / the Payee Sheet, and the files that were downloaded (names only). */
    public String employeeWorkbook;
    public String payeeWorkbook;
    public List<String> downloads = new ArrayList<>();

    /** The numbers the application showed after the employee upload (for the report). */
    public String uploadResultText;
}
