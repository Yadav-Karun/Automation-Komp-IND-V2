import org.openqa.selenium.*;
import org.openqa.selenium.chrome.*;
import java.nio.file.*;
import java.util.*;

/** Dev-only reconnaissance helper: attaches to a running Chrome (port 9222) and executes simple commands. */
public class Recon {
    static final String DUMP_JS = String.join("\n",
        "const out=[];const vis=e=>{const r=e.getBoundingClientRect();const s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'};",
        "const q='input,button,select,textarea,a,[role=button],[role=option],[role=tab],[role=combobox],h1,h2,h3,h4,label,[role=dialog],[class*=toast],[class*=Toast],[role=alert],[class*=error],[class*=Error],th,li[role],p.Mui-error,.Mui-error';",
        "document.querySelectorAll(q).forEach(e=>{if(!vis(e))return;",
        " const t=(e.innerText||'').trim().replace(/ +/g,' ').slice(0,70);",
        " const a=['id','name','type','placeholder','title','role','aria-label','href','value','class','data-testid'].map(k=>{let v=e.getAttribute(k);if(v&&k==='class')v=v.split(' ').slice(0,3).join(' ');if(k==='value'&&e.type==='password')v='***';return v?k+'='+v.slice(0,70):null}).filter(Boolean).join(' | ');",
        " out.push(e.tagName.toLowerCase()+' ['+t+'] '+a)});",
        "return out.join(String.fromCharCode(10));");

    public static void main(String[] args) throws Exception {
        ChromeOptions o = new ChromeOptions();
        o.setExperimentalOption("debuggerAddress", "127.0.0.1:9222");
        WebDriver d = new ChromeDriver(o);
        JavascriptExecutor js = (JavascriptExecutor) d;
        Path shots = Paths.get("tools/recon/out"); Files.createDirectories(shots);
        for (int i = 0; i < args.length; i++) {
            String c = args[i];
            switch (c) {
                case "open" -> { d.get(args[++i]); }
                case "wait" -> Thread.sleep(Long.parseLong(args[++i]));
                case "url" -> System.out.println("URL: " + d.getCurrentUrl() + "  TITLE: " + d.getTitle());
                case "dump" -> System.out.println("--- DUMP ---\n" + js.executeScript(DUMP_JS));
                case "text" -> System.out.println("--- BODY TEXT ---\n" + d.findElement(By.tagName("body")).getText());
                case "shot" -> { byte[] b = ((TakesScreenshot) d).getScreenshotAs(OutputType.BYTES); Files.write(shots.resolve(args[++i] + ".png"), b); }
                case "click" -> { d.findElement(By.xpath(args[++i])).click(); }
                case "jsclick" -> { js.executeScript("arguments[0].click()", d.findElement(By.xpath(args[++i]))); }
                case "type" -> { WebElement e = d.findElement(By.xpath(args[++i])); String v = sub(args[++i]); e.sendKeys(v); }
                case "clear" -> { WebElement e = d.findElement(By.xpath(args[++i])); e.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE); }
                case "upload" -> { d.findElement(By.xpath(args[++i])).sendKeys(args[++i]); }
                case "js" -> System.out.println("JS: " + js.executeScript(args[++i]));
                case "html" -> { String x = args[++i]; System.out.println("--- HTML ---\n" + d.findElement(By.xpath(x)).getAttribute("outerHTML")); }
                case "frame" -> d.switchTo().frame(d.findElement(By.xpath(args[++i])));
                case "grabtemp" -> { String t = d.findElement(By.tagName("body")).getText(); var m = java.util.regex.Pattern.compile("Temporary password: *(\\S+)").matcher(t); if(m.find()) Files.writeString(shots.resolve("tmp.txt"), m.group(1)); System.out.println("grabbed=" + new java.io.File("tools/recon/out/tmp.txt").exists()); }
                case "default" -> d.switchTo().defaultContent();
                case "tabs" -> { for (String h : d.getWindowHandles()) { d.switchTo().window(h); System.out.println("TAB " + h + " " + d.getCurrentUrl()); } }
                case "tab" -> { List<String> hs = new ArrayList<>(d.getWindowHandles()); d.switchTo().window(hs.get(Integer.parseInt(args[++i]))); }
                case "newtab" -> d.switchTo().newWindow(WindowType.TAB);
                default -> throw new IllegalArgumentException("unknown cmd " + c);
            }
        }
        // do NOT quit: keep Chrome alive
    }

    static String sub(String v) throws Exception {
        if (!v.startsWith("@")) return v;
        String json = Files.readString(Paths.get("config/secrets.local.properties"));
        return switch (v) {
            case "@ADMIN_EMAIL" -> get(json, "sandbox.admin.email");
            case "@ADMIN_PASS" -> get(json, "sandbox.admin.password");
            case "@TEMP_PASS" -> Files.readString(Paths.get("tools/recon/out/tmp.txt")).trim();
            case "@GENERIC_PASS" -> get(json, "client.test.password");
            default -> v;
        };
    }
    static String get(String json, String key) {
        var m = java.util.regex.Pattern.compile("(?m)^" + java.util.regex.Pattern.quote(key) + "=(.*)$").matcher(json);
        if (!m.find()) throw new RuntimeException("missing " + key); return m.group(1);
    }
}
