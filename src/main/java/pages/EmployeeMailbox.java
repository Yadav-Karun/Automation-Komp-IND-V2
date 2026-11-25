package pages;

import java.util.List;

import org.openqa.selenium.WebDriver;

/**
 * The mailbox of an EOR employee as the tests use it: the "Letter of intent" mail (its link "Accept Offer"), the "Candidate Login" mail (the password) and the
 * "Welcome Aboard" mail (login link). Two ways to read it: {@link EorMailbox} (Yopmail, in a tab of the test browser) and {@link ImapMailbox} (a real mailbox read by a mail
 * program, no browser: Yopmail asks the automated browser to "confirm you're human"). {@code -Dmail.provider=yopmail|imap} chooses.
 */
public interface EmployeeMailbox {

    /** Opens the mailbox of this address (the address the employee was registered with). */
    void open(String address);

    /** Opens the mail "Letter of intent", follows its link "Accept Offer" and returns what the page that opens says ("Congratulations"). */
    String acceptLetterOfIntent(int seconds);

    /** Reads the password from the mail "Candidate Login". */
    String readLoginPassword(int seconds);

    /** Finds the mail whose subject contains the text (waits up to {@code seconds}) and returns its text; its links can then be read with {@link #links()}. */
    String readMail(String subjectPart, int seconds);

    /** The links (text -> address) of the mail that was read last. */
    List<String> links();

    /** The address behind the link "Access Portal" of the mail that was read last ("" when there is none). */
    String portalLink();

    /** The text of the mail that was read last. */
    String mailText();

    /** Leaves the mailbox (closes the tab of the mail). */
    void close();

    /** The browser that shows the mail (for a picture of it), or null when the mail is not shown in a browser. */
    WebDriver mailDriver();
}
