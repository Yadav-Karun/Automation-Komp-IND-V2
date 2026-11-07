package pages;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

import base.BasePage;
import utils.Xp;

/**
 * Admin > Clients > one client's profile (/clients/&lt;id&gt;). Verified live (2026-11-07):
 *  - header: h1 "&lt;legal name&gt;(&lt;type&gt;)", status badge, e-mail; summary tiles NAME / PHONE NO. / COMPANY NO / MSA ID
 *  - tabs "Company Details" (cards About Organization, Mailing Address, Legal Details), "Point of Contact" (Business, Finance, HR),
 *    "MSA" (signed document with Preview / Download buttons)
 *  - cards are &lt;dl&gt;&lt;div&gt;&lt;dt&gt;label&lt;/dt&gt;&lt;dd&gt;value&lt;/dd&gt;&lt;/div&gt;&lt;/dl&gt;; every card has an edit (pencil) button (never clicked here: read-only)
 *  - an empty value is shown as a dash.
 */
public class ClientProfilePage extends BasePage {

    private final By title = By.xpath("//main//h1");
    private final By status = By.xpath("//main//h1/following-sibling::div[1]/span[1]");
    private final By email = By.xpath("//main//h1/following-sibling::div[1]/span[2]");
    private final By header = By.xpath("//main//h1/ancestor::div[contains(@class,'rounded-[11px]')][1]");
    private final By back = By.xpath("//button[normalize-space()='Back to Clients']");

    /** [label, value] of every label/value div inside the element (the summary tiles). */
    private static final String TILES_JS = String.join("\n",
            "const root=arguments[0];const out=[];",
            "root.querySelectorAll('div').forEach(l=>{const c=(typeof l.className==='string')?l.className:'';",
            "  if(l.children.length===0&&/uppercase/.test(c)){const v=l.nextElementSibling;",
            "    out.push([l.textContent.trim(), v?v.innerText.trim().replace(/\\s+/g,' '):'']);}});",
            "return out;");

    /** [card title, [[label, value, number of links/buttons in the value], ...]] for every card of the open tab. */
    private static final String CARDS_JS = String.join("\n",
            "const out=[];",
            "document.querySelectorAll('main h3').forEach(h=>{const card=h.parentElement.parentElement;",
            "  const rows=[...card.querySelectorAll('dt')].map(dt=>{const dd=dt.nextElementSibling;",
            "    return [dt.textContent.trim(), dd?dd.innerText.trim().replace(/\\s+/g,' '):'', dd?dd.querySelectorAll('a,button').length:0];});",
            "  out.push([h.innerText.trim(), rows]);});",
            "return out;");

    public ClientProfilePage(WebDriver driver) {
        super(driver);
    }

    /**
     * From the Clients list: search the company, click its row (the EXACT name: names that only differ in case also match the
     * search) and wait until the profile has loaded.
     */
    public void open(ClientsPage clients, String legalName) {
        clients.open();
        clients.findClient(legalName);
        click(By.xpath("//tbody/tr[.//div[normalize-space()=" + Xp.literal(legalName) + "]]/td[1]"));
        waitFor(timeout).until(d -> d.getCurrentUrl().matches(".*/clients/[0-9a-fA-F-]{20,}.*"));
        visible(title);
        waitFor(timeout).until(d -> !summary().getOrDefault("name", "").isEmpty());
    }

    public boolean isOpen() {
        return driver.getCurrentUrl().matches(".*/clients/[0-9a-fA-F-]{20,}.*") && isDisplayedNow(title);
    }

    // ---- header and summary tiles ---------------------------------------------------------------------

    /** Full heading text, e.g. "KOMP IND Regression Client 2026...(EOR)". */
    public String titleText() {
        return text(title);
    }

    public String statusText() {
        return text(status);
    }

    public String headerEmail() {
        return text(email);
    }

    /** NAME / PHONE NO. / COMPANY NO / MSA ID, keys in lower case ("name", "phone no.", "company no", "msa id"). */
    public Map<String, String> summary() {
        return pairs(TILES_JS, driver.findElement(header));
    }

    // ---- tabs ------------------------------------------------------------------------------------------

    public void openTab(String tab) {
        click(By.xpath("//button[@role='tab'][normalize-space()=" + Xp.literal(tab) + "]"));
        waitFor(timeout).until(d -> "true".equals(d.findElement(By.xpath("//button[@role='tab'][normalize-space()=" + Xp.literal(tab) + "]"))
                .getAttribute("aria-selected")));
    }

    /** Cards of the open tab: card title -> (label (lower case) -> value). The empty-value dash is returned as "". */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, String>> cards() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        Object o = ((JavascriptExecutor) driver).executeScript(CARDS_JS);
        for (Object card : (List<Object>) o) {
            List<Object> c = (List<Object>) card;
            Map<String, String> rows = new LinkedHashMap<>();
            for (Object row : (List<Object>) c.get(1)) {
                List<Object> r = (List<Object>) row;
                rows.put(String.valueOf(r.get(0)).toLowerCase(), clean(String.valueOf(r.get(1))));
                rows.put(String.valueOf(r.get(0)).toLowerCase() + "#controls", String.valueOf(r.get(2)));
            }
            out.put(String.valueOf(c.get(0)), rows);
        }
        return out;
    }

    /** Waits until the open tab has rendered its cards, then returns them. */
    public Map<String, Map<String, String>> waitForCards() {
        waitFor(timeout).until(d -> !cards().isEmpty());
        return cards();
    }

    // ---- MSA tab ---------------------------------------------------------------------------------------

    /** Name of the signed document card on the MSA tab ("" when there is none). */
    public String msaDocumentName() {
        Object o = ((JavascriptExecutor) driver).executeScript(
                "const p=document.querySelector('[role=tabpanel][data-state=active]');if(!p)return '';"
                        + "const s=p.querySelector('span');return s?s.innerText.trim():'';");
        return o == null ? "" : o.toString();
    }

    public boolean hasPreviewButton() {
        return !driver.findElements(By.xpath("//button[@title='Preview signed document']")).isEmpty();
    }

    public boolean isDownloadEnabled() {
        List<org.openqa.selenium.WebElement> b = driver.findElements(By.xpath("//button[@title='Download signed document']"));
        return !b.isEmpty() && b.get(0).isEnabled();
    }

    /** Clicks Preview and returns "&lt;mime&gt;:&lt;bytes&gt;" of the document the inline viewer received (proves the signed PDF opens). */
    public String previewSignedMsa() {
        click(By.xpath("//button[@title='Preview signed document']"));
        By viewer = By.xpath("//iframe[@title='Signed MSA Document']");
        waitFor(timeout).until(d -> {
            List<org.openqa.selenium.WebElement> f = d.findElements(viewer);
            return !f.isEmpty() && String.valueOf(f.get(0).getAttribute("src")).startsWith("blob:");
        });
        Object r = ((JavascriptExecutor) driver).executeAsyncScript(
                "const done=arguments[arguments.length-1];const f=document.querySelector('iframe[title=\"Signed MSA Document\"]');"
                        + "if(!f||!f.src){done('no-src');return;}"
                        + "fetch(f.src).then(r=>r.blob()).then(b=>done(b.type+':'+b.size)).catch(e=>done('error:'+e));");
        return String.valueOf(r);
    }

    /** Closes the full-screen "Signed MSA Document" viewer (it covers the page, so 'Back to Clients' is not clickable while it is open). */
    public void closePreview() {
        By viewer = By.xpath("//iframe[@title='Signed MSA Document']");
        if (driver.findElements(viewer).isEmpty()) {
            return;
        }
        driver.findElement(By.tagName("body")).sendKeys(org.openqa.selenium.Keys.ESCAPE);
        try {
            new org.openqa.selenium.support.ui.WebDriverWait(driver, java.time.Duration.ofSeconds(4))
                    .until(d -> d.findElements(viewer).isEmpty());
        } catch (org.openqa.selenium.TimeoutException stillOpen) {
            // Escape did not close it: use the X button of the viewer header
            ((JavascriptExecutor) driver).executeScript(
                    "const x=document.querySelector('button svg.lucide-x');if(x){x.closest('button').click();}");
            waitFor(timeout).until(d -> d.findElements(viewer).isEmpty());
        }
    }

    public void backToClients() {
        click(back);
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, String> pairs(String script, Object arg) {
        Map<String, String> out = new LinkedHashMap<>();
        Object o = ((JavascriptExecutor) driver).executeScript(script, arg);
        for (Object row : (List<Object>) o) {
            List<Object> r = (List<Object>) row;
            out.put(String.valueOf(r.get(0)).toLowerCase(), clean(String.valueOf(r.get(1))));
        }
        return out;
    }

    /** An empty value is displayed as a dash (or an unreadable replacement character): return it as "". */
    static String clean(String v) {
        String t = v == null ? "" : v.trim();
        return t.matches("[\\u2014\\u2013\\u2212\\-\\uFFFD\\s]*") ? "" : t;
    }
}
