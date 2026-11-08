package utils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

/**
 * Observes how the application reacts to a submitted form: inline validation messages
 * (the app renders them as <p class="text-destructive">), native browser validation messages and toasts.
 * Verified live on the Register Client form (2026-11-07).
 */
public final class FormFeedback {

    /** What happened after a submit. */
    public static final class Outcome {
        /** The app moved on (form closed / next step shown / success toast). */
        public final boolean accepted;
        public final List<String> messages;
        public final List<String> toasts;

        public Outcome(boolean accepted, List<String> messages, List<String> toasts) {
            this.accepted = accepted;
            this.messages = messages;
            this.toasts = toasts;
        }

        public boolean hasFeedback() {
            return !messages.isEmpty() || !toasts.isEmpty();
        }

        /** Everything the application told the user (messages under the fields and pop-up notices), each text once. */
        public List<String> shownTexts() {
            List<String> all = new ArrayList<>(messages);
            all.addAll(toasts);
            return PlainText.withoutRepeats(all);
        }

        /** The texts shown, each in double quotes ("" when nothing was shown). */
        public String quotedTexts() {
            return PlainText.quote(shownTexts());
        }

        /** What the application did, in one plain sentence (the report is read by people who are not technical). */
        public String describe() {
            String shown = quotedTexts();
            if (accepted) {
                return "The application accepted the entry and moved on" + (shown.isEmpty() ? ". It showed no message." : ". It showed: " + shown + ".");
            }
            if (shown.isEmpty()) {
                return "The application refused the entry but showed no message, so the user is not told what is wrong.";
            }
            return "The application refused the entry and showed " + (shownTexts().size() == 1 ? "this message: " : "these messages: ") + shown + ".";
        }
    }

    private static final String INLINE_JS = String.join("\n",
            "const out=[];",
            "const vis=e=>{const r=e.getBoundingClientRect();const s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'};",
            "document.querySelectorAll('p.text-destructive, [role=alert], [data-slot=form-message], .text-red-500, .text-red-600').forEach(e=>{",
            "  if(!vis(e)) return; const t=(e.innerText||'').trim().replace(/\\s+/g,' ');",
            "  if(t && t.length<200 && !out.includes(t)) out.push(t);});",
            "document.querySelectorAll('form *, [role=dialog] *').forEach(e=>{",
            "  if(e.children.length>0 || /^(LABEL|BUTTON|OPTION|SCRIPT|STYLE|A)$/.test(e.tagName) || !vis(e)) return;",
            "  const t=(e.innerText||'').trim().replace(/\\s+/g,' ');",
            "  if(t && t.length<160 && /(is required|are required|required field|invalid|must be|should be|enter a valid|cannot be|too short|too long|do not match|does not match|not match)/i.test(t) && !out.includes(t)) out.push(t);});",
            "document.querySelectorAll('form *, [role=dialog] *').forEach(e=>{",
            "  if(e.children.length>0 || /^(LABEL|BUTTON|OPTION|SCRIPT|STYLE|A|SPAN)$/.test(e.tagName) && e.closest('label,button') || !vis(e)) return;",
            "  const t=(e.innerText||'').trim().replace(/\\s+/g,' '); if(!t || t.length>=160 || out.includes(t)) return;",
            "  const m=/rgba?\\((\\d+), *(\\d+), *(\\d+)/.exec(getComputedStyle(e).color||''); if(!m) return;",
            "  if(+m[1]>170 && +m[2]<140 && +m[3]<140 && !e.closest('label')) out.push(t);});",
            "document.querySelectorAll('input,select,textarea').forEach(e=>{",
            "  if(e.validationMessage && vis(e) && !e.validity.valid){ const t=(e.name||e.id||'field')+': '+e.validationMessage; if(!out.includes(t)) out.push(t);} });",
            "return out;");

    private FormFeedback() {
    }

    @SuppressWarnings("unchecked")
    public static List<String> inlineMessages(WebDriver driver) {
        try {
            Object o = ((JavascriptExecutor) driver).executeScript(INLINE_JS);
            return o == null ? List.of() : new ArrayList<>((List<String>) o);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Runs the submit action, then waits (max {@code seconds}) until the app either moved on ({@code advanced})
     * or produced feedback. If neither happens in time the submit is reported as silently rejected.
     */
    public static Outcome submit(WebDriver driver, int seconds, Runnable submit, BooleanSupplier advanced) {
        ToastWatcher.waitNoneVisible(driver, 12);
        ToastWatcher.clear(driver);
        submit.run();
        Instant end = Instant.now().plus(Duration.ofSeconds(seconds));
        while (Instant.now().isBefore(end)) {
            if (safe(advanced)) {
                break;
            }
            if (!inlineMessages(driver).isEmpty() || !ToastWatcher.all(driver).isEmpty()) {
                // give a late success redirect a moment before calling it a rejection
                pause(1200);
                break;
            }
            pause(250);
        }
        boolean adv = safe(advanced);
        Set<String> toasts = new LinkedHashSet<>(ToastWatcher.all(driver));
        return new Outcome(adv, adv ? List.of() : inlineMessages(driver), new ArrayList<>(toasts));
    }

    private static boolean safe(BooleanSupplier s) {
        try {
            return s.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
