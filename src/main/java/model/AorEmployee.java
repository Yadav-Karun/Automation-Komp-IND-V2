package model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One AOR employee (an "agent") of a run: every value of one row of the AOR bulk-upload workbook, plus the pay data of the Payee Sheet.
 * The Aadhaar and PAN numbers are supplied by the QA owner ({@code config/aor-identities.local.txt}); they are never written to the run data file and are shown
 * in reports only in a masked form.
 */
public class AorEmployee {

    public String state;
    public String hubLocation;
    public String deliveryPartnerId;
    public String companyEmployeeId;
    public String name;
    public String fatherName;
    public String contactNumber;
    /** ISO yyyy-MM-dd. */
    public String onboardingDate;
    public String joiningDate;
    public String birthDate;
    public String hubName;
    public String personalEmail;
    public String address;
    public String bankName;
    public String accountNumber;
    public String ifscCode;
    public String drivingLicence;
    public String vehicleInsurance;
    public String managerContactNumber;

    /** The Aadhaar / PAN number from the pool of the QA owner (kept out of the run data file). */
    @JsonIgnore
    public String aadhaar;
    @JsonIgnore
    public String pan;

    /** Final payout of the Payee Sheet (a whole amount); set when the Payee Sheet is prepared. */
    public int finalPayout;

    /** What the report may show of an Aadhaar number: only the last four digits. */
    public static String maskAadhaar(String aadhaar) {
        return aadhaar == null || aadhaar.length() < 4 ? "(not set)" : "XXXX XXXX " + aadhaar.substring(aadhaar.length() - 4);
    }

    /** What the report may show of a PAN number: the first and the last character. */
    public static String maskPan(String pan) {
        return pan == null || pan.length() < 2 ? "(not set)" : pan.charAt(0) + "********" + pan.charAt(pan.length() - 1);
    }
}
