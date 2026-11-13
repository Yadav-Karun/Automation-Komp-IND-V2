package model;

import java.util.ArrayList;
import java.util.List;

/** Everything ONE run of the EOR employee happy flow shares: the run id and date, the client / project of the workbook row, the employees, the workbook. */
public class EorRun {

    /** e.g. EOR-20261112-1130 */
    public String runId;
    /** ISO yyyy-MM-dd */
    public String runDate;
    public String client = "";
    public String project = "";
    public List<EorEmployee> employees = new ArrayList<>();
    /** The values of the template row that every employee keeps (header in lower case -> value): department, employment type, joining date, manager ... */
    public java.util.Map<String, String> sheet = new java.util.LinkedHashMap<>();
    /** The workbook that was uploaded (file name inside the run folder "uploads"). */
    public String workbook;
    /** What the application answered after the upload (for the report). */
    public String uploadResultText = "";
}
