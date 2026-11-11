package utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import model.AorEmployee;
import model.AorRun;

/**
 * Test data of the AOR happy flow, from the rules of the QA owner (spec section 6):
 * <ul>
 *   <li>client and project are fixed ("Bleach" / "The Substitute Shinigami": the ones of the supplied workbook), never replaced at random;</li>
 *   <li>every employee gets a random state of India and its capital as the hub; Delivery Partner Id, Company Employee Id and the name are unique and are written to
 *       the run history ({@link AorRunHistory}) at once;</li>
 *   <li>onboarding date before 1 Jan 2025, joining date before 1 Jan 2024, an age of at least 18 on the run date;</li>
 *   <li>Aadhaar and PAN come from the pool of the QA owner, one pair per employee, round-robin;</li>
 *   <li>one Area Manager name and one Supervisor name per run, the same for every employee.</li>
 * </ul>
 * All other values are synthetic. {@code -Daor.seed=<n>} repeats the random choices (names / ids that the history already holds are skipped anyway).
 */
public final class AorDataGenerator {

    public static final String CLIENT = "Bleach";
    public static final String PROJECT = "The Substitute Shinigami";
    /** The manager of every employee, and the Area Manager e-mail (the AOR manager of the walkthrough). */
    public static final String MANAGER_NAME = "Karun Yadav";
    public static final String AREA_MANAGER_EMAIL = "Karun.Yadav.AOR@yopmail.com";

    /** The states of India and their capitals (a union territory is not used: the capital of a state is what the rule asks for). */
    private static final Map<String, String> CAPITALS = new LinkedHashMap<>();

    static {
        String[][] pairs = { { "Andhra Pradesh", "Amaravati" }, { "Arunachal Pradesh", "Itanagar" }, { "Assam", "Dispur" }, { "Bihar", "Patna" },
                { "Chhattisgarh", "Raipur" }, { "Goa", "Panaji" }, { "Gujarat", "Gandhinagar" }, { "Haryana", "Chandigarh" }, { "Himachal Pradesh", "Shimla" },
                { "Jharkhand", "Ranchi" }, { "Karnataka", "Bengaluru" }, { "Kerala", "Thiruvananthapuram" }, { "Madhya Pradesh", "Bhopal" },
                { "Maharashtra", "Mumbai" }, { "Manipur", "Imphal" }, { "Meghalaya", "Shillong" }, { "Mizoram", "Aizawl" }, { "Nagaland", "Kohima" },
                { "Odisha", "Bhubaneswar" }, { "Punjab", "Chandigarh" }, { "Rajasthan", "Jaipur" }, { "Sikkim", "Gangtok" }, { "Tamil Nadu", "Chennai" },
                { "Telangana", "Hyderabad" }, { "Tripura", "Agartala" }, { "Uttar Pradesh", "Lucknow" }, { "Uttarakhand", "Dehradun" },
                { "West Bengal", "Kolkata" } };
        for (String[] p : pairs) {
            CAPITALS.put(p[0], p[1]);
        }
    }

    private static final String[] FIRST = { "Aarav", "Vihaan", "Rudra", "Advait", "Kabir", "Arjun", "Ishaan", "Reyansh", "Vivaan", "Aditya", "Ayaan", "Krish", "Sai",
            "Dhruv", "Shaurya", "Atharv", "Aanya", "Diya", "Saanvi", "Ananya", "Myra", "Kiara", "Navya", "Pari", "Riya", "Sara", "Tara", "Meera", "Isha", "Kavya",
            "Rohan", "Karan", "Nikhil", "Rahul", "Siddharth", "Manish", "Neha", "Pooja", "Priya", "Sneha" };
    private static final String[] LAST = { "Bhatia", "Malhotra", "Kapoor", "Sethi", "Chawla", "Mehta", "Sharma", "Verma", "Gupta", "Joshi", "Nair", "Menon", "Iyer",
            "Reddy", "Rao", "Patel", "Shah", "Desai", "Banerjee", "Chatterjee", "Das", "Ghosh", "Bose", "Khanna", "Arora", "Bajaj", "Saxena", "Tiwari", "Mishra",
            "Pandey", "Yadav", "Singh", "Kulkarni", "Deshmukh", "Pillai", "Naidu", "Thakur", "Sinha", "Jain", "Agarwal" };

    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private AorDataGenerator() {
    }

    /** The states the employees can come from. */
    public static Set<String> states() {
        return CAPITALS.keySet();
    }

    public static String capitalOf(String state) {
        String c = CAPITALS.get(state);
        if (c == null) {
            throw new IllegalArgumentException("No capital known for the state '" + state + "'.");
        }
        return c;
    }

    // ------------------------------------------------------------------ the pool of the QA owner

    /** The Aadhaar / PAN pairs of {@code config/aor-identities.local.txt}: [aadhaar, pan]. */
    public static List<String[]> identityPool() {
        Path f = Paths.get(ConfigManager.get("aor.identities.file", "config/aor-identities.local.txt"));
        if (!Files.isRegularFile(f)) {
            throw new IllegalStateException("The Aadhaar / PAN numbers supplied by the QA owner are missing: " + f.toAbsolutePath()
                    + " (one line per employee: Aadhaar number, a tab, PAN number). They are never generated.");
        }
        try {
            List<String[]> out = new ArrayList<>();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.trim().split("\\s+");
                if (p.length >= 2) {
                    out.add(new String[] { p[0], p[1] });
                }
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("The Aadhaar / PAN file " + f + " cannot be read: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ a run

    /** A new run: {@code count} employees (1 to 10: the template has 10 rows) with the data rules applied; their ids are booked in the run history. */
    public static AorRun newRun(int count) {
        if (count < 1 || count > 20) {
            throw new IllegalArgumentException("An AOR run has 1 to 20 employees (the Aadhaar / PAN pool has 20 pairs), not " + count + ".");
        }
        long seed = ConfigManager.get("aor.seed") != null ? Long.parseLong(ConfigManager.get("aor.seed")) : System.nanoTime() & 0xFFFFFFFFL;
        Random rnd = new Random(seed);
        AorRun run = new AorRun();
        LocalDateTime now = LocalDateTime.now();
        run.runId = "AOR-" + now.format(RUN_STAMP);
        run.runDate = now.toLocalDate().toString(); // the business date of the run, taken ONCE
        run.client = CLIENT;
        run.project = PROJECT;
        run.areaManagerName = person(rnd);
        run.supervisorName = person(rnd);

        List<String[]> pool = identityPool();
        if (pool.size() < count) {
            throw new IllegalStateException("The Aadhaar / PAN pool has " + pool.size() + " pair(s) but the run needs " + count + ".");
        }
        int offset = AorRunHistory.employeesEver();
        List<String[]> pairs = pickPairs(pool, count, offset);
        Set<String> usedDp = AorRunHistory.used("DP");
        Set<String> usedEmp = AorRunHistory.used("EMP");
        Set<String> usedNames = AorRunHistory.used("NAME");
        List<String> states = new ArrayList<>(CAPITALS.keySet());

        for (int i = 0; i < count; i++) {
            AorEmployee e = new AorEmployee();
            e.state = states.get(rnd.nextInt(states.size()));
            e.hubLocation = capitalOf(e.state);
            e.deliveryPartnerId = uniqueId("DPA-", rnd, usedDp);
            e.companyEmployeeId = uniqueId("TSS-", rnd, usedEmp);
            e.name = uniqueName(rnd, usedNames);
            e.fatherName = FIRST[rnd.nextInt(FIRST.length)] + " " + e.name.substring(e.name.indexOf(' ') + 1);
            e.contactNumber = "9" + digits(rnd, 9);
            // joining before 1 Jan 2024; onboarding on or after the joining date and before 1 Jan 2025; born 1985-2000 (at least 18 years old on the run date and when joining)
            LocalDate joining = between(rnd, LocalDate.of(2019, 1, 1), LocalDate.of(2023, 12, 31));
            LocalDate onboarding = between(rnd, joining, LocalDate.of(2024, 12, 31));
            e.joiningDate = joining.toString();
            e.onboardingDate = onboarding.toString();
            e.birthDate = between(rnd, LocalDate.of(1985, 1, 1), LocalDate.of(2000, 12, 31)).toString();
            // the Hub Name: the hub location, a hyphen and the Company Employee Id (rule of the QA owner)
            e.hubName = e.hubLocation + "-" + e.companyEmployeeId;
            e.personalEmail = e.name.replace(" ", "") + "." + emailTag() + "@yopmail.com";
            e.address = "Flat No. " + (1 + rnd.nextInt(99)) + ", " + FIRST[rnd.nextInt(FIRST.length)] + " Nagar, " + e.hubLocation + ", " + e.state + " "
                    + (1 + rnd.nextInt(8)) + digits(rnd, 5);
            e.bankName = "SBI";
            e.accountNumber = "9" + digits(rnd, 11);
            e.ifscCode = "SBIN1" + digits(rnd, 6);
            e.drivingLicence = "DL" + digits(rnd, 13);
            e.vehicleInsurance = "VI" + digits(rnd, 10);
            e.managerContactNumber = "9" + digits(rnd, 9);
            String[] id = pairs.get(i);
            e.aadhaar = id[0];
            e.pan = id[1];
            run.employees.add(e);
            // booked at once: a run that stops half-way still blocks these values for ever
            AorRunHistory.record(run.runId, "DP", e.deliveryPartnerId);
            AorRunHistory.record(run.runId, "EMP", e.companyEmployeeId);
            AorRunHistory.record(run.runId, "NAME", e.name);
        }
        return run;
    }

    /**
     * The Aadhaar / PAN pairs of a run: pairs that no employee has used yet come first (a pair is booked in the run history as soon as it is chosen); when every pair has been
     * used the list is used again from the start. The first 20 pairs of the list are the older list that earlier runs already used. A pair that is not a valid Aadhaar (12 digits)
     * / PAN (AAAAA9999A in Latin capitals) is never used.
     */
    /** False while rows are generated that can never become employees (the edge cases that are always refused): their pairs are not booked as used. */
    public static volatile boolean bookPairs = true;

    private static List<String[]> pickPairs(List<String[]> pool, int count, int offset) {
        List<String[]> valid = new ArrayList<>();
        for (String[] p : pool) {
            if (p[0].matches("\\d{12}") && p[1].matches("[A-Z]{5}\\d{4}[A-Z]")) {
                valid.add(p);
            }
        }
        Set<String> used = new HashSet<>(AorRunHistory.used("AADHAAR"));
        if (used.isEmpty() && offset > 0) {
            for (int i = 0; i < Math.min(20, valid.size()); i++) {
                used.add(valid.get(i)[0].toLowerCase());
            }
        }
        List<String[]> out = new ArrayList<>();
        for (String[] p : valid) {
            if (out.size() < count && !used.contains(p[0].toLowerCase())) {
                out.add(p);
            }
        }
        for (int i = 0; out.size() < count; i++) {
            out.add(valid.get((offset + i) % valid.size()));
        }
        if (bookPairs) {
            for (String[] p : out) {
                AorRunHistory.record("PAIR", "AADHAAR", p[0]);
            }
        }
        return out;
    }

    /** The Final Payout of every employee: a whole amount between 1000 and 9999. */
    public static void assignPayouts(AorRun run) {
        Random rnd = new Random(System.nanoTime());
        for (AorEmployee e : run.employees) {
            e.finalPayout = 1000 + rnd.nextInt(9000);
        }
    }

    /** Gives a payout to the employees that do not have one yet (the others keep theirs). */
    public static void assignPayoutsMissing(AorRun run) {
        Random rnd = new Random(System.nanoTime());
        for (AorEmployee e : run.employees) {
            if (e.finalPayout <= 0) {
                e.finalPayout = 1000 + rnd.nextInt(9000);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String uniqueId(String prefix, Random rnd, Set<String> used) {
        for (int i = 0; i < 1000; i++) {
            String id = prefix + (100000 + rnd.nextInt(900000));
            if (used.add(id.toLowerCase())) {
                return id;
            }
        }
        throw new IllegalStateException("No unused id with the prefix " + prefix + " is left.");
    }

    private static String uniqueName(Random rnd, Set<String> used) {
        for (int i = 0; i < 1000; i++) {
            String name = person(rnd);
            if (used.add(name.toLowerCase())) {
                return name;
            }
        }
        throw new IllegalStateException("No unused employee name is left.");
    }

    private static String person(Random rnd) {
        return FIRST[rnd.nextInt(FIRST.length)] + " " + LAST[rnd.nextInt(LAST.length)];
    }

    private static String digits(Random rnd, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(rnd.nextInt(10));
        }
        return sb.toString();
    }

    private static LocalDate between(Random rnd, LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to);
        return from.plusDays((long) rnd.nextInt((int) days + 1));
    }

    /** The letters between the dot and @yopmail.com of a personal e-mail: first letters of the client and of the project ("Bleach" + "The Substitute Shinigami" -> BTSS). */
    public static String emailTag() {
        return initials(CLIENT) + initials(PROJECT);
    }

    /** First letter of every word: "The Substitute Shinigami" -> TSS, "Bleach" -> B. */
    static String initials(String text) {
        StringBuilder sb = new StringBuilder();
        for (String w : text.trim().split("\\s+")) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0)));
            }
        }
        return sb.toString();
    }
}
