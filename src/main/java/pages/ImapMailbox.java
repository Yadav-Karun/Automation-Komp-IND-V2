package pages;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.MimeUtility;
import javax.mail.search.AndTerm;
import javax.mail.search.RecipientStringTerm;
import javax.mail.search.SearchTerm;
import javax.mail.search.SubjectTerm;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.support.ui.WebDriverWait;

import utils.ConfigManager;
import utils.Log;
import utils.SecretMasker;

/**
 * The mailbox of an EOR employee read by a MAIL PROGRAM (IMAP), not by a browser: Yopmail asks the automated browser to "confirm you're human" and never lets it in.
 * <p>
 * Settings ({@code config/secrets.local.properties}, never committed): {@code imap.user}, {@code imap.password} (for Gmail an APP PASSWORD, not the account password),
 * {@code imap.host} (default imap.gmail.com), {@code imap.port} (993), and {@code imap.address.base} = the mailbox address; the employees get the plus-address
 * {@code <local>+<name><m|f>@<domain>}, every mail to it lands in this one mailbox and is found by the address in its "To" line.
 * <p>
 * "Accepting" the Letter of Intent is opening the web address behind its link "Accept Offer": the test reads that address from the HTML of the mail and opens it in a NEW TAB
 * of the test browser (one window for everything), exactly what a click on the link does.
 */
public class ImapMailbox implements EmployeeMailbox {

    private static final Pattern PASSWORD = Pattern.compile("(?im)password\\s*:?\\s*(\\S+)");

    private final WebDriver driver;
    private String address = "";
    private String lastSubject = "";
    private String lastHtml = "";
    private String lastText = "";

    public ImapMailbox(WebDriver testBrowser) {
        this.driver = testBrowser;
    }

    @Override
    public void open(String employeeAddress) {
        this.address = employeeAddress.trim().toLowerCase(Locale.ROOT);
        lastSubject = "";
        lastHtml = "";
        lastText = "";
    }

    @Override
    public void close() {
        // nothing is open: the mailbox is read per mail
    }

    @Override
    public WebDriver mailDriver() {
        return null;
    }

    @Override
    public String acceptLetterOfIntent(int seconds) {
        read("Letter of intent", seconds);
        String href = linkWithText("accept");
        if (href.isEmpty()) {
            throw new AssertionError("The mail \"" + lastSubject + "\" has no link \"Accept Offer\". The links of the mail: " + links());
        }
        String main = driver.getWindowHandle();
        String answer;
        driver.switchTo().newWindow(WindowType.TAB);
        String tab = driver.getWindowHandle();
        try {
            driver.get(href);
            try {
                new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> !d.findElements(By.xpath("//*[contains(normalize-space(.),'Congratulations')]")).isEmpty());
            } finally {
                answer = driver.findElements(By.tagName("body")).isEmpty() ? "" : driver.findElement(By.tagName("body")).getText();
            }
        } finally {
            driver.switchTo().window(tab);
            driver.close();
            driver.switchTo().window(main);
        }
        return "The mail \"" + lastSubject + "\" arrived; the page that its link \"Accept Offer\" opens says: \"" + shorten(answer) + "\"";
    }

    @Override
    public String readLoginPassword(int seconds) {
        read("Candidate Login", seconds);
        Matcher m = PASSWORD.matcher(lastText);
        if (!m.find()) {
            throw new AssertionError("The mail \"" + lastSubject + "\" does not contain a password. It says: \"" + shorten(lastText) + "\"");
        }
        String password = m.group(1).trim();
        SecretMasker.register(password);
        return password;
    }

    @Override
    public String readMail(String subjectPart, int seconds) {
        read(subjectPart, seconds);
        return lastText;
    }

    @Override
    public List<String> links() {
        List<String> out = new ArrayList<>();
        if (!lastHtml.isEmpty()) {
            for (Element a : Jsoup.parse(lastHtml).select("a[href]")) {
                out.add(a.text().trim() + " -> " + a.attr("href"));
            }
        }
        return out;
    }

    @Override
    public String portalLink() {
        return linkWithText("access portal");
    }

    @Override
    public String mailText() {
        return lastText;
    }

    /** The address of the first link of the last mail whose text contains the fragment (case ignored), or "". */
    private String linkWithText(String fragment) {
        return linkIn(lastHtml, fragment);
    }

    /** Public for the offline check of the link search. */
    public static String linkIn(String html, String fragment) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        for (Element a : doc.select("a[href]")) {
            if (a.text().toLowerCase(Locale.ROOT).contains(fragment.toLowerCase(Locale.ROOT))) {
                return a.attr("href").trim();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ the mailbox

    /** Waits for the newest mail to this address whose subject contains the text and keeps its subject, HTML and text. */
    private void read(String subjectPart, int seconds) {
        if (address.isEmpty()) {
            throw new IllegalStateException("The mailbox has no address: open(address) first.");
        }
        long end = System.currentTimeMillis() + seconds * 1000L;
        Exception last = null;
        while (true) {
            try {
                if (fetch(subjectPart)) {
                    return;
                }
            } catch (MessagingException | java.io.IOException e) {
                last = e;
                Log.warn("The mailbox could not be read (" + e.getMessage() + "): trying again.");
            }
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("No mail with \"" + subjectPart + "\" in the subject arrived for " + address + " within " + seconds + " seconds"
                        + (last == null ? "." : " (last error: " + last.getMessage() + ")."));
            }
            try {
                Thread.sleep(5000); // documented external-poll interval: mail delivery is asynchronous
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the mail.");
            }
        }
    }

    private boolean fetch(String subjectPart) throws MessagingException, java.io.IOException {
        String host = ConfigManager.get("imap.host", "imap.gmail.com");
        int port = ConfigManager.getInt("imap.port", 993);
        String user = ConfigManager.get("imap.user");
        String pass = ConfigManager.get("imap.password");
        if (user == null || pass == null) {
            throw new IllegalStateException("imap.user and imap.password (for Gmail an app password) are not set in config/secrets.local.properties.");
        }
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.connectiontimeout", "20000");
        props.put("mail.imaps.timeout", "30000");
        Session session = Session.getInstance(props);
        try (Store store = session.getStore("imaps")) {
            store.connect(host, port, user, pass);
            SecretMasker.register(pass);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                SearchTerm term = new AndTerm(new RecipientStringTerm(Message.RecipientType.TO, address), new SubjectTerm(subjectPart));
                Message[] found = inbox.search(term);
                if (found.length == 0) {
                    return false;
                }
                Message newest = java.util.Arrays.stream(found).max(Comparator.comparing(m -> {
                    try {
                        return m.getSentDate() == null ? new java.util.Date(0) : m.getSentDate();
                    } catch (MessagingException e) {
                        return new java.util.Date(0);
                    }
                })).orElseThrow();
                lastSubject = newest.getSubject() == null ? subjectPart : MimeUtility.decodeText(newest.getSubject());
                StringBuilder html = new StringBuilder();
                StringBuilder text = new StringBuilder();
                collect(newest, html, text);
                lastHtml = html.toString();
                lastText = text.length() > 0 ? text.toString() : Jsoup.parse(lastHtml).text();
                return true;
            } finally {
                inbox.close(false);
            }
        }
    }

    /** The HTML and the plain text of a mail (every part of it). */
    private static void collect(Part part, StringBuilder html, StringBuilder text) throws MessagingException, java.io.IOException {
        if (part.isMimeType("text/html")) {
            html.append((String) part.getContent());
        } else if (part.isMimeType("text/plain")) {
            text.append((String) part.getContent()).append('\n');
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                collect(mp.getBodyPart(i), html, text);
            }
        }
    }

    private static String shorten(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() > 220 ? t.substring(0, 220) + "..." : t;
    }
}
