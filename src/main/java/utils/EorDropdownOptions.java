package utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Every entry of every drop-down of the candidate portal (user, 2026-11-18: "many times it gets stuck on drop-downs ... take all the options of the lists, store them in the code and choose
 * randomly, without any bias, except the country, which is always India").
 * <ul>
 *   <li>{@link #STORED}: the entries as the portal offered them (key = the label of the list: "Marital Status", "Blood Group", "Physically Handicapped", "Relationship", "State", "Account Type",
 *       "Proficiency", "Education Level", "Mode of Education" ...). The test data generator picks from them with the same chance for every entry ({@link #pick}).</li>
 *   <li>Harvest: with {@code -Deor.harvest.dropdowns=true} the candidate-portal page writes every entry it sees to {@code testdata/eor-dropdown-options.json} (the entries of a run are added to
 *       the ones already in the file). The file is the source of {@link #STORED}: after a harvest run the constants below are made from it.</li>
 *   <li>The country is not a free choice: it is always India ({@link #COUNTRY}).</li>
 * </ul>
 */
public final class EorDropdownOptions {

    /** The only country that is ever chosen (user's rule). */
    public static final String COUNTRY = "India";

    /** Where a harvest run writes what it saw. */
    public static final Path HARVEST_FILE = Paths.get("testdata/eor-dropdown-options.json");

    private static final Map<String, List<String>> STORED = new LinkedHashMap<>();

    static {
        // GENERATED on 2026-11-18 from testdata/eor-dropdown-options.json (a complete harvest run of the candidate portal): every entry of every list that the generator uses.
        // "City of <state>" holds the cities of the states that the harvest run visited; the cities are normally read live from the open list.
        STORED.put("Marital Status", List.of(
                "Married", "Unmarried", "Other"));
        STORED.put("Blood Group", List.of(
                "A+", "A-", "B+", "B-", "AB+", "AB-",
                "O+", "O-"));
        STORED.put("Physically Handicapped", List.of(
                "Yes", "No"));
        STORED.put("Relationship", List.of(
                "Father", "Mother", "Spouse", "Brother", "Sister", "Other"));
        STORED.put("State", List.of(
                "Andaman and Nicobar Islands", "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chandigarh",
                "Chhattisgarh", "Dadra and Nagar Haveli and Daman and Diu", "Delhi", "Goa", "Gujarat", "Haryana",
                "Himachal Pradesh", "Jammu and Kashmir", "Jharkhand", "Karnataka", "Kerala", "Ladakh",
                "Lakshadweep", "Madhya Pradesh", "Maharashtra", "Manipur", "Meghalaya", "Mizoram",
                "Nagaland", "Odisha", "Puducherry", "Punjab", "Rajasthan", "Sikkim",
                "Tamil Nadu", "Telangana", "Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal"));
        STORED.put("City of Andhra Pradesh", List.of(
                "Addanki", "Adoni", "Akasahebpet", "Akividu", "Akkarampalle", "Amalapuram",
                "Amudalavalasa", "Anakapalle", "Anantapur", "Atmakur", "Attili", "Avanigadda",
                "Badvel", "Banganapalle", "Bapatla", "Betamcherla", "Bhattiprolu", "Bhimavaram",
                "Bhimunipatnam", "Bobbili", "Challapalle", "Chemmumiahpet", "Chilakalurupet", "Chinnachowk",
                "Chipurupalle", "Chirala", "Chittoor", "Chodavaram", "Cuddapah", "Cumbum",
                "Darsi", "Dharmavaram", "Dhone", "Diguvametta", "East Godavari", "Elamanchili",
                "Ellore", "Emmiganur", "Erraguntla", "Etikoppaka", "Gajuwaka", "Ganguvada",
                "Gannavaram", "Giddalur", "Gokavaram", "Gorantla", "GovindapuramChilakaluripetGuntur", "Gudivada",
                "Gudlavalleru", "Gudur", "Guntakal Junction", "Guntur", "Hindupur", "Ichchapuram",
                "Jaggayyapeta", "Jammalamadugu", "Kadiri", "Kaikalur", "Kakinada", "Kalyandurg",
                "Kamalapuram", "Kandukur", "Kanigiri", "Kankipadu", "Kanuru", "Kavali",
                "Kolanukonda", "Kondapalle", "Korukollu", "Kosigi", "Kovvur", "Krishna",
                "Kuppam", "Kurnool", "Macherla", "Machilipatnam", "Madanapalle", "Madugula",
                "Mandapeta", "Mandasa", "Mangalagiri", "Markapur", "Nagari", "Nagireddipalli",
                "Nandigama", "Nandikotkur", "Nandyal", "Narasannapeta", "Narasapur", "Narasaraopet",
                "Narasingapuram", "Narayanavanam", "Narsipatnam", "Nayudupet", "Nellore", "Nidadavole",
                "Nuzvid", "Ongole", "Pakala", "Palakollu", "Palasa", "Palkonda",
                "Pallevada", "Palmaner", "Parlakimidi", "Parvatipuram", "Pavuluru", "Pedana",
                "pedda nakkalapalem", "Peddapuram", "Penugonda", "Penukonda", "Phirangipuram", "Pippara",
                "Pithapuram", "Polavaram", "Ponnur", "Ponnuru", "Prakasam", "Proddatur",
                "Pulivendla", "Punganuru", "Puttaparthi", "Puttur", "Rajahmundry", "Ramachandrapuram",
                "Ramanayyapeta", "Ramapuram", "Rampachodavaram", "Rayachoti", "Rayadrug", "Razam",
                "Razampeta", "Razole", "Renigunta", "Repalle", "Salur", "Samalkot",
                "Sattenapalle", "Singarayakonda", "Sompeta", "Srikakulam", "Srisailain", "Suluru",
                "Tadepalle", "Tadepallegudem", "Tadpatri", "Tanuku", "Tekkali", "Tirumala",
                "Tirupati", "Tuni", "Uravakonda", "vadlamuru", "Vadlapudi", "Venkatagiri",
                "Vepagunta", "Vetapalem", "Vijayawada", "Vinukonda", "Visakhapatnam", "Vizianagaram",
                "Vizianagaram District", "Vuyyuru", "West Godavari", "Yanam", "Yanamalakuduru", "Yarada"));
        STORED.put("City of Maharashtra", List.of(
                "Achalpur", "Adawad", "Agar Panchaitan", "Aheri", "Ahmadpur", "Ahmednagar",
                "Airoli", "Ajara", "Akalkot", "Akluj", "Akola", "Akolner",
                "Akot", "Akrani", "Alandi", "Ale", "Alibag", "Alkuti",
                "Allapalli", "Amalner", "Amarnath", "Ambad", "Ambajogai", "Ambegaon",
                "Ambernath", "Amgaon", "Amravati", "Andheri", "Andura", "Anjangaon",
                "Anjarle", "Anshing", "Arag", "Arangaon", "Ardhapur", "Argaon",
                "Artist Village", "Arvi", "Ashta", "Ashti", "Asoda", "Assaye",
                "Astagaon", "Aundh Satara", "Aurangabad", "Ausa", "Badlapur", "Badnapur",
                "Badnera", "Bagewadi", "Balapur", "Balapur Akola district", "Ballalpur", "Ballard Estate",
                "Ballarpur", "Banda Maharashtra", "Bandra", "Baner", "Bankot", "Baramati",
                "Barsi", "Basmat", "Bavdhan", "Bawanbir", "Beed", "Bhadgaon Maharashtra",
                "Bhandara", "Bhandardara", "Bhandup", "Bhayandar", "Bhigvan", "Bhiwandi",
                "Bhiwapur", "Bhokar", "Bhokardan", "Bhoom", "Bhor", "Bhudgaon",
                "Bhugaon", "Bhusaval", "Bijur", "Bilashi", "Biloli", "Boisar",
                "Borgaon Manju", "Borivali", "Brahmapuri", "Breach Candy", "Buldana", "Byculla",
                "Chakan", "Chakur", "Chalisgaon", "Chanda", "Chandgad", "Chandor",
                "Chandrapur", "Chandur", "Chandur Bazar", "Chausala", "Chembur", "Chicholi",
                "Chichondi Patil", "Chikhli (Buldhana)", "Chikhli (Jalna)", "Chimur", "Chinchani", "Chinchpokli",
                "Chiplun", "Chopda", "Colaba", "Dabhol", "Daddi", "Dahanu",
                "Dahivel", "Dapoli", "Darwha", "Daryapur", "Dattapur", "Daulatabad",
                "Daund", "Deccan Gymkhana", "Deglur", "Dehu", "Deolali", "Deolapar",
                "Deoli", "Deoni", "Deulgaon Raja", "Devrukh", "Dharangaon", "Dharavi",
                "Dharmabad", "Dharur", "Dhawalpuri", "Dhule", "Dhulia", "Dighori",
                "Diglur", "Digras", "Dindori Maharashtra", "Diveagar", "Dombivli", "Dondaicha",
                "Dongri", "Dudhani", "Durgapur", "Durgapur Chandrapur", "Erandol", "Faizpur",
                "Fort", "Gadchiroli", "Gadhinglaj", "Gangakher", "Gangapur", "Ganpatipule",
                "Gevrai", "Ghargaon", "Ghatanji", "Ghatkopar", "Ghoti Budrukh", "Ghugus",
                "Girgaon", "Gondia", "Gorai", "Goregaon", "Guhagar", "Hadapsar Pune",
                "Hadgaon", "Halkarni", "Harangul", "Harnai", "Helwak", "Hinganghat",
                "Hingoli", "Hirapur", "Hirapur Hamesha", "Hotgi", "Ichalkaranji", "Igatpuri",
                "Indapur", "Jaisingpur", "Jaitapur", "Jakhangaon", "Jalgaon", "Jalgaon Jamod",
                "Jalkot", "Jalna", "Jamkhed", "Jamod", "Janephal", "Jaoli",
                "Jat Sangli", "Jategaon", "Jawhar", "Jaysingpur", "Jejuri", "Jintur",
                "Jogeshwari", "Juhu", "Junnar", "Kachurwahi", "Kadegaon", "Kadus",
                "Kagal", "Kaij", "Kalamb", "Kalamb Osmanabad", "Kalamboli", "Kalamnuri",
                "Kalas", "Kali(DK)", "Kalmeshwar", "Kalundri", "Kalyan", "Kalyani Nagar",
                "Kamargaon", "Kamatgi", "Kamptee", "Kandri", "Kankauli", "Kankavli",
                "Kannad", "Karad", "Karajagi", "Karanja", "Karanja Lad", "Karjat",
                "Karkamb", "Karmala", "Kasara", "Kasoda", "Kati", "Katol",
                "Katral", "Khadki", "Khalapur", "Khallar", "Khamgaon", "Khanapur",
                "Khandala", "Khangaon", "Khapa", "Kharakvasla", "Kharda", "Kharghar",
                "Kharsundi", "Khed", "Khetia", "Khoni", "Khopoli", "Khuldabad",
                "Kinwat", "Kodoli", "Kolhapur", "Kondalwadi", "Kondhali", "Kopar Khairane",
                "Kopargaon", "Kopela", "Koradi", "Koregaon", "Koynanagar", "Kudal",
                "Kuhi", "Kurandvad", "Kurankhed", "Kurduvadi", "Kusumba", "Lakhandur",
                "Lanja", "Lasalgaon", "Latur", "Lavasa", "Lohogaon", "Lonar",
                "Lonavla", "Mahabaleshwar", "Mahad", "Mahape", "Mahim", "Maindargi",
                "Majalgaon", "Makhjan", "Malabar Hill", "Malad", "Malegaon", "Malkapur",
                "Malvan", "Manchar", "Mandangad", "Mandhal", "Mandwa", "Mangaon",
                "Mangrul Pir", "Manjlegaon", "Mankeshwar", "Mankhurd", "Manmad", "Manor",
                "Mansar", "Manwat", "Maregaon", "Mastiholi", "Masur India", "Matheran",
                "Matunga", "Mazagaon", "Mehekar", "Mehergaon", "Mehkar", "Mhasla",
                "Mhasvad", "Miraj", "Mohadi", "Mohol", "Mohpa", "Mokhada taluka",
                "Mora Maharashtra", "Moram", "Morsi", "Mowad", "Mudkhed", "Mukher",
                "Mul", "Mulher", "Mulund", "Mumbai", "Mumbai Suburban", "Murbad",
                "Murgud", "Murtajapur", "Murud (Raigad)", "Murud (Ratnagiri)", "Murum", "Nadgaon",
                "Nagapur", "Nagothana", "Nagpur", "Nagpur Division", "Nala Sopara", "Naldurg",
                "Nalegaon", "Nampur", "Nanded", "Nandgaon", "Nandnee", "Nandura",
                "Nandura Buzurg", "Nandurbar", "Narayangaon", "Nardana", "Nariman Point", "Narkhed",
                "Nashik", "Nashik Division", "Navapur", "Navi Mumbai", "Neral", "Nerur",
                "Nevasa", "Nighoj", "Nilanga", "Nipani", "Niphad", "Nira Narsingpur",
                "Osmanabad", "Ozar", "Pabal", "Pachora", "Pahur Maharashtra", "Paithan",
                "Palghar", "Pali Raigad", "Palso", "Panchgani", "Pandharpur", "Pandhurli",
                "Panhala", "Panvel", "Parbhani", "Parel", "Parli Vaijnath", "Parner",
                "Parola", "Parseoni", "Partur", "Patan", "Pathardi", "Pathri",
                "Patur", "Paturda", "Paud", "Pauni", "Pawni", "Pedgaon",
                "Peint", "Pen", "Phaltan", "Phulambri", "Piliv", "Pimpalgaon Baswant",
                "Pimpalgaon Raja", "Pimpri", "Pimpri-Chinchwad", "Pipri", "Powai", "Prabhadevi",
                "Prakasha", "Pulgaon", "Pune", "Pune Division", "Puntamba", "Pural",
                "Purna", "Pusad", "Radhanagari", "Rahata", "Rahimatpur", "Rahuri",
                "Raigarh", "Raireshwar", "Rajapur", "Rajgurunagar", "Rajur", "Rajura",
                "Ralegaon", "Ramewadi", "Ramtek", "Ratnagiri", "Raver", "Renapur",
                "Renavi", "Revadanda", "Revdanda", "Risod", "Roha", "Sailu",
                "Sakol", "Sakoli", "Sakri", "Samudrapur", "Sangameshwar", "Sangamner",
                "Sangli", "Sangola", "Sangrampur Maharashtra", "Saoli", "Saoner", "Sarangkheda",
                "Saswad", "Satana", "Satara", "Satara Division", "Satpati", "Savantvadi",
                "Savda", "Savlaj", "Sawantvadi", "Selu", "Sevagram", "Sewri",
                "Shahada", "Shahapur", "Shedbal", "Shegaon", "Shevgaon", "Shikrapur",
                "Shiraguppi", "Shirala", "Shirdi", "Shirgaon", "Shirol", "Shirpur",
                "Shirud", "Shirwal", "Shivaji Nagar", "Shrigonda", "Sillod", "Sindewahi",
                "Sindhudurg", "Sindi", "Sindkheda", "Sinnar", "Sion Mumbai", "Sironcha",
                "Sirur", "Sivala East Godavari district", "Solapur", "Sonala", "Sonegaon", "Songir",
                "Sonvad", "Soygaon", "Srivardhan", "Surgana", "Taklibhan", "Talbid",
                "Talegaon Dabhade", "Talegaon Dhamdhere", "Taloda", "Talode", "Tarapur", "Tardeo",
                "Tasgaon", "Telhara", "Thalner", "Thane", "Trimbak", "Trombay",
                "Tuljapur", "Tumsar", "Udgir", "Ulhasnagar", "Umarga", "Umarkhed",
                "Umred", "Uran", "Uruli Kanchan", "Vada", "Vadgaon", "Vadner",
                "Vaijapur", "Vairag", "Valsang", "Vangaon", "Varangaon", "Vashi",
                "Vasind", "Vatul", "Velas Maharashtra", "Velneshwar", "Vengurla", "Vijaydurg",
                "Vikhroli", "Vile Parle", "Vinchur", "Virar", "Vita Maharashtra", "Vite",
                "Wadala", "Wadgaon", "Wadner", "Wadwani", "Wagholi", "Wai",
                "Wakad", "Walgaon", "Walki", "Wani", "Wardha", "Warora",
                "Warud", "Washim", "Worli", "Yaval", "Yavatmal", "Yeola"));
        STORED.put("Account Type", List.of(
                "Salaried", "Current", "Saving"));
        STORED.put("Proficiency", List.of(
                "Expert", "Intermediate", "Beginner"));
        STORED.put("Education Level", List.of(
                "PHD", "Masters", "Bachelor", "12th", "10th"));
        STORED.put("Mode of Education", List.of(
                "Regular", "Part-Time", "Open Learning"));
    }

    private static final Random RANDOM = new Random(seed());

    private EorDropdownOptions() {
    }

    private static long seed() {
        String given = ConfigManager.get("eor.seed");
        try {
            return given == null || given.isBlank() ? System.nanoTime() : Long.parseLong(given.trim());
        } catch (NumberFormatException e) {
            return System.nanoTime();
        }
    }

    /** The one source of chance for the choices made while the portal is filled in (repeatable with {@code -Deor.seed}). */
    public static Random random() {
        return RANDOM;
    }

    /** The stored entries of a list; empty when nothing was stored for it. */
    public static List<String> of(String key) {
        List<String> o = STORED.get(key);
        return o == null ? List.of() : o;
    }

    /** True when the entries of this list are stored. */
    public static boolean has(String key) {
        return !of(key).isEmpty();
    }

    /** One entry of the stored list, every entry with the same chance; {@code fallback} when the list is not stored yet. */
    public static String pick(Random r, String key, String[] fallback) {
        List<String> o = of(key);
        return o.isEmpty() ? fallback[r.nextInt(fallback.length)] : o.get(r.nextInt(o.size()));
    }

    /** True for the lines that are not entries: the placeholder of an empty list, the message of a list without a match. */
    public static boolean isPlaceholder(String text) {
        String t = text == null ? "" : text.trim();
        return t.isEmpty() || t.toLowerCase().startsWith("select") || t.toLowerCase().startsWith("no results") || t.toLowerCase().startsWith("no result")
                || t.toLowerCase().startsWith("loading");
    }

    /** Adds the entries a run saw to the file (harvest mode only; never throws). */
    public static synchronized void harvest(String key, List<String> seen) {
        try {
            Map<String, LinkedHashSet<String>> all = new LinkedHashMap<>();
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            if (Files.isRegularFile(HARVEST_FILE)) {
                Map<String, List<String>> old = mapper.readValue(Files.readString(HARVEST_FILE, StandardCharsets.UTF_8), new TypeReference<Map<String, List<String>>>() { });
                old.forEach((k, v) -> all.put(k, new LinkedHashSet<>(v)));
            }
            Set<String> set = all.computeIfAbsent(key, k -> new LinkedHashSet<>());
            for (String s : seen) {
                if (!isPlaceholder(s)) {
                    set.add(s.trim());
                }
            }
            Map<String, List<String>> out = new LinkedHashMap<>();
            all.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
            if (HARVEST_FILE.getParent() != null) {
                Files.createDirectories(HARVEST_FILE.getParent());
            }
            Files.writeString(HARVEST_FILE, mapper.writeValueAsString(out), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Log.warn("The entries of the list \"" + key + "\" could not be stored: " + e.getMessage());
        }
    }
}
