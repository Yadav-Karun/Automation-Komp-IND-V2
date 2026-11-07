package utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import model.ClientData;

/** Builds a unique, valid-format ClientData for every execution so reruns never collide. */
public final class TestDataGenerator {

    private static final SecureRandom RND = new SecureRandom();
    private static final String ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String GST_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private TestDataGenerator() {
    }

    /**
     * Data for the client of the happy flow: an anime that is not in the existing-clients file yet. Client name = the anime, contact person =
     * its main character (e-mail songoku@yopmail.com), Business / Finance / HR contacts = three other characters (see {@link ClientNames}).
     */
    public static ClientData generate() {
        return build(ClientNames.pick(), null, null, null);
    }

    /** Data for the ONE client of the edge-case class: the same anime scheme (another anime than the happy-flow client). */
    public static ClientData generateEdgeCase() {
        return build(ClientNames.pick(), null, null, null);
    }

    /** Puts an anime onto existing client data: legal name, registration contact (main character) and the three wizard contacts. */
    public static void applyPick(ClientData d, ClientNames.Pick p) {
        d.legalName = p.name;
        d.pocName = p.characters[0];
        d.email = p.email(0);
        d.business.name = p.characters[1];
        d.business.email = p.email(1);
        d.finance.name = p.characters[2];
        d.finance.email = p.email(2);
        d.hr.name = p.characters[3];
        d.hr.email = p.email(3);
    }

    /**
     * Throw-away data for the negative cases (valid base values that a case then breaks on purpose): unique, timestamped names that are
     * NOT anime names and not in the existing-clients file. A client created by such a case by accident is a defect of the application.
     */
    public static ClientData generateScratch() {
        return build(null, null, null, null);
    }

    /** Writes the data of a run (no passwords are ever part of it) so the run can be reproduced or re-checked later. */
    public static void save(ClientData d, java.nio.file.Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), d);
        } catch (IOException e) {
            Log.warn("Test data file not written: " + e.getMessage());
        }
    }

    public static ClientData load(java.nio.file.Path file) {
        try {
            return new ObjectMapper().readValue(file.toFile(), ClientData.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the client data file " + file + ": " + e.getMessage(), e);
        }
    }

    private static ClientData build(ClientNames.Pick pick, String legalNamePrefix, String pocNamePrefix, String emailPrefixOverride) {
        JsonNode cfg = readTemplate();
        JsonNode org = cfg.path("organization");
        JsonNode contacts = cfg.path("contacts");

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String rand = randomLower(3);
        String suffix = stamp + rand;
        String domain = cfg.path("emailDomain").asText("yopmail.com");
        String emailPrefix = emailPrefixOverride != null ? emailPrefixOverride : cfg.path("emailPrefix").asText("komp.ind");

        ClientData d = new ClientData();
        d.runId = suffix;
        d.legalName = (legalNamePrefix != null ? legalNamePrefix : cfg.path("legalNamePrefix").asText("KOMP IND Regression Client")) + " " + suffix;
        d.clientType = ConfigManager.get("client.type", "EOR").toUpperCase();
        d.country = ConfigManager.get("client.country", "India");
        d.pocName = (pocNamePrefix != null ? pocNamePrefix : cfg.path("pocNamePrefix").asText("Regression POC")) + " " + suffix;
        d.email = emailPrefix + "." + suffix + "@" + domain;
        d.mobile = phone();

        d.yearsInIndustry = org.path("yearsInIndustry").asText("7");
        d.website = org.path("website").asText("https://www.example.com");
        d.description = org.path("productOrServiceDescription").asText("Regression test data");
        d.pincode = org.path("pincode").asText("201009");
        d.city = org.path("city").asText("Ghaziabad");
        d.address = org.path("addressLine").asText("Plot 12, Test Area");
        d.companyType = org.path("companyType").asText("Private Ltd");
        d.cin = cin();
        d.gst = gstin();
        d.cinDocPath = ConfigManager.document("clientCIN");
        d.taxDocPath = ConfigManager.document("clientGSTIN");

        fill(d.business, contacts.path("business"), emailPrefix, "biz", suffix, domain);
        fill(d.finance, contacts.path("finance"), emailPrefix, "fin", suffix, domain);
        fill(d.hr, contacts.path("hr"), emailPrefix, "hr", suffix, domain);
        if (pick != null) {
            applyPick(d, pick);
        }
        return d;
    }

    private static void fill(ClientData.Contact c, JsonNode n, String prefix, String tag, String suffix, String domain) {
        c.name = n.path("namePrefix").asText("Contact") + " " + suffix;
        c.designation = n.path("designation").asText("Manager");
        c.email = prefix + "." + tag + "." + suffix + "@" + domain;
        c.phone = phone();
    }

    private static JsonNode readTemplate() {
        try {
            return new ObjectMapper().readTree(Files.readString(Paths.get("testdata/client-onboarding.json")));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read testdata/client-onboarding.json", e);
        }
    }

    /** 10 digits, starts with 9. */
    public static String phone() {
        StringBuilder sb = new StringBuilder("9");
        for (int i = 0; i < 9; i++) {
            sb.append(RND.nextInt(10));
        }
        return sb.toString();
    }

    /** CIN format: [LU] + 5 digits + 2 letters + 4-digit year + 3 letters + 6 digits (21 chars). */
    public static String cin() {
        return "U" + (10000 + RND.nextInt(89999)) + "MH" + (2010 + RND.nextInt(14)) + "PTC" + (100000 + RND.nextInt(899999));
    }

    /** GSTIN: state(2) + PAN(10) + entity(1) + 'Z' + checksum, with a correct mod-36 checksum. */
    public static String gstin() {
        String pan = "" + letter() + letter() + letter() + 'C' + letter()
                + (1000 + RND.nextInt(8999)) + letter();
        String base = "27" + pan + "1Z";
        return base + checksum(base);
    }

    static char checksum(String base) {
        int sum = 0;
        for (int i = 0; i < base.length(); i++) {
            int v = GST_CHARS.indexOf(base.charAt(i));
            int prod = v * (i % 2 == 0 ? 1 : 2);
            sum += prod / 36 + prod % 36;
        }
        return GST_CHARS.charAt((36 - sum % 36) % 36);
    }

    private static char letter() {
        return ALPHA.charAt(RND.nextInt(26));
    }

    private static String randomLower(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append((char) ('a' + RND.nextInt(26)));
        }
        return sb.toString();
    }
}
