package model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One EOR employee of a run: the row of the onboarding workbook (admin side) and everything the employee fills in on the candidate portal.
 * The password, Aadhaar and PAN are kept out of the run data file ({@link JsonIgnore}).
 */
public class EorEmployee {

    // ---- the workbook row
    public String firstName;
    public String middleName = "";
    public String lastName;
    /** "Male" or "Female". */
    public String gender;
    public String email;
    public String phone;
    /** "Fresher" or "Experienced". */
    public String experience;
    public String state;
    public String workingLocation;
    public String endClient;
    public String designation;
    public String jobDescription;

    // ---- candidate portal: personal
    public String fatherName;
    public String motherName;
    public String maritalStatus;
    public String spouseName = "";
    public String nationality = "India";
    public String bloodGroup;
    /** dd-MM-yyyy */
    public String dateOfBirth;
    public String physicallyHandicapped;

    // ---- contact
    public String alternateMobile;
    public String emergencyName;
    public String emergencyRelationship;
    public String emergencyNumber;
    public String addressCountry = "India";
    public String addressState;
    public String addressCity;
    public String postalCode;
    public String address;
    /** true: the box "Permanent address is the same as current address" is ticked; false: a separate permanent address is entered. */
    public boolean permanentSame = true;
    public String permanentState;
    public String permanentCity;
    public String permanentPostalCode;
    public String permanentAddress;

    // ---- bank
    public String accountHolder;
    public String bankName;
    public String accountNumber;
    public String ifsc;
    public String accountType;
    public String branchName;
    public String bankAddress;

    // ---- professional
    public String uan;
    public String esic;
    public String primarySkills;
    public String secondarySkills;
    public String language;
    public String proficiency;

    // ---- education
    public String educationLevel;
    public String educationInstitution;
    public String fieldOfStudy;
    public String university;
    public String modeOfEducation;
    public String passingYear;
    public String certificateName;
    public String certificateInstitution;
    public String certificateIssueDate;

    // ---- identification
    public String aadhaar;
    public String pan;
    public String drivingLicence;
    public String passport;

    // ---- previous employment (only an experienced employee)
    public String previousEmployer;
    public String previousDesignation;
    /** dd-MM-yyyy */
    public String previousFrom;
    public String previousTo;
    public String previousReasonForLeaving;
    public String previousResponsibilities;

    /** The temporary password from the "Candidate Login" mail (never written to the run data file). */
    @JsonIgnore
    public String password;

    // ---- progress of this employee in the run (a later step is skipped for an employee whose earlier step failed)
    public boolean loiAccepted;
    public boolean profileFilled;
    public boolean verified;
    public boolean offerSent;
    public boolean offerAccepted;
    public boolean joined;
    public String failedAt = "";
    /** Read from the profile after joining (for the checks of the later cases). */
    public String employeeCode = "";
    /** dd-MM-yyyy; set when the joining date of the profile was changed (the contract shows it). */
    public String joiningDate = "";
    public String candidateId = "";

    public String fullName() {
        return (firstName + " " + (middleName == null || middleName.isBlank() ? "" : middleName + " ") + lastName).trim();
    }

    public boolean experienced() {
        return "Experienced".equalsIgnoreCase(experience);
    }

    public static String maskAadhaar(String aadhaar) {
        return aadhaar == null || aadhaar.length() < 4 ? "(not set)" : "XXXX XXXX " + aadhaar.substring(aadhaar.length() - 4);
    }

    public static String maskPan(String pan) {
        return pan == null || pan.length() < 2 ? "(not set)" : pan.charAt(0) + "********" + pan.charAt(pan.length() - 1);
    }
}
