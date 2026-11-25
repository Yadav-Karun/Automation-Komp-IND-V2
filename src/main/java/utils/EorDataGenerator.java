package utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import model.EorEmployee;
import model.EorRun;

/**
 * Random but sensible data for the EOR employee happy flow (the rules of the QA owner's earlier project, "KOMP IND - Java"):
 * a name and a yopmail address {@code <firstnamelastname>.<M|F>@yopmail.com} (the letter tells the gender), a working location that belongs to the state,
 * an experience (Fresher / Experienced; chosen at random, 50% each, like gender and disability),
 * and the personal, contact, bank, professional, education and identification values that the employee types into the candidate portal.
 * Every e-mail address ever used is kept in {@code testdata/eor-used-emails.txt} and is never generated again (-Deor.history.file=... overrides the file).
 */
public final class EorDataGenerator {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss a");

    private static final String[] MALE = {"Aarav", "Vihaan", "Arjun", "Rohan", "Karan", "Rahul", "Aditya", "Nikhil", "Sameer", "Vikram", "Manish", "Deepak", "Suresh", "Pranav", "Harsh",
            "Kunal", "Varun", "Gaurav", "Ishaan", "Naveen", "Tarun", "Yash", "Ajay", "Mohit", "Sanjay"};
    private static final String[] FEMALE = {"Ananya", "Diya", "Isha", "Kavya", "Meera", "Neha", "Priya", "Riya", "Sneha", "Tanvi", "Pooja", "Divya", "Shreya", "Anjali", "Nisha",
            "Aditi", "Bhavna", "Komal", "Swati", "Rekha", "Simran", "Payal", "Jyoti", "Kriti", "Lavanya"};
    private static final String[] LAST = {"Sharma", "Verma", "Gupta", "Mehta", "Iyer", "Nair", "Reddy", "Patel", "Singh", "Kapoor", "Joshi", "Bose", "Das", "Chauhan", "Malhotra",
            "Bhatt", "Pillai", "Menon", "Desai", "Kulkarni", "Saxena", "Tiwari", "Agarwal", "Rao", "Banerjee"};

    /** State -> a working location that is offered for it (the places of the QA owner's workbook). */
    private static final Map<String, String> STATES = new java.util.LinkedHashMap<>();
    static {
        STATES.put("Gujarat", "Ahmedabad");
        STATES.put("Maharashtra", "Mumbai");
        STATES.put("Rajasthan", "Jaipur");
        STATES.put("Delhi", "New Delhi");
        STATES.put("Karnataka", "Ramanagara");
        STATES.put("Tamil Nadu", "Chennai");
        STATES.put("Telangana", "Hyderabad");
        STATES.put("Uttar Pradesh", "Lucknow");
        STATES.put("Madhya Pradesh", "Bhopal");
        STATES.put("West Bengal", "Kolkata");
        STATES.put("Andhra Pradesh", "Visakhapatnam");
        STATES.put("Haryana", "Karnal");
        STATES.put("Punjab", "Amritsar");
        STATES.put("Kerala", "Tiruvalla");
        STATES.put("Bihar", "Patna");
        STATES.put("Odisha", "Bhubaneswar");
    }

    private static final String[] DESIGNATIONS = {"ASSOCIATE", "EXECUTIVE", "OFFICER", "COORDINATOR", "SPECIALIST", "ANALYST", "ADMINISTRATOR", "TEAM LEAD", "SENIOR EXECUTIVE",
            "SENIOR OFFICER", "SENIOR ASSOCIATE", "SUPERVISOR", "ASSISTANT MANAGER", "DEPUTY MANAGER", "MANAGER"};
    private static final String[] JOBS = {"Develop and maintain Java based applications.", "Design and execute automated test cases for web applications.",
            "Develop backend services and REST APIs.", "Build and maintain automated testing frameworks.", "Design and develop responsive web applications.",
            "Develop and maintain full stack enterprise applications.", "Develop automation scripts for application testing.",
            "Analyze application requirements and develop software solutions.", "Perform functional and regression testing of enterprise applications.",
            "Develop APIs and integrate third party services."};
    private static final String[] END_CLIENTS = {"Northwind Traders", "Contoso Logistics", "Fabrikam Retail", "Tailspin Systems", "Litware Software", "Adventure Works", "Wingtip Services",
            "Proseware Analytics", "Lucerne Publishing", "Margie Travel"};
    private static final String[] BANKS = {"HDFC Bank", "ICICI Bank", "State Bank of India", "Axis Bank", "Kotak Mahindra Bank", "IndusInd Bank", "Punjab National Bank", "Bank of Baroda"};
    private static final String[] BLOOD = {"A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"};
    private static final String[] INSTITUTIONS = {"ABC Institute of Technology", "Delhi Institute of Technology", "National Institute of Engineering", "Global Institute of Technology",
            "Modern College of Engineering"};
    private static final String[] FIELDS = {"Computer Science", "Information Technology", "Computer Engineering", "Electronics and Communication", "Data Science", "Software Engineering"};
    private static final String[] UNIVERSITIES = {"University of Delhi", "Amity University", "Anna University", "Maharshi Dayanand University", "Dr. A.P.J. Abdul Kalam Technical University"};
    private static final String[] CERTIFICATES = {"Java Programming Certificate", "Selenium Automation Certificate", "Python Programming Certificate", "SQL Certification",
            "AWS Cloud Practitioner", "Data Analytics Certificate"};
    private static final String[] CERT_INSTITUTIONS = {"Udemy", "Coursera", "Great Learning", "Simplilearn", "LinkedIn Learning"};
    private static final String[] PREVIOUS_EMPLOYERS = {"Infosys Limited", "Tata Consultancy Services", "Wipro Limited", "HCL Technologies", "Tech Mahindra", "Cognizant", "Capgemini India"};
    private static final String[] LEAVING = {"Better career opportunity", "Career growth", "Relocation", "Higher studies"};

    private EorDataGenerator() {
    }

    private static Path file() {
        return Paths.get(ConfigManager.get("eor.history.file", "testdata/eor-used-emails.txt"));
    }

    private static Set<String> usedEmails() {
        Set<String> out = new HashSet<>();
        try {
            if (Files.isRegularFile(file())) {
                for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                    if (!line.startsWith("#") && !line.isBlank()) {
                        out.add(line.split("\\|", -1)[0].trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("The list of used EOR e-mail addresses " + file() + " cannot be read: " + e.getMessage(), e);
        }
        return out;
    }

    /** Books the e-mail addresses of a run (line: e-mail | run id | date time). */
    public static void book(EorRun run) {
        try {
            if (file().getParent() != null) {
                Files.createDirectories(file().getParent());
            }
            StringBuilder sb = new StringBuilder();
            if (!Files.isRegularFile(file())) {
                sb.append("# EOR e-mail addresses used by the EOR happy flow: e-mail | run id | date time (an address in this file is never generated again)\n");
            }
            for (EorEmployee e : run.employees) {
                sb.append(e.email).append(" | ").append(run.runId).append(" | ").append(LocalDateTime.now().format(STAMP)).append('\n');
            }
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("The list of used EOR e-mail addresses " + file() + " cannot be written: " + e.getMessage(), e);
        }
    }

    /**
     * The login address of a new employee, all lower case. Default: a yopmail address. With {@code -Dmail.provider=imap} (user, 2026-11-17): the next free VARIANT of the QA mailbox
     * ({@code imap.address.base}, donotreply.qamail@gmail.com): {@code donotreply.qamail+1@gmail.com}, {@code +2}, ... the first number whose address is not yet in the list of used
     * addresses ({@code testdata/eor-used-emails.txt}); every mail to a variant lands in the one mailbox. Only the EOR employees use these; everything else stays on yopmail.
     */
    static String mailAddress(String name, boolean male, Set<String> used) {
        if ("imap".equalsIgnoreCase(ConfigManager.get("mail.provider", "yopmail"))) {
            String base = ConfigManager.get("imap.address.base");
            if (base == null || !base.contains("@")) {
                throw new IllegalStateException("-Dmail.provider=imap needs imap.address.base (the address of the mailbox, donotreply.qamail@gmail.com) in config/secrets.local.properties.");
            }
            int at = base.indexOf('@');
            String local = base.substring(0, at).trim().toLowerCase(Locale.ROOT);
            String domain = base.substring(at).trim().toLowerCase(Locale.ROOT);
            for (int n = 1; n < 100000; n++) {
                String candidate = local + "+" + n + domain;
                if (!used.contains(candidate)) {
                    return candidate;
                }
            }
            throw new IllegalStateException("No unused variant of " + base + " is left.");
        }
        return name.toLowerCase(Locale.ROOT) + "." + (male ? "m" : "f") + "@yopmail.com";
    }

    public static EorRun newRun(int count, long seed) {
        EorRun run = new EorRun();
        LocalDateTime now = LocalDateTime.now();
        run.runId = "EOR-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        run.runDate = LocalDate.now().toString();
        Random r = new Random(seed);
        Set<String> used = usedEmails();
        for (int i = 0; i < count; i++) {
            EorEmployee e = new EorEmployee();
            boolean male = r.nextBoolean();
            e.gender = male ? "Male" : "Female";
            while (true) {
                e.firstName = pick(r, male ? MALE : FEMALE);
                e.lastName = pick(r, LAST);
                e.email = mailAddress(e.firstName + e.lastName, male, used);
                if (used.add(e.email)) {
                    break;
                }
                e.lastName = pick(r, LAST) + pick(r, new String[] {"A", "B", "C", "D", "E", "F"}); // same name again: add a letter
                e.email = mailAddress(e.firstName + e.lastName, male, used);
                if (used.add(e.email)) {
                    break;
                }
            }
            e.middleName = r.nextInt(100) < 20 ? pick(r, male ? MALE : FEMALE) : "";
            e.phone = (6 + r.nextInt(4)) + String.format("%09d", r.nextInt(1_000_000_000));
            e.experience = r.nextBoolean() ? "Fresher" : "Experienced"; // 50% each (user, 2026-11-13: one employee per run, chosen at random like gender and disability)
            String[] states = STATES.keySet().toArray(new String[0]);
            e.state = states[r.nextInt(states.length)];
            e.workingLocation = STATES.get(e.state);
            e.endClient = pick(r, END_CLIENTS);
            e.designation = pick(r, DESIGNATIONS);
            e.jobDescription = pick(r, JOBS);
            personal(e, r);
            contact(e, r);
            e.permanentSame = r.nextBoolean(); // 50%: either "same as current address" is ticked or a separate permanent address is entered
            if (!e.permanentSame) {
                e.permanentState = EorDropdownOptions.pick(r, "State", STATES.keySet().toArray(new String[0]));
                e.permanentCity = STATES.getOrDefault(e.permanentState, "");
                e.permanentPostalCode = String.valueOf(100000 + r.nextInt(900000));
                e.permanentAddress = (10 + r.nextInt(900)) + ", " + pick(r, new String[] {"Hill Road", "River Side", "Temple Street", "College Road"});
            }
            bank(e, r);
            professional(e, r);
            education(e, r);
            identification(e, r);
            if (e.experienced()) {
                previousEmployment(e, r);
            }
            run.employees.add(e);
        }
        return run;
    }

    private static void personal(EorEmployee e, Random r) {
        e.fatherName = pick(r, MALE) + " " + e.lastName;
        e.motherName = pick(r, FEMALE) + " " + e.lastName;
        // every list of the candidate portal: all stored entries, the same chance for each (user, 2026-11-18); the fall-back pools are used until the entries are stored
        e.maritalStatus = r.nextBoolean() ? "Married" : "Unmarried"; // user, 2026-11-19: only Married or Unmarried (the list also offers "Other", which the profile later refuses to save)
        e.spouseName = pick(r, "Male".equals(e.gender) ? FEMALE : MALE) + " " + e.lastName; // used when the status is "Married" (or the relationship is "Spouse")
        e.bloodGroup = EorDropdownOptions.pick(r, "Blood Group", BLOOD);
        e.dateOfBirth = LocalDate.of(1990 + r.nextInt(14), 1 + r.nextInt(12), 1 + r.nextInt(28)).format(DMY);
        e.physicallyHandicapped = EorDropdownOptions.pick(r, "Physically Handicapped", new String[] {"Yes", "No"});
    }

    private static void contact(EorEmployee e, Random r) {
        e.alternateMobile = (6 + r.nextInt(4)) + String.format("%09d", r.nextInt(1_000_000_000));
        e.emergencyRelationship = EorDropdownOptions.pick(r, "Relationship", "Married".equals(e.maritalStatus) ? new String[] {"Father", "Mother", "Spouse", "Brother", "Sister"} : new String[] {"Father", "Mother", "Sister"});
        switch (e.emergencyRelationship) {
            case "Father": e.emergencyName = e.fatherName; break;
            case "Mother": e.emergencyName = e.motherName; break;
            case "Spouse": e.emergencyName = e.spouseName; break;
            default: e.emergencyName = pick(r, r.nextBoolean() ? MALE : FEMALE) + " " + e.lastName;
        }
        e.emergencyNumber = (6 + r.nextInt(4)) + String.format("%09d", r.nextInt(1_000_000_000));
        e.addressState = EorDropdownOptions.pick(r, "State", STATES.keySet().toArray(new String[0]));
        e.addressCity = STATES.getOrDefault(e.addressState, ""); // the portal page replaces it by a city chosen at random from what the portal offers for the state
        e.postalCode = String.valueOf(100000 + r.nextInt(900000));
        e.address = (10 + r.nextInt(900)) + ", " + pick(r, new String[] {"Park Street", "Lake Road", "Station Road", "Market Lane", "Garden Avenue"});
    }

    private static void bank(EorEmployee e, Random r) {
        e.accountHolder = e.fullName();
        e.bankName = pick(r, BANKS);
        e.accountNumber = String.valueOf(1_000_000_000L + (long) (r.nextDouble() * 999_000_000_000L));
        e.ifsc = pick(r, new String[] {"HDFC", "ICIC", "SBIN", "UTIB", "KKBK"}) + "0" + String.format("%06d", r.nextInt(1_000_000)).substring(0, 6);
        e.accountType = EorDropdownOptions.pick(r, "Account Type", new String[] {"Saving", "Current", "Salaried"});
        e.branchName = e.workingLocation + " Branch";
        e.bankAddress = (10 + r.nextInt(90)) + " Main Road, " + e.workingLocation + ", " + e.state + ", India";
    }

    private static void professional(EorEmployee e, Random r) {
        e.uan = String.format("%012d", 100_000_000_000L + (long) (r.nextDouble() * 899_999_999_999L));
        e.esic = String.format("%017d", 10_000_000_000_000_000L + (long) (r.nextDouble() * 89_999_999_999_999_999L));
        e.primarySkills = pick(r, new String[] {"Java, Spring Boot, SQL", "Selenium, Java, TestNG", "Python, SQL, PostgreSQL", "AWS, Docker, Jenkins"});
        e.secondarySkills = pick(r, new String[] {"Git, Maven, Jenkins", "REST API, Git, Postman", "Docker, Git, Linux", "SQL, Git, Postman"});
        e.language = pick(r, new String[] {"English", "Hindi"});
        e.proficiency = EorDropdownOptions.pick(r, "Proficiency", new String[] {"Beginner", "Intermediate", "Expert"});
    }

    private static void education(EorEmployee e, Random r) {
        e.educationLevel = EorDropdownOptions.pick(r, "Education Level", new String[] {"Masters", "Bachelor", "12th", "10th"});
        e.educationInstitution = pick(r, INSTITUTIONS);
        e.fieldOfStudy = pick(r, FIELDS);
        e.university = pick(r, UNIVERSITIES);
        e.modeOfEducation = EorDropdownOptions.pick(r, "Mode of Education", new String[] {"Part-Time", "Regular"});
        e.passingYear = String.valueOf(2012 + r.nextInt(12));
        e.certificateName = pick(r, CERTIFICATES);
        e.certificateInstitution = pick(r, CERT_INSTITUTIONS);
        e.certificateIssueDate = LocalDate.of(2018 + r.nextInt(7), 1 + r.nextInt(12), 1 + r.nextInt(28)).format(DMY);
    }

    private static void identification(EorEmployee e, Random r) {
        e.aadhaar = String.valueOf(2 + r.nextInt(8)) + String.format("%011d", (long) (r.nextDouble() * 99_999_999_999L));
        StringBuilder pan = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            pan.append((char) ('A' + r.nextInt(26)));
        }
        pan.append(1000 + r.nextInt(9000)).append((char) ('A' + r.nextInt(26)));
        e.pan = pan.toString();
        e.drivingLicence = "DL" + (100_000_000 + r.nextInt(900_000_000));
        e.passport = (char) ('A' + r.nextInt(26)) + String.format("%07d", r.nextInt(10_000_000));
    }

    private static void previousEmployment(EorEmployee e, Random r) {
        e.previousEmployer = pick(r, PREVIOUS_EMPLOYERS);
        e.previousDesignation = pick(r, new String[] {"Software Engineer", "Test Engineer", "Business Analyst", "Associate Consultant", "Support Engineer"});
        LocalDate from = LocalDate.of(2016 + r.nextInt(4), 1 + r.nextInt(12), 1);
        e.previousFrom = from.format(DMY);
        e.previousTo = from.plusMonths(18 + r.nextInt(30)).withDayOfMonth(28).format(DMY);
        e.previousReasonForLeaving = pick(r, LEAVING);
        e.previousResponsibilities = pick(r, JOBS);
    }

    private static <T> T pick(Random r, T[] pool) {
        return pool[r.nextInt(pool.length)];
    }

    public static List<String> states() {
        return List.copyOf(STATES.keySet());
    }
}
