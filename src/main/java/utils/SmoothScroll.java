package utils;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Smooth scrolling for the person who watches the run (user feedback, 2026-11-13: "you don't scroll while performing testing, the user wasn't able to see what is going on, move the
 * scroll so you go below or up smoothly"). The page glides to the element the test works on, and waits until it stands still, so the next click does not hit a moving target.
 * {@link #tour} shows a long page: it glides to the bottom and back to the top. {@code -Dscroll.smooth=false} switches everything off (instant jumps), {@code -Dscroll.tour=false} only the tours.
 */
public final class SmoothScroll {

    private static final String SCROLLER = "const c=[document.scrollingElement].concat(Array.from(document.querySelectorAll('main,[class*=overflow-y-auto],[class*=overflow-auto],[class*=overflow-y-scroll]')));"
            + "let best=null,room=60;for(const e of c){if(e&&e.scrollHeight-e.clientHeight>room){room=e.scrollHeight-e.clientHeight;best=e;}}";

    private SmoothScroll() {
    }

    private static boolean smooth() {
        return ConfigManager.getBool("scroll.smooth", true);
    }

    /** Glides to the element (centre of the window) unless it is already well inside the window; returns when the page stands still. */
    public static void to(WebDriver d, WebElement e) {
        JavascriptExecutor js = (JavascriptExecutor) d;
        if (!smooth()) {
            js.executeScript("arguments[0].scrollIntoView({block:'center'})", e);
            return;
        }
        Object inside = js.executeScript("const r=arguments[0].getBoundingClientRect();const h=window.innerHeight;return r.top>h*0.2&&r.bottom<h*0.8&&r.height>0;", e);
        if (Boolean.TRUE.equals(inside)) {
            return;
        }
        js.executeScript("arguments[0].scrollIntoView({behavior:'smooth',block:'center'})", e);
        double last = Double.NaN;
        int still = 0;
        for (int i = 0; i < 25 && still < 3; i++) {
            pause(100);
            Object top = js.executeScript("return arguments[0].getBoundingClientRect().top;", e);
            double now = top instanceof Number ? ((Number) top).doubleValue() : 0;
            still = now == last ? still + 1 : 0;
            last = now;
        }
    }

    /** Scrolls the element into view, then clicks it with a script click (for buttons that are shown on hover only or that something lies over). */
    public static void click(WebDriver d, WebElement e) {
        to(d, e);
        ((JavascriptExecutor) d).executeScript("arguments[0].click()", e);
    }

    /** Glides down to the end of the page (or of its scrolling panel) and back to the top, so that the person watching sees the whole page. */
    public static void tour(WebDriver d) {
        if (!smooth() || !ConfigManager.getBool("scroll.tour", true)) {
            return;
        }
        try {
            JavascriptExecutor js = (JavascriptExecutor) d;
            Object room = js.executeScript(SCROLLER + "window.__tourScroller=best;return best?best.scrollHeight-best.clientHeight:0;");
            if (!(room instanceof Number) || ((Number) room).doubleValue() < 60) {
                return;
            }
            glide(js, "Math.max(0,window.__tourScroller.scrollHeight)");
            pause(500);
            glide(js, "0");
        } catch (RuntimeException ignored) {
            // the tour is only for the eyes: it never fails a test
        }
    }

    private static void glide(JavascriptExecutor js, String target) {
        js.executeScript("window.__tourScroller.scrollTo({top:" + target + ",behavior:'smooth'});");
        double last = Double.NaN;
        int still = 0;
        for (int i = 0; i < 60 && still < 3; i++) {
            pause(100);
            Object top = js.executeScript("return window.__tourScroller.scrollTop;");
            double now = top instanceof Number ? ((Number) top).doubleValue() : 0;
            still = now == last ? still + 1 : 0;
            last = now;
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms); // documented: waiting for a smooth scroll to finish
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
