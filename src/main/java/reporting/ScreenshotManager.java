package reporting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;

import utils.ConfigManager;
import utils.Log;

/**
 * Evidence screenshots. EVERY screenshot shows the WHOLE page, not only what fits on the screen: the KOMP pages scroll inside their own panel
 * (the window itself does not scroll), so the part below the fold - e.g. the document rows of a client profile, or the last rows of a Clients
 * list - would be missing from a normal screenshot.
 * <p>
 * How: for a page whose content is taller than the window, the browser view is enlarged for a moment (Chrome DevTools "device metrics"), the page
 * is given time to settle (the app recomputes the height of its table and animates it for 300 ms), the real end of the content is measured (the
 * sidebar and sticky / fixed parts are ignored), the page is captured down to that point, and the view is restored and settled again (scroll
 * positions are kept), so the next test step meets the page as it was.
 * <p>
 * Pages that fit, pages that only overflow in the sidebar, and pages with something open on top (dialog, menu, list, PDF viewer / iframe,
 * full-screen overlay - resizing could close or blank it) get a normal screenshot.
 * <p>
 * Options: -Dscreenshot.fullpage=false switches it off; -Dscreenshot.max.height (default 6000 px) caps the height;
 * -Dscreenshot.settle.ms (default 350) is the minimum wait after each resize.
 */
public final class ScreenshotManager {

    /**
     * null = take a normal screenshot (something is open on top of the page: a dialog, menu, list, a PDF viewer / iframe or a full-screen overlay -
     * resizing the view would re-render it, e.g. the PDF viewer of the signed MSA goes blank);
     * else [innerWidth, innerHeight, extra scroll height, devicePixelRatio]. The extra height ignores the sidebar (a left rail whose own menu
     * scrolls). The scroll positions are remembered for {@link #RESTORE_SCROLL_JS}.
     */
    private static final String MEASURE_JS = String.join("\n",
            "const big=e=>{const r=e.getBoundingClientRect();return r.width>20&&r.height>20;};",
            "const over='[role=dialog],[role=alertdialog],[aria-modal=true],[role=menu],[role=listbox],[data-slot=popover-content],"
                    + "[data-radix-popper-content-wrapper],iframe,embed,object';",
            "if([...document.querySelectorAll(over)].some(big)) return null;",
            "const vw=window.innerWidth, vh=window.innerHeight;",
            "let extra=Math.max(0, document.documentElement.scrollHeight-vh);",
            "for(const e of document.querySelectorAll('body *')){",
            "  const s=getComputedStyle(e);",
            "  const r=e.getBoundingClientRect();",
            "  if(s.position==='fixed'){",
            "    if(r.width>=vw*0.9 && r.height>=vh*0.9 && !e.querySelector('main')) return null; }",
            "  const rail=r.left<40 && r.width<=vw*0.3;",
            "  if(!rail && e.clientHeight>=100 && (s.overflowY==='auto'||s.overflowY==='scroll') && e.scrollHeight>e.clientHeight+4){",
            "    extra=Math.max(extra,e.scrollHeight-e.clientHeight); }",
            "}",
            "window.__shotScroll=[];",
            "document.querySelectorAll('*').forEach(e=>{ if(e.scrollTop>0) window.__shotScroll.push([e,e.scrollTop]); });",
            "return [vw, vh, extra, window.devicePixelRatio||1];");

    /** Puts the scroll positions back (the enlarged view had no need to scroll, so they were reset). */
    private static final String RESTORE_SCROLL_JS =
            "(window.__shotScroll||[]).forEach(p=>{try{p[0].scrollTop=p[1];}catch(e){}});window.__shotScroll=null;return 1;";

    /**
     * Waits until the layout stopped moving: arguments = [minimum wait ms, maximum wait ms]; returns the bottom (px from the top of the page) of the
     * real content. "Stable" = the measured bottom did not change for 3 polls (60 ms) and no finite CSS transition / animation is running
     * (endless spinners are ignored). Content = text / leaf elements that are not in a fixed or sticky part, not in the left rail (sidebar),
     * and not clipped away by a scroll container; a full-height decorative element is ignored.
     */
    private static final String SETTLE_JS = String.join("\n",
            "const minWait=arguments[0], maxWait=arguments[1], done=arguments[arguments.length-1];",
            "const measure=()=>{",
            "  const vw=window.innerWidth, vh=window.innerHeight; let best=0;",
            "  const walk=(el,clip)=>{",
            "    const s=getComputedStyle(el);",
            "    if(s.display==='none'||s.visibility==='hidden'||s.position==='fixed'||s.position==='sticky') return;",
            "    const t=el.tagName; if(t==='SCRIPT'||t==='STYLE'||t==='NOSCRIPT') return;",
            "    const r=el.getBoundingClientRect();",
            "    if(r.left<40 && r.width<=vw*0.3 && r.height>=vh*0.6) return;",
            "    let c=clip; if(s.overflowY!=='visible') c=Math.min(c,r.bottom);",
            "    const own=[...el.childNodes].some(n=>n.nodeType===3&&n.textContent.trim());",
            "    if((el.children.length===0||own) && r.width>0 && r.height>0 && r.height<vh*0.9) best=Math.max(best,Math.min(r.bottom,c));",
            "    for(const k of el.children) walk(k,c);",
            "  };",
            "  walk(document.body,Infinity);",
            "  return best+window.scrollY;",
            "};",
            "const running=()=>(document.getAnimations?document.getAnimations():[]).filter(a=>{try{",
            "  return a.playState==='running' && !(a.effect && a.effect.getComputedTiming().iterations===Infinity);}catch(e){return false;}}).length;",
            "const t0=performance.now(); let last=-1, stable=0;",
            "const tick=()=>{",
            "  const now=performance.now()-t0; const m=measure(); const r=running();",
            "  if(r===0 && Math.abs(m-last)<1) stable++; else stable=0;",
            "  last=m;",
            "  if((now>=minWait && stable>=3) || now>=maxWait){ done(m); return; }",
            "  setTimeout(tick,60);",
            "};",
            "setTimeout(tick,60);");

    private ScreenshotManager() {
    }

    /**
     * Saves screenshots/NN_slug_STATUS.png and returns the report-relative path
     * (screenshots/NN_slug_STATUS.png), or null when disabled or the browser is unreachable.
     */
    public static String capture(WebDriver driver, String fileName, boolean failure) {
        String flag = failure ? "screenshot.on.fail" : "screenshot.on.pass";
        if (!ConfigManager.getBool(flag, true)) {
            return null;
        }
        try {
            byte[] png = capturePng(driver);
            Path target = ExecutionInfo.screenshots().resolve(fileName);
            Files.write(target, png);
            return "screenshots/" + fileName;
        } catch (IOException | RuntimeException e) {
            Log.warn("Screenshot '" + fileName + "' not captured: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** The whole page as PNG bytes (falls back to the normal screenshot of the visible area when the full page cannot be taken). */
    public static byte[] capturePng(WebDriver driver) {
        byte[] full = fullPage(driver);
        return full != null ? full : ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
    }

    @SuppressWarnings("unchecked")
    private static byte[] fullPage(WebDriver driver) {
        if (!(driver instanceof ChromiumDriver) || !ConfigManager.getBool("screenshot.fullpage", true)) {
            return null;
        }
        ChromiumDriver chrome = (ChromiumDriver) driver;
        JavascriptExecutor js = (JavascriptExecutor) driver;
        long settleMs = ConfigManager.getInt("screenshot.settle.ms", 350);
        boolean enlarged = false;
        try {
            Object measured = js.executeScript(MEASURE_JS);
            if (!(measured instanceof List)) {
                return null; // a dialog / menu / viewer is open
            }
            List<Object> m = (List<Object>) measured;
            double width = ((Number) m.get(0)).doubleValue();
            double height = ((Number) m.get(1)).doubleValue();
            double extra = ((Number) m.get(2)).doubleValue();
            double dpr = ((Number) m.get(3)).doubleValue();
            if (extra < 8) {
                return null; // everything is visible already
            }
            long total = Math.min((long) Math.ceil(height + extra), ConfigManager.getInt("screenshot.max.height", 6000));
            chrome.executeCdpCommand("Emulation.setDeviceMetricsOverride", Map.<String, Object>of(
                    "width", (int) width, "height", (int) total, "deviceScaleFactor", dpr, "mobile", false));
            enlarged = true;
            Number bottom = (Number) js.executeAsyncScript(SETTLE_JS, settleMs, 3000L); // the page lays out again and animates its table
            // capture down to the end of the real content (not the whole enlarged view, which can be mostly empty), at least the normal view
            long cut = Math.min(total, Math.max((long) Math.ceil(height), (long) Math.ceil(bottom.doubleValue() + 24)));
            Map<String, Object> clip = Map.<String, Object>of("x", 0, "y", 0, "width", width, "height", (double) cut, "scale", 1);
            Map<String, Object> shot = chrome.executeCdpCommand("Page.captureScreenshot", Map.<String, Object>of("format", "png", "clip", clip));
            return Base64.getDecoder().decode((String) shot.get("data"));
        } catch (RuntimeException e) {
            return null; // not possible right now: the caller takes the normal screenshot
        } finally {
            if (enlarged) {
                try {
                    chrome.executeCdpCommand("Emulation.clearDeviceMetricsOverride", Map.<String, Object>of());
                    js.executeAsyncScript(SETTLE_JS, settleMs, 3000L); // let the page return to its normal layout before the test goes on
                    js.executeScript(RESTORE_SCROLL_JS);
                } catch (RuntimeException ignored) {
                    // browser already gone
                }
            }
        }
    }
}
