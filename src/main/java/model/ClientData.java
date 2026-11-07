package model;

/** All values submitted during one client-onboarding run. Fields are public so negative tests can mutate copies. */
public class ClientData {

    public String runId;

    // registration (admin side)
    public String legalName;
    public String clientType;
    public String country;
    public String pocName;
    public String email;
    public String mobile;

    // logins of the client, shown in the report header (never written to test-data.json)
    /** Temporary password from the welcome e-mail (null until the e-mail was read). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String temporaryPassword;
    /** The password the client signs in with now (null while unknown). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String password;

    // organization details (client side)
    public String yearsInIndustry;
    public String website;
    public String description;
    public String pincode;
    public String city;
    public String address;
    public String companyType;
    public String cin;
    public String gst;
    public String cinDocPath;
    public String taxDocPath;

    // values the application itself filled in or generated during the run (kept so the profile can be checked against them)
    /** State shown in the form after the pincode was entered (auto-filled by the app; its correctness is not asserted). */
    public String selectedState;
    /** City actually selected in the form. */
    public String selectedCity;
    /** MSA id shown in the EOR MSA list. */
    public String msaId;

    // points of contact
    public Contact business = new Contact();
    public Contact finance = new Contact();
    public Contact hr = new Contact();

    public static class Contact {
        public String name;
        public String designation;
        public String email;
        public String phone;
    }
}
