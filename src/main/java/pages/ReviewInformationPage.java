package pages;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import base.BasePage;

/** Client onboarding wizard - step 3: Review of Information. Values are parsed from the page text. */
public class ReviewInformationPage extends BasePage {

    private static final Set<String> ORG_LABELS = Set.of("Organization Name", "Company Type", "Years in Industry",
            "Website", "Email", "Mobile Number", "CIN", "Tax ID Number (GST Number)", "Registered Address",
            "Mailing Address");
    private static final Set<String> POC_LABELS = Set.of("Name", "Designation", "Email", "Contact Number");

    private final By confirm = By.xpath("//button[normalize-space()='Confirm & Continue']");

    public static class Review {
        public Map<String, String> organization = new LinkedHashMap<>();
        public Map<String, String> business = new LinkedHashMap<>();
        public Map<String, String> finance = new LinkedHashMap<>();
        public Map<String, String> hr = new LinkedHashMap<>();
        public List<String> lines;
    }

    public ReviewInformationPage(WebDriver driver) {
        super(driver);
    }

    public void waitUntilOpen() {
        visible(confirm);
    }

    public Review read() {
        List<String> lines = Arrays.stream(bodyText().split("\\R")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        // the step bar repeats these titles earlier on the page; the content sections are the LAST occurrences
        int org = lines.lastIndexOf("Organization Details");
        int poc = lines.lastIndexOf("Points of Contact");
        int biz = lines.indexOf("Business POC");
        int fin = lines.indexOf("Finance POC");
        int hr = lines.indexOf("HR POC");
        int end = lines.size();
        if (org < 0 || poc < 0 || biz < 0 || fin < 0 || hr < 0) {
            throw new AssertionError("Review page sections not found. Page text was: " + lines);
        }
        Review r = new Review();
        r.lines = lines;
        r.organization = pairs(lines, org, poc, ORG_LABELS);
        r.business = pairs(lines, biz, fin, POC_LABELS);
        r.finance = pairs(lines, fin, hr, POC_LABELS);
        r.hr = pairs(lines, hr, end, POC_LABELS);
        return r;
    }

    private static Map<String, String> pairs(List<String> lines, int from, int to, Set<String> labels) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = from; i < to - 1; i++) {
            if (labels.contains(lines.get(i)) && !m.containsKey(lines.get(i))) {
                m.put(lines.get(i), lines.get(i + 1));
            }
        }
        return m;
    }

    public boolean pageMentions(String text) {
        return bodyText().contains(text);
    }

    public void scrollToBottom() {
        scrollIntoView(confirm);
    }

    public void confirmAndContinue() {
        scrollIntoView(confirm);
        click(confirm);
    }
}
