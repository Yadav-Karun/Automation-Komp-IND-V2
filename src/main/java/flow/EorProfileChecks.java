package flow;

import java.util.List;
import java.util.Locale;

import model.EorEmployee;
import model.EorRun;
import pages.EorProfilePage;
import reporting.ValueChecks;

/**
 * The checks of the profile of an EOR employee (Employees &gt; EOR &gt; eye button), tab by tab: every value the employee typed into the candidate portal and every value
 * of the workbook row must be shown by the profile exactly. One row of the report table per value (plain wording: "should be X but the page shows Y").
 */
final class EorProfileChecks {

    private static final String DASH = "—";

    private EorProfileChecks() {
    }

    // ------------------------------------------------------------------ header

    static void header(EorRun run, EorEmployee e, List<String> lines, ValueChecks c) {
        String all = String.join(" | ", lines);
        c.check("Top of the profile", "Name", all.contains(e.fullName()), e.fullName(), shortText(all));
        c.check("Top of the profile", "Status \"Active\"", all.toLowerCase(Locale.ROOT).contains("active"), "Active", shortText(all));
        c.check("Top of the profile", "Designation", all.toUpperCase(Locale.ROOT).contains(e.designation.toUpperCase(Locale.ROOT)), e.designation, shortText(all));
        c.check("Top of the profile", "E-mail", all.toLowerCase(Locale.ROOT).contains(e.email.toLowerCase(Locale.ROOT)), e.email, shortText(all));
        c.check("Top of the profile", "Phone number", all.contains(e.phone), e.phone, shortText(all));
        c.check("Top of the profile", "Client", all.contains(run.client), run.client, shortText(all));
        c.check("Top of the profile", "Project", all.contains(run.project), run.project, shortText(all));
        c.check("Top of the profile", "Employee code (EMP_...)", all.matches("(?s).*EMP_[0-9]+.*"), "a code like EMP_0030", shortText(all));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("EMP_[0-9]+").matcher(all);
        if (m.find()) {
            e.employeeCode = m.group();
        }
    }

    // ------------------------------------------------------------------ Personal & Contact

    static void personal(EorEmployee e, List<String[]> p, ValueChecks c) {
        String w = "Personal & Contact > Personal Information";
        eq(c, w, "First Name", e.firstName, EorProfilePage.value(p, "Personal Information", "First Name", 0));
        eq(c, w, "Middle Name", e.middleName, EorProfilePage.value(p, "Personal Information", "Middle Name", 0));
        eq(c, w, "Last Name", e.lastName, EorProfilePage.value(p, "Personal Information", "Last Name", 0));
        eq(c, w, "Father Name", e.fatherName, EorProfilePage.value(p, "Personal Information", "Father Name", 0));
        eq(c, w, "Mother Name", e.motherName, EorProfilePage.value(p, "Personal Information", "Mother Name", 0));
        eq(c, w, "Date of birth", e.dateOfBirth, EorProfilePage.value(p, "Personal Information", "Emp Dob", 0));
        eq(c, w, "Gender", e.gender, EorProfilePage.value(p, "Personal Information", "Gender", 0));
        eq(c, w, "Nationality", "IN", EorProfilePage.value(p, "Personal Information", "Nationality", 0));
        eq(c, w, "Marital status", e.maritalStatus, EorProfilePage.value(p, "Personal Information", "Marrital Status", 0));
        eq(c, w, "Blood group", e.bloodGroup, EorProfilePage.value(p, "Personal Information", "Blood Group", 0));
        eq(c, w, "Physically handicapped", e.physicallyHandicapped, EorProfilePage.value(p, "Personal Information", "Physically Handicaped", 0));
        if ("Yes".equalsIgnoreCase(e.physicallyHandicapped)) {
            eq(c, w, "Disability certificate", "disability.png", EorProfilePage.value(p, "Personal Information", "Disability Certificate", 0));
        }
        String cw = "Personal & Contact > Contact Information";
        eq(c, cw, "Personal e-mail", e.email, EorProfilePage.value(p, "Contact Information", "Personal Email", 0));
        eq(c, cw, "Mobile number", e.phone, EorProfilePage.value(p, "Contact Information", "Mobile No", 0));
        eq(c, cw, "Alternate mobile number", e.alternateMobile, EorProfilePage.value(p, "Contact Information", "Alternate Mobile No", 0));
        address(c, "Personal & Contact > Current Address", p, "Current Address", e.addressState, e.addressCity, e.address, e.postalCode);
        if (e.permanentSame) {
            address(c, "Personal & Contact > Permanent Address (same as current)", p, "Permanent Address", e.addressState, e.addressCity, e.address, e.postalCode);
        } else {
            address(c, "Personal & Contact > Permanent Address (entered separately)", p, "Permanent Address", e.permanentState, e.permanentCity, e.permanentAddress, e.permanentPostalCode);
        }
        String ew = "Personal & Contact > Emergency Contact";
        eq(c, ew, "Name", e.emergencyName, EorProfilePage.value(p, "Emergency Contact", "Name", 0));
        eq(c, ew, "Relationship", e.emergencyRelationship, EorProfilePage.value(p, "Emergency Contact", "Relationship", 0));
        eq(c, ew, "Contact number", e.emergencyNumber, EorProfilePage.value(p, "Emergency Contact", "Contact No", 0));
        String ed = "Personal & Contact > Education";
        eq(c, ed, "Educational level", e.educationLevel, EorProfilePage.value(p, "Education", "Educational Level", 0));
        eq(c, ed, "Institution name", e.educationInstitution, EorProfilePage.value(p, "Education", "Institution Name", 0));
        eq(c, ed, "Field of study", e.fieldOfStudy, EorProfilePage.value(p, "Education", "Field Of Study", 0));
        eq(c, ed, "Passing year", e.passingYear, EorProfilePage.value(p, "Education", "Passing Year", 0));
        eq(c, ed, "Mode of education", e.modeOfEducation, EorProfilePage.value(p, "Education", "Mode Of Education", 0));
        eq(c, ed, "University name", e.university, EorProfilePage.value(p, "Education", "University Name", 0));
        String cert = "Personal & Contact > Additional Certifications";
        eq(c, cert, "Certificate name", e.certificateName, EorProfilePage.value(p, "Certifications", "Certificate Name", 0));
        eq(c, cert, "Issue date", e.certificateIssueDate, EorProfilePage.value(p, "Certifications", "Issue Date", 0));
        eq(c, cert, "Institution name", e.certificateInstitution, EorProfilePage.value(p, "Certifications", "Institution Name", 0));
    }

    private static void address(ValueChecks c, String where, List<String[]> p, String heading, String state, String city, String address, String pin) {
        eq(c, where, "Country", "IN", EorProfilePage.value(p, heading, "Country", 0));
        eq(c, where, "State", state, EorProfilePage.value(p, heading, "State", 0));
        eq(c, where, "City", city, EorProfilePage.value(p, heading, "City", 0));
        eq(c, where, "Address", address, EorProfilePage.value(p, heading, "Address", 0));
        eq(c, where, "Pincode", pin, EorProfilePage.value(p, heading, "Pincode", 0));
    }

    // ------------------------------------------------------------------ Professional

    static void professional(EorRun run, EorEmployee e, List<String[]> p, ValueChecks c) {
        String w = "Professional > Employment Information";
        String h = "Employment Information";
        eq(c, w, "Client", run.client, EorProfilePage.value(p, h, "Client", 0));
        eq(c, w, "Work experience", e.experience, EorProfilePage.value(p, h, "Work Experience", 0));
        sheetEq(c, w, "Department", run.sheet.getOrDefault("department", ""), EorProfilePage.value(p, h, "Department", 0));
        eq(c, w, "Designation", e.designation, EorProfilePage.value(p, h, "Designation", 0));
        sheetEq(c, w, "Employment type", run.sheet.getOrDefault("employment type", ""), EorProfilePage.value(p, h, "Emp Type", 0));
        sheetEq(c, w, "Joining date", run.sheet.getOrDefault("joining date", "").replace('/', '-'), EorProfilePage.value(p, h, "Joining Date", 0));
        eq(c, w, "Work country", "India", EorProfilePage.value(p, h, "Work Country", 0));
        eq(c, w, "Work state", e.state, EorProfilePage.value(p, h, "Work State", 0));
        sheetEq(c, w, "Reporting manager", run.sheet.getOrDefault("manager name", ""), EorProfilePage.value(p, h, "Reporting Manager", 0));
        sheetEq(c, w, "Manager e-mail", run.sheet.getOrDefault("manager email", ""), EorProfilePage.value(p, h, "Manager Email", 0));
        String s = "Professional > Professional Skills";
        eq(c, s, "Primary skills", e.primarySkills, EorProfilePage.value(p, "Professional Skills", "Primary Skills", 0));
        eq(c, s, "Secondary skills", e.secondarySkills, EorProfilePage.value(p, "Professional Skills", "Secondary Skills", 0));
        eq(c, "Professional > Languages", "Proficiency", e.proficiency, EorProfilePage.value(p, "Languages", "Proficiency", 0));
        eq(c, "Professional > Languages", "Language known", e.language, EorProfilePage.value(p, "Languages", "Language Known", 0));
        String pj = "Professional > Project & Job Details";
        String project = EorProfilePage.value(p, "Project & Job", "Project Id", 0);
        c.check(pj, "Project", project != null && project.contains(run.project), run.project, show(project));
        eq(c, pj, "End client", e.endClient, EorProfilePage.value(p, "Project & Job", "End Client Id", 0));
        if (e.experienced()) {
            String we = "Professional > Work Experience";
            eq(c, we, "Previous employer", e.previousEmployer, EorProfilePage.value(p, "Work Experience", "Previous Employer", 0));
            eq(c, we, "Designation", e.previousDesignation, EorProfilePage.value(p, "Work Experience", "Designation", 0));
            eq(c, we, "Joining date (the first day of the month that was chosen)", "01-" + e.previousFrom.substring(3), EorProfilePage.value(p, "Work Experience", "Joining Date", 0));
            eq(c, we, "Last working date", e.previousTo, EorProfilePage.value(p, "Work Experience", "Last Working Date", 0));
            eq(c, we, "Key responsibilities", e.previousResponsibilities, EorProfilePage.value(p, "Work Experience", "Key Responsibilities", 0));
        }
    }

    // ------------------------------------------------------------------ Statutory Details

    static void statutory(EorEmployee e, List<String[]> p, ValueChecks c) {
        String w = "Statutory Details > Bank & Statutory Details";
        String h = "Bank & Statutory";
        eq(c, w, "Account holder name", e.accountHolder, EorProfilePage.value(p, h, "Account Holder Name", 0));
        eq(c, w, "Bank name", e.bankName, EorProfilePage.value(p, h, "Bank Name", 0));
        eq(c, w, "Account number", e.accountNumber, EorProfilePage.value(p, h, "Account No", 0));
        eq(c, w, "Confirm account number", e.accountNumber, EorProfilePage.value(p, h, "Confirm Account No", 0));
        eq(c, w, "Branch name", e.branchName, EorProfilePage.value(p, h, "Branch Name", 0));
        eq(c, w, "Bank address", e.bankAddress, EorProfilePage.value(p, h, "Bank Address", 0));
        eq(c, w, "IFSC code", e.ifsc, EorProfilePage.value(p, h, "Ifsc Code", 0));
        eq(c, w, "Account type", e.accountType, EorProfilePage.value(p, h, "Account Type", 0));
        eq(c, w, "UAN number", e.uan, EorProfilePage.value(p, h, "Uan Number", 0));
        eq(c, w, "ESIC number", e.esic, EorProfilePage.value(p, h, "Esic Number", 0));
        String cheque = EorProfilePage.value(p, h, "Cancelled Cheque", 0);
        c.check(w, "Cancelled cheque is attached", cheque != null && !cheque.isBlank() && !DASH.equals(cheque), "a file name", show(cheque));
    }

    // ------------------------------------------------------------------ Documents

    static void documents(EorEmployee e, List<String[]> p, ValueChecks c) {
        String w = "Documents > Identification";
        if (e.aadhaar != null) {
            digits(c, w, "Aadhaar number", e.aadhaar, EorProfilePage.value(p, "Aadhaar", "Aadhar No", 0));
        }
        eq(c, w, "Aadhaar card (front)", "myaadhaar-Front.pdf", EorProfilePage.value(p, "Aadhaar", "Upload Aadhaar Card (Front)", 0));
        eq(c, w, "Aadhaar card (back)", "myaadhaar-Back.pdf", EorProfilePage.value(p, "Aadhaar", "Upload Aadhaar Card (Back)", 0));
        if (e.pan != null) {
            eq(c, w, "PAN number", e.pan, EorProfilePage.value(p, "PAN", "Pan No", 0));
        }
        eq(c, w, "PAN card", "Pancard.pdf", EorProfilePage.value(p, "PAN", "Upload Pancard", 0));
        eq(c, w, "Driving licence number", e.drivingLicence, EorProfilePage.value(p, "Driving Licence", "Licence No", 0));
        eq(c, w, "Driving licence document", "drivingPdfPath.png", EorProfilePage.value(p, "Driving Licence", "Upload Driving Licence", 0));
        eq(c, w, "Passport number", e.passport, EorProfilePage.value(p, "Passport", "Passport No", 0));
        eq(c, w, "Passport document", "pasportPdfPath.png", EorProfilePage.value(p, "Passport", "Upload Passport", 0));
    }

    /** The Letter of Intent and the Offer Letter are listed and accepted. */
    static void employmentDocuments(String bodyText, ValueChecks c) {
        String t = bodyText.replaceAll("\\s+", " ");
        c.check("Employment Documents", "Letter of Intent is listed", t.contains("Letter of Intent"), "Letter of Intent (LOI)", shortText(t));
        c.check("Employment Documents", "Offer Letter is listed", t.contains("Offer Letter"), "Offer Letter", shortText(t));
        c.check("Employment Documents", "Both documents are accepted", countOf(t.toLowerCase(Locale.ROOT), "accepted") >= 2, "\"Accepted\" twice", countOf(t.toLowerCase(Locale.ROOT), "accepted") + " times \"Accepted\"");
    }

    // ------------------------------------------------------------------ helpers

    /** The page must show the expected text (spaces, comma spacing and upper / lower case are not compared; an empty value is shown as a dash). */
    static void eq(ValueChecks c, String where, String label, String expected, String shown) {
        String exp = expected == null ? "" : expected;
        String got = shown == null ? null : (DASH.equals(shown.trim()) || "-".equals(shown.trim()) ? "" : shown);
        boolean ok = got != null && norm(exp).equals(norm(got));
        c.check(where, label, ok, exp.isEmpty() ? "(empty)" : exp, got == null ? "(not found on the page)" : got.isEmpty() ? "(empty)" : got);
    }

    /** A value of the template row (department, employment type, joining date, manager ...): checked when the run knows it. */
    private static void sheetEq(ValueChecks c, String where, String label, String expected, String shown) {
        if (!expected.isBlank()) {
            eq(c, where, label, expected, shown);
        }
    }

    private static void digits(ValueChecks c, String where, String label, String expected, String shown) {
        String got = shown == null ? "" : shown.replaceAll("[^0-9]", "");
        c.check(where, label, got.equals(expected.replaceAll("[^0-9]", "")), expected, shown == null ? "(not found on the page)" : shown);
    }

    static String norm(String s) {
        return s.replaceAll("\\s*,\\s*", ",").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String show(String s) {
        return s == null ? "(not found on the page)" : s;
    }

    private static String shortText(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    private static int countOf(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }
}
