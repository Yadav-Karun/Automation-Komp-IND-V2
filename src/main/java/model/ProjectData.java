package model;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * All values generated and submitted for ONE project (Project Management module). Fields are public so the report and the JSON copy can read them.
 * AOR and EOR use different fields: {@code referenceId} is AOR only; {@code endClientName}, {@code city} and the end-client location are EOR only.
 */
public class ProjectData {

    /** "AOR" or "EOR". */
    public String type;
    /** Seed of the random choices (client, state, city, date ...): the same seed + the same options reproduce the same data (-Dproject.seed). */
    public long seed;

    public String clientName;
    public String projectName;
    public String projectCode;

    /** AOR only, e.g. BLEACH-IND-0042. */
    public String referenceId;
    /** EOR only, a real company name such as Microsoft. */
    public String endClientName;

    /** Project start date as entered, ISO yyyy-MM-dd (always before 1 January 2025). */
    public String startDate;
    /** Always empty: the happy flow leaves the end date blank. */
    public String endDate = "";

    /** Project Location (AOR and EOR). AOR has a state only, EOR a state and a matching city. */
    public String country = "India";
    public String state;
    public String city;

    /** End Client Location (EOR only). */
    public String endClientCountry;
    public String endClientState;
    public String endClientCity;

    /** What the listing showed after the project was searched by its exact name (filled by the test). */
    public String listedStatus;

    /** The start date the way the project listings print it (dd-MM-yyyy, e.g. 15-03-2024). */
    public String startDateInList() {
        return startDate == null ? null : LocalDate.parse(startDate).format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isAor() {
        return "AOR".equals(type);
    }
}
