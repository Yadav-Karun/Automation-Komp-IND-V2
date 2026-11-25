package utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.github.javafaker.Faker;

import model.EorEmployee;
import model.EorRun;

/**
 * Fake data for the EOR happy flow: two employees, a new set on every run (nothing is typed by hand).
 * Employee 1 = Experienced + Male, employee 2 = Fresher + Female. Lists of the portal (blood group, marital status ...) are chosen at random from the stored options.
 */
public class HappyFlowData {

    private static final Faker faker = new Faker(new Locale("en-IND"));
    private static final Random random = new Random();
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private static final String[] MALE = {"Aarav", "Vihaan", "Arjun", "Rohan", "Karan", "Rahul", "Aditya", "Nikhil", "Sameer", "Vikram", "Manish", "Deepak"};
    private static final String[] FEMALE = {"Ananya", "Diya", "Isha", "Kavya", "Meera", "Neha", "Priya", "Riya", "Sneha", "Tanvi", "Pooja", "Divya"};
    private static final String[] BANKS = {"HDFC Bank", "ICICI Bank", "State Bank of India", "Axis Bank", "Kotak Mahindra Bank"};
    private static final String[] INSTITUTES = {"ABC Institute of Technology", "Delhi Institute of Technology", "National Institute of Engineering"};
    private static final String[] COURSES = {"Computer Science", "Information Technology", "Electronics and Communication"};
    private static final String[] CERTIFICATES = {"Java Programming Certificate", "Selenium Automation Certificate", "SQL Certification"};

    /** State -> a city of it (for the "Working Location" of the workbook). */
    public static final Map<String, String> STATES = Map.of("Gujarat", "Ahmedabad", "Maharashtra", "Mumbai", "Rajasthan", "Jaipur", "Karnataka", "Ramanagara",
            "Tamil Nadu", "Chennai", "Telangana", "Hyderabad", "Uttar Pradesh", "Lucknow", "West Bengal", "Kolkata", "Bihar", "Patna", "Odisha", "Bhubaneswar");

    /** Department -> designations of it. */
    public static final Map<String, String[]> DEPARTMENTS = Map.of(
            "IT", new String[] {"SOFTWARE ENGINEER", "QA ENGINEER", "TEAM LEAD"},
            "HR", new String[] {"HR EXECUTIVE", "RECRUITER", "HR MANAGER"},
            "Finance", new String[] {"ACCOUNTANT", "FINANCE EXECUTIVE", "PAYROLL SPECIALIST"},
            "Sales", new String[] {"SALES EXECUTIVE", "SALES MANAGER", "ACCOUNT MANAGER"},
            "Operations", new String[] {"OPERATIONS EXECUTIVE", "OPERATIONS ANALYST", "COORDINATOR"});

    public static final Map<String, String> JOB_DESCRIPTIONS = Map.of(
            "IT", "Design, develop and test software applications and fix defects.",
            "HR", "Manage recruitment, onboarding and employee records.",
            "Finance", "Maintain books of accounts, invoices and monthly financial reports.",
            "Sales", "Find new customers, present the services and close sales targets.",
            "Operations", "Coordinate daily operations, track deliveries and report issues.");

    /** The department of each employee (by e-mail): the workbook has a column for it. */
    public static final Map<String, String> DEPARTMENT_OF = new java.util.concurrent.ConcurrentHashMap<>();

    public static EorRun newRun() {
        EorRun run = new EorRun();
        run.runId = "EOR-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + "-HF";
        run.runDate = LocalDate.now().toString();
        Set<String> used = usedEmails();
        run.employees.add(employee(true, "Experienced", used));
        run.employees.add(employee(false, "Fresher", used));
        return run;
    }

    private static EorEmployee employee(boolean male, String experience, Set<String> used) {
        EorEmployee e = new EorEmployee();
        e.gender = male ? "Male" : "Female";
        e.experience = experience;
        e.firstName = pick(male ? MALE : FEMALE);
        do {
            e.middleName = pick(male ? MALE : FEMALE);
        } while (e.middleName.equals(e.firstName));
        e.lastName = faker.name().lastName().replaceAll("[^a-zA-Z]", "");
        e.email = nextMailAddress(used);
        e.phone = mobile();
        e.state = pick(STATES.keySet().toArray(new String[0]));
        e.workingLocation = STATES.get(e.state);
        e.endClient = faker.company().name().replaceAll("[^a-zA-Z ]", "");

        String department = pick(DEPARTMENTS.keySet().toArray(new String[0]));
        e.designation = pick(DEPARTMENTS.get(department));
        e.jobDescription = JOB_DESCRIPTIONS.get(department);
        DEPARTMENT_OF.put(e.email, department);

        // personal
        e.fatherName = pick(MALE) + " " + e.lastName; // the surname of the relatives is the surname of the employee
        e.motherName = pick(FEMALE) + " " + e.lastName;
        e.maritalStatus = random.nextBoolean() ? "Married" : "Unmarried";
        e.spouseName = pick(male ? FEMALE : MALE) + " " + e.lastName;
        e.bloodGroup = EorDropdownOptions.pick(random, "Blood Group", new String[] {"A+", "B+", "O+", "AB+", "A-", "B-", "O-", "AB-"});
        e.dateOfBirth = LocalDate.now().minusYears(22 + random.nextInt(15)).minusDays(random.nextInt(300)).format(DMY);
        e.physicallyHandicapped = EorDropdownOptions.pick(random, "Physically Handicapped", new String[] {"Yes", "No"});

        // contact
        e.alternateMobile = mobile();
        e.emergencyRelationship = EorDropdownOptions.pick(random, "Relationship", new String[] {"Father", "Mother", "Sister", "Brother"});
        boolean emergencyIsMale = e.emergencyRelationship.equals("Father") || e.emergencyRelationship.equals("Brother");
        e.emergencyName = e.emergencyRelationship.equals("Father") ? e.fatherName : e.emergencyRelationship.equals("Mother") ? e.motherName
                : pick(emergencyIsMale ? MALE : FEMALE) + " " + e.lastName;
        e.emergencyNumber = mobile();
        e.addressState = EorDropdownOptions.pick(random, "State", STATES.keySet().toArray(new String[0]));
        e.addressCity = STATES.get(e.addressState); // the portal chooses a city at random from what it offers
        e.postalCode = digits(6);
        e.address = faker.address().streetAddress().replaceAll("[^a-zA-Z0-9 ,]", "");
        e.permanentSame = random.nextBoolean();
        if (!e.permanentSame) {
            e.permanentState = EorDropdownOptions.pick(random, "State", STATES.keySet().toArray(new String[0]));
            e.permanentCity = STATES.get(e.permanentState);
            e.permanentPostalCode = digits(6);
            e.permanentAddress = faker.address().streetAddress().replaceAll("[^a-zA-Z0-9 ,]", "");
        }

        // bank
        e.accountHolder = e.fullName();
        e.bankName = pick(BANKS);
        e.accountNumber = digits(12);
        e.ifsc = faker.regexify("[A-Z]{4}0[0-9]{6}");
        e.accountType = EorDropdownOptions.pick(random, "Account Type", new String[] {"Saving", "Current", "Salaried"});
        e.branchName = e.workingLocation + " Branch";
        e.bankAddress = faker.address().streetAddress().replaceAll("[^a-zA-Z0-9 ,]", "") + ", " + e.workingLocation;

        // professional
        e.uan = digits(12);
        e.esic = digits(17);
        e.primarySkills = "Java, Selenium, SQL";
        e.secondarySkills = "Git, Maven, Postman";
        e.language = pick(new String[] {"English", "Hindi"});
        e.proficiency = EorDropdownOptions.pick(random, "Proficiency", new String[] {"Beginner", "Intermediate", "Expert"});

        // education
        e.educationLevel = EorDropdownOptions.pick(random, "Education Level", new String[] {"Masters", "Bachelor", "12th", "10th"});
        e.educationInstitution = pick(INSTITUTES);
        e.fieldOfStudy = pick(COURSES);
        e.university = faker.university().name().replaceAll("[^a-zA-Z ]", "");
        e.modeOfEducation = EorDropdownOptions.pick(random, "Mode of Education", new String[] {"Part-Time", "Regular"});
        e.passingYear = String.valueOf(2012 + random.nextInt(12));
        e.certificateName = pick(CERTIFICATES);
        e.certificateInstitution = pick(new String[] {"Udemy", "Coursera", "Simplilearn"});
        e.certificateIssueDate = LocalDate.now().minusYears(1 + random.nextInt(5)).format(DMY);

        // identification
        e.aadhaar = (2 + random.nextInt(8)) + digits(11);
        e.pan = faker.regexify("[A-Z]{5}[0-9]{4}[A-Z]");
        e.drivingLicence = "DL" + digits(9);
        e.passport = faker.regexify("[A-Z][0-9]{7}");

        // the previous job of an experienced employee
        if (e.experienced()) {
            LocalDate from = LocalDate.now().minusYears(5).minusMonths(random.nextInt(12)).withDayOfMonth(1);
            e.previousEmployer = faker.company().name().replaceAll("[^a-zA-Z ]", "");
            e.previousDesignation = "Software Engineer";
            e.previousFrom = from.format(DMY);
            e.previousTo = from.plusMonths(24).withDayOfMonth(28).format(DMY);
            e.previousReasonForLeaving = "Better career opportunity";
            e.previousResponsibilities = "Developed and tested web applications.";
        }
        return e;
    }

    private static String mobile() {
        return (6 + random.nextInt(4)) + digits(9);
    }

    // n digits, the first one is never 0 (the portal refuses the postal code "076845")
    private static String digits(int n) {
        StringBuilder number = new StringBuilder().append(1 + random.nextInt(9));
        for (int i = 1; i < n; i++) {
            number.append(random.nextInt(10));
        }
        return number.toString();
    }

    private static String pick(String[] list) {
        return list[random.nextInt(list.length)];
    }

    // ------------------------------------------------------------------ the e-mail addresses: donotreply.qamail+1@gmail.com, +2, ...

    private static final Path USED = Paths.get("testdata/eor-used-emails.txt");

    private static Set<String> usedEmails() {
        Set<String> used = new HashSet<>();
        try {
            if (Files.isRegularFile(USED)) {
                List<String> lines = Files.readAllLines(USED, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (!line.startsWith("#") && !line.isBlank()) {
                        used.add(line.split("\\|")[0].trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + USED + ": " + e.getMessage(), e);
        }
        return used;
    }

    /** The next number that is not in testdata/eor-used-emails.txt (the file is written by {@link EorDataGenerator#book}). */
    private static String nextMailAddress(Set<String> used) {
        String base = ConfigManager.required("imap.address.base").toLowerCase(Locale.ROOT);
        String name = base.substring(0, base.indexOf('@'));
        String domain = base.substring(base.indexOf('@'));
        for (int n = 1; ; n++) {
            String address = name + "+" + n + domain;
            if (used.add(address)) {
                return address;
            }
        }
    }
}
