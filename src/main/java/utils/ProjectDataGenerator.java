package utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import model.ProjectData;

/**
 * Test data of the project happy flow (rules of the test brief):
 * <ul>
 *   <li>client = an existing client of the type's dropdown (random; -Dproject.client="Bleach" picks one);</li>
 *   <li>project name = "&lt;client&gt; - &lt;topic&gt;" (the first topic nobody used yet, so a re-run never repeats a name), code = the first letter of every word
 *       of the name, no number ("Bleach - Contract Management" -&gt; BCM);</li>
 *   <li>AOR reference id = client prefix + "-IND-" + random number (BLEACH-IND-0042); EOR end client = a real company;</li>
 *   <li>start date before 1 January 2025, end date always empty, country India, a random state (EOR: with a city that really belongs to that state).</li>
 * </ul>
 * Every random choice comes from one {@link Random} seeded with {@code -Dproject.seed} (or the clock): the seed is stored in the data and printed in the
 * report, so a failing combination can be repeated.
 */
public final class ProjectDataGenerator {

    /** Meaningful project topics, in the order they are tried. Their initials all differ, so two projects of one client never share a code. */
    static final List<String> TOPICS = List.of("Contract Management", "Payroll Automation", "Talent Onboarding", "Compliance Tracking",
            "Vendor Portal", "Invoice Management", "Attendance Analytics", "Document Workflow", "Employee Self Service", "Benefits Administration",
            "Recruitment Pipeline", "Performance Reviews");

    /** Real companies for the EOR "End Client Name". */
    static final List<String> END_CLIENTS = List.of("Microsoft", "Google", "Amazon", "Infosys", "Accenture", "IBM", "Oracle", "Deloitte", "Cisco", "Adobe");

    /** Indian states / union territories as the application lists them -> a real city of that state (the EOR city field is free text). */
    static final Map<String, String> STATE_CITY = new LinkedHashMap<>();

    static {
        String[][] pairs = {
                { "Andaman and Nicobar Islands", "Port Blair" }, { "Andhra Pradesh", "Visakhapatnam" }, { "Arunachal Pradesh", "Itanagar" },
                { "Assam", "Guwahati" }, { "Bihar", "Patna" }, { "Chandigarh", "Chandigarh" }, { "Chhattisgarh", "Raipur" },
                { "Dadra and Nagar Haveli and Daman and Diu", "Daman" }, { "Delhi", "New Delhi" }, { "Goa", "Panaji" }, { "Gujarat", "Ahmedabad" },
                { "Haryana", "Gurugram" }, { "Himachal Pradesh", "Shimla" }, { "Jammu and Kashmir", "Srinagar" }, { "Jharkhand", "Ranchi" },
                { "Karnataka", "Bengaluru" }, { "Kerala", "Kochi" }, { "Ladakh", "Leh" }, { "Lakshadweep", "Kavaratti" },
                { "Madhya Pradesh", "Bhopal" }, { "Maharashtra", "Mumbai" }, { "Manipur", "Imphal" }, { "Meghalaya", "Shillong" },
                { "Mizoram", "Aizawl" }, { "Nagaland", "Kohima" }, { "Odisha", "Bhubaneswar" }, { "Puducherry", "Puducherry" },
                { "Punjab", "Ludhiana" }, { "Rajasthan", "Jaipur" }, { "Sikkim", "Gangtok" }, { "Tamil Nadu", "Chennai" },
                { "Telangana", "Hyderabad" }, { "Tripura", "Agartala" }, { "Uttar Pradesh", "Lucknow" }, { "Uttarakhand", "Dehradun" },
                { "West Bengal", "Kolkata" } };
        for (String[] p : pairs) {
            STATE_CITY.put(p[0], p[1]);
        }
    }

    private final Random random;
    private final ProjectData data = new ProjectData();

    /** Starts the data of one project of the given type ("AOR" or "EOR"). */
    public ProjectDataGenerator(String type) {
        String given = ConfigManager.get("project.seed");
        long seed = given != null ? Long.parseLong(given) : System.nanoTime() & 0xFFFFFFFFL;
        this.random = new Random(seed);
        data.type = type;
        data.seed = seed;
        data.country = "India";
    }

    public ProjectData data() {
        return data;
    }

    // ------------------------------------------------------------------ client, name, code

    /** The client of the project: -Dproject.client when given (must be in the dropdown), otherwise a random one of the dropdown. */
    public String pickClient(List<String> options) {
        if (options.isEmpty()) {
            throw new AssertionError("The Client Name dropdown has no client: there is no existing " + data.type + " client to create a project for.");
        }
        String wanted = ConfigManager.get("project.client");
        if (wanted != null) {
            for (String o : options) {
                if (o.equalsIgnoreCase(wanted)) {
                    data.clientName = o;
                    return o;
                }
            }
            throw new AssertionError("-Dproject.client='" + wanted + "' is not in the " + data.type + " Client Name dropdown. Available: " + options);
        }
        data.clientName = options.get(random.nextInt(options.size()));
        return data.clientName;
    }

    /**
     * Project name "&lt;client&gt; - &lt;topic&gt;" and its code. The first topic whose name AND code are not in the listing yet is used (names are compared
     * ignoring case), so a second run never creates a duplicate - duplicates are a negative case, not part of the happy flow.
     */
    public void generateNameAndCode(Set<String> usedNames, Set<String> usedCodes) {
        for (String topic : TOPICS) {
            String name = data.clientName + " - " + topic;
            String code = codeOf(name);
            if (!lower(usedNames).contains(name.toLowerCase(Locale.ROOT)) && !lower(usedCodes).contains(code.toLowerCase(Locale.ROOT))) {
                data.projectName = name;
                data.projectCode = code;
                return;
            }
        }
        throw new AssertionError("Every project topic is already used for the client '" + data.clientName + "' (" + TOPICS.size()
                + " names tried): the happy flow needs a name that is not in the listing yet. Add topics to ProjectDataGenerator.TOPICS.");
    }

    private static Set<String> lower(Set<String> in) {
        Set<String> out = new java.util.HashSet<>();
        for (String s : in) {
            out.add(s.toLowerCase(Locale.ROOT).trim());
        }
        return out;
    }

    /**
     * First letter of each word of the project name, upper case, no number: "Ija Doi" -> ID, "Bleach - Contract Management" -> BCM
     * (a lone "-" is not a word; a word that starts with a digit or a symbol is skipped as well).
     */
    public static String codeOf(String projectName) {
        StringBuilder code = new StringBuilder();
        for (String word : projectName.trim().split("\\s+")) {
            if (!word.isEmpty() && Character.isLetter(word.charAt(0))) {
                code.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        return code.toString();
    }

    // ------------------------------------------------------------------ AOR

    /** AOR reference id: client prefix + -IND- + 4 random digits (e.g. BLEACH-IND-0042, ATC-IND-7305). */
    public String generateReferenceId() {
        data.referenceId = clientPrefix(data.clientName) + "-IND-" + String.format("%04d", 1 + random.nextInt(9999));
        return data.referenceId;
    }

    /** One word -> the word in capitals ("BLEACH"); several words -> their initials ("AOR Test Client" -> "ATC"). Letters and digits only. */
    static String clientPrefix(String client) {
        String[] words = client.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        if (words.length == 1) {
            sb.append(words[0].replaceAll("[^A-Za-z0-9]", ""));
        } else {
            for (String w : words) {
                String clean = w.replaceAll("[^A-Za-z0-9]", "");
                if (!clean.isEmpty()) {
                    sb.append(clean.charAt(0));
                }
            }
        }
        String prefix = sb.toString().toUpperCase(Locale.ROOT);
        return prefix.isEmpty() ? "CLI" : prefix;
    }

    // ------------------------------------------------------------------ EOR

    /** A real company name for the EOR "End Client Name". */
    public String generateEndClient() {
        data.endClientName = END_CLIENTS.get(random.nextInt(END_CLIENTS.size()));
        return data.endClientName;
    }

    // ------------------------------------------------------------------ dates and locations

    /** A random valid date between 1 January 2020 and 31 December 2024 (before 1 January 2025). */
    public LocalDate generateStartDate() {
        LocalDate from = LocalDate.of(2020, 1, 1);
        long days = ChronoUnit.DAYS.between(from, LocalDate.of(2024, 12, 31));
        LocalDate d = from.plusDays((long) random.nextInt((int) days + 1));
        data.startDate = d.toString();
        return d;
    }

    /** A random state of the dropdown (AOR: the state only). */
    public String pickState(List<String> options) {
        if (options.isEmpty()) {
            throw new AssertionError("The State dropdown has no state after India was selected.");
        }
        return options.get(random.nextInt(options.size()));
    }

    /** A random state of the dropdown for which a matching city is known (EOR). */
    public String pickStateWithCity(List<String> options) {
        List<String> known = new ArrayList<>();
        for (String o : options) {
            if (STATE_CITY.containsKey(o)) {
                known.add(o);
            }
        }
        if (known.isEmpty()) {
            throw new AssertionError("None of the states in the dropdown has a known city (dropdown: " + options + ").");
        }
        return known.get(random.nextInt(known.size()));
    }

    /** A real city of the (Indian) state. */
    public static String cityOf(String state) {
        String city = STATE_CITY.get(state);
        if (city == null) {
            throw new AssertionError("No known city for the state '" + state + "'.");
        }
        return city;
    }

    // ------------------------------------------------------------------ a copy of the data next to the report

    /** Writes the data of the case as JSON into the run folder (no secrets in it). */
    public static void save(ProjectData d, Path file) {
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(Files.newOutputStream(file), d);
        } catch (IOException e) {
            Log.warn("Project data not written to " + file + ": " + e.getMessage());
        }
    }
}
