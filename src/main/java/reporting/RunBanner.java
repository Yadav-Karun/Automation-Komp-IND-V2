package reporting;

import java.util.Map;
import java.util.WeakHashMap;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;

import utils.ConfigManager;

/**
 * A small bar on the visible browser: "Total TC - N / Ran so far - N / Remaining - N / Time MM:SS", so whoever watches the run sees how far it is.
 * <p>
 * It must never disturb the tests: the box is drawn OUTSIDE the page body (a child of the html element, so the text of the page - read by the tests - does not
 * change), is click-through except for its small drag handle (the grip on the left: drag it where it suits you; the position is remembered), is shown in the top
 * window only (not inside iframes such as the PDF viewer), is hidden for the instant of a report screenshot ({@link ScreenshotManager}) and every call swallows
 * every error. {@code -Drun.banner=false} switches it off.
 * <p>
 * No blinking: the numbers are changed IN PLACE (the progress line moves with a CSS transition), the timer runs inside the browser (one tick per second, no
 * call from the tests), and the script is registered with the browser as "run on every new document" so a page load only has to draw the box once.
 */
public final class RunBanner {

    private static final Map<WebDriver, String> SCRIPT_ID = new WeakHashMap<>();
    /** Where the bar was last seen ([left, top]) or null: the default position of every new page. */
    private static volatile String lastPos = "null";

    private static final String JS = String.join("\n",
            "(function(){",
            "  if (window.top !== window) return;",
            "  var st = window.__kompRun = window.__kompRun || {};",
            "  st.T=@TOTAL@; st.D=@DONE@; st.S=@START@;",
            "  function two(n){return (n<10?'0':'')+n;}",
            "  function render(){",
            "    var e=document.getElementById('komp-run-banner'); if(!e) return;",
            "    var R=Math.max(st.T-st.D,0), P=st.T>0?Math.min(100,st.D*100/st.T):0, s=Math.max(0,Math.floor((Date.now()-st.S)/1000));",
            "    e.querySelector('[data-k=t]').textContent=st.T; e.querySelector('[data-k=d]').textContent=st.D; e.querySelector('[data-k=r]').textContent=R;",
            "    e.querySelector('[data-k=time]').textContent=two(Math.floor(s/60))+':'+two(s%60);",
            "    e.querySelector('[data-k=bar]').style.width=P+'%';",
            "  }",
            "  function place(e,x,y){",
            "    var w=e.offsetWidth||360, h=e.offsetHeight||40;",
            "    x=Math.min(Math.max(0,x),Math.max(0,window.innerWidth-w)); y=Math.min(Math.max(0,y),Math.max(0,window.innerHeight-h));",
            "    e.style.left=x+'px'; e.style.top=y+'px'; return [x,y];",
            "  }",
            "  function savedPos(){ var m=/(?:^|;)kompbar=(-?[0-9]+),(-?[0-9]+)(?:;|$)/.exec(window.name||''); return m?[+m[1],+m[2]]:@POS@; }",
            "  function savePos(x,y){ var n=(window.name||'').replace(/(?:^|;)kompbar=[^;]*/,''); window.name=n+(n?';':'')+'kompbar='+x+','+y; }",
            "  function build(){",
            "    if(document.getElementById('komp-run-banner')){ render(); return; }",
            "    if(!document.documentElement){ if(!st.waiting){ st.waiting=true; new MutationObserver(function(m,o){ if(document.documentElement){ o.disconnect(); build(); } }).observe(document,{childList:true}); } return; }",
            "    var e=document.createElement('div'); e.id='komp-run-banner'; e.setAttribute('aria-hidden','true');",
            "    e.style.cssText='position:fixed;left:8px;top:8px;z-index:2147483647;pointer-events:none;background:#111;color:#fff;border:2px solid #000;"
                    + "border-radius:6px;padding:6px 12px 8px 4px;font:600 13px/1.35 Segoe UI,Arial,sans-serif;box-shadow:0 2px 8px rgba(0,0,0,.5);opacity:.96;"
                    + "white-space:nowrap;user-select:none';",
            "    e.innerHTML='<span data-k=\"grip\" title=\"Drag to move\" style=\"display:inline-block;width:18px;margin-right:4px;text-align:center;cursor:move;"
                    + "pointer-events:auto;color:#aaa\">&#8942;&#8942;</span>'",
            "      +'Total TC - <b data-k=\"t\" style=\"color:#ffd84d\"></b> &nbsp;/&nbsp; Ran so far - <b data-k=\"d\" style=\"color:#7dff9b\"></b>'",
            "      +' &nbsp;/&nbsp; Remaining - <b data-k=\"r\" style=\"color:#ff9f6b\"></b> &nbsp;/&nbsp; Time - <b data-k=\"time\" style=\"color:#8fd3ff\"></b>'",
            "      +'<div style=\"margin:5px 0 0 18px;height:4px;background:#444;border-radius:2px\"><div data-k=\"bar\" style=\"height:4px;width:0;background:#7dff9b;"
                    + "border-radius:2px\"></div></div>';",
            "    document.documentElement.appendChild(e);",
            "    var pos=savedPos(); if(pos) place(e,pos[0],pos[1]);",
            "    var grip=e.querySelector('[data-k=grip]'), dx=0, dy=0, drag=false;",
            "    grip.addEventListener('pointerdown',function(ev){ drag=true; dx=ev.clientX-e.offsetLeft; dy=ev.clientY-e.offsetTop; grip.setPointerCapture(ev.pointerId); ev.preventDefault(); });",
            "    grip.addEventListener('pointermove',function(ev){ if(drag) place(e,ev.clientX-dx,ev.clientY-dy); });",
            "    grip.addEventListener('pointerup',function(ev){ if(!drag) return; drag=false; savePos(e.offsetLeft,e.offsetTop); });",
            "    window.addEventListener('resize',function(){ place(e,e.offsetLeft,e.offsetTop); });",
            "    render();",
            "    requestAnimationFrame(function(){ var b=e.querySelector('[data-k=bar]'); if(b) b.style.transition='width .6s ease'; });",
            "  }",
            "  build();",
            "  if(!st.listening){ st.listening=true; document.addEventListener('DOMContentLoaded', build); setInterval(render,1000); }",
            "})();");

    private RunBanner() {
    }

    private static boolean enabled() {
        return ConfigManager.getBool("run.banner", true);
    }

    /**
     * Shows / refreshes the bar on the current page (in place, nothing is rebuilt) and on every page the browser loads from now on.
     * {@code startMs} is the moment the run started (the timer counts from it).
     */
    public static void update(WebDriver driver, int total, int done, long startMs) {
        if (driver == null || !enabled() || !(driver instanceof ChromiumDriver)) {
            return;
        }
        try {
            String pos = "null";
            try {
                Object p = ((JavascriptExecutor) driver).executeScript(
                        "var e=document.getElementById('komp-run-banner'); return e? [e.offsetLeft,e.offsetTop] : null;");
                if (p instanceof java.util.List && ((java.util.List<?>) p).size() == 2) {
                    pos = "[" + ((Number) ((java.util.List<?>) p).get(0)).intValue() + "," + ((Number) ((java.util.List<?>) p).get(1)).intValue() + "]";
                    lastPos = pos;
                }
            } catch (RuntimeException ignored) {
                // page is loading: keep the last known position
            }
            if ("null".equals(pos)) {
                pos = lastPos;
            }
            String js = JS.replace("@TOTAL@", String.valueOf(Math.max(total, 0))).replace("@DONE@", String.valueOf(Math.max(done, 0)))
                    .replace("@START@", String.valueOf(startMs)).replace("@POS@", pos);
            ((JavascriptExecutor) driver).executeScript(js); // first: the numbers on the screen change at once
            ChromiumDriver chrome = (ChromiumDriver) driver;
            String old = SCRIPT_ID.get(driver);
            Object added = chrome.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", Map.<String, Object>of("source", js)).get("identifier");
            if (added != null) {
                SCRIPT_ID.put(driver, String.valueOf(added));
            }
            if (old != null) {
                try {
                    chrome.executeCdpCommand("Page.removeScriptToEvaluateOnNewDocument", Map.<String, Object>of("identifier", old));
                } catch (RuntimeException ignored) {
                    // an old registration that is already gone is fine
                }
            }
        } catch (RuntimeException e) {
            // never a reason to fail a test
        }
    }

    /** Hides the bar for the instant of a report picture (waits two frames so the picture really shows no bar) / shows it again. */
    public static void visible(WebDriver driver, boolean visible) {
        if (driver == null || !enabled()) {
            return;
        }
        try {
            ((JavascriptExecutor) driver).executeAsyncScript(
                    "var done=arguments[arguments.length-1]; var e=document.getElementById('komp-run-banner');"
                            + "if(!e){done(true);return;} e.style.visibility=arguments[0]?'visible':'hidden';"
                            + "if(arguments[0]){done(true);return;} requestAnimationFrame(function(){requestAnimationFrame(function(){done(true);});});", visible);
        } catch (RuntimeException e) {
            // ignore
        }
    }
}
