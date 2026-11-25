package utils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;

/**
 * "The page has finished": used before every picture (user, 2026-11-17: "take the screenshot after the action is performed and the visible page is fully loaded; wait for it to update;
 * nothing may be in a running state"). A picture is taken only when
 * <ul>
 *   <li>the document is complete,</li>
 *   <li>no request of the page is still on its way (every fetch / XMLHttpRequest of the page is counted by a small script that is put into every new page),</li>
 *   <li>no loading sign is visible (spinner, skeleton, progress bar, busy area),</li>
 *   <li>and - as far as the page allows - nothing on the page has changed for half a second.</li>
 * </ul>
 * The first three are required, the quiet half second is given up after {@code screenshot.settle.max.seconds} (default 10), so a page with a clock cannot hold the run back for ever.
 */
public final class PageReady {

    private static final String SCRIPT = String.join("\n",
            "(function(){",
            " if(window.__kompReady) return; window.__kompReady=true; window.__kompPending=0; window.__kompLastChange=Date.now();",
            " const of=window.fetch; if(of){ window.fetch=function(){ window.__kompPending++; const done=()=>{window.__kompPending=Math.max(0,window.__kompPending-1);window.__kompLastChange=Date.now();};",
            "   return of.apply(this,arguments).then(r=>{done();return r;},e=>{done();throw e;}); }; }",
            " const ox=XMLHttpRequest.prototype.send; XMLHttpRequest.prototype.send=function(){ window.__kompPending++; let finished=false;",
            "   const done=()=>{ if(finished) return; finished=true; window.__kompPending=Math.max(0,window.__kompPending-1); window.__kompLastChange=Date.now(); };",
            "   this.addEventListener('loadend',done); return ox.apply(this,arguments); };",
            " const start=()=>{ if(document.body){ new MutationObserver(()=>{window.__kompLastChange=Date.now();}).observe(document.body,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:['class','src','value','aria-busy']}); } else { setTimeout(start,50);} };",
            " start();",
            "})();");

    /** Hard conditions (complete, no request on its way, no loading sign) and the soft one (quiet for 0.5 s) as a list of what still holds the page back. */
    private static final String STATE = String.join("\n",
            "const busy=[];",
            "if(document.readyState!=='complete') busy.push('the page is still loading');",
            "if((window.__kompPending||0)>0) busy.push((window.__kompPending)+' request(s) on their way');",
            "const sel=\".animate-spin,[class*='skeleton' i],[class*='spinner' i],[role='progressbar'],[aria-busy='true'],svg.lucide-loader,svg.lucide-loader-circle,svg.lucide-loader-2\";",
            "const visible=e=>{const r=e.getBoundingClientRect();const s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0';};",
            "const sp=Array.from(document.querySelectorAll(sel)).filter(visible).filter(e=>!e.closest('#komp-run-banner'));",
            "if(sp.length) busy.push(sp.length+' loading sign(s) visible');",
            "const quiet=Date.now()-(window.__kompLastChange||0)>=500;",
            "return JSON.stringify({busy:busy,quiet:quiet});");

    private PageReady() {
    }

    /** Puts the counting script into every new page of the browser (also called when a picture is about to be taken, for a page that was opened before). */
    public static void install(WebDriver driver) {
        try {
            ((ChromiumDriver) driver).executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", Map.of("source", SCRIPT));
        } catch (RuntimeException e) {
            Log.warn("The page-ready script could not be installed: " + e.getClass().getSimpleName());
        }
    }

    /** Waits until the page is fully loaded and has stopped updating (see the class text); never throws, never waits longer than {@code screenshot.settle.max.seconds}. */
    public static void waitSettled(WebDriver driver) {
        if (!ConfigManager.getBool("screenshot.wait.ready", true)) {
            return;
        }
        int max = ConfigManager.getInt("screenshot.settle.max.seconds", 10);
        Instant end = Instant.now().plus(Duration.ofSeconds(max));
        Instant softEnd = Instant.now().plus(Duration.ofSeconds(Math.min(max, 4)));
        String last = "";
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript(SCRIPT); // idempotent: a page that was opened before the script was installed starts counting now
            while (true) {
                String state = String.valueOf(js.executeScript(STATE));
                last = state;
                boolean noBusy = state.contains("\"busy\":[]");
                boolean quiet = state.contains("\"quiet\":true");
                if (noBusy && (quiet || Instant.now().isAfter(softEnd))) {
                    return;
                }
                if (Instant.now().isAfter(end)) {
                    Log.info("The page was still updating when its picture was taken (" + last + ").");
                    return;
                }
                Thread.sleep(200); // documented: the page needs a moment to finish what the action started
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // no page to ask (a PDF viewer, an error page, a closed window): the picture is taken as it is
        }
    }
}
