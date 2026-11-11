package pages;

import java.util.ArrayList;
import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import utils.ToastWatcher;

/** The confirmation window that "Delete" opens (a dialog or alert dialog): read its text, confirm or cancel. Only used on data the test run created itself. */
public final class DeleteWindow {

    private static final String FIND = "var all=[...document.querySelectorAll('[role=alertdialog],[role=dialog]')].filter(function(x){var r=x.getBoundingClientRect();return r.width>0&&r.height>0;});"
            + "var d=all.length?all[all.length-1]:null;";

    private DeleteWindow() {
    }

    /** Waits (up to 10 s) for the window and returns its text ("" when none opened). */
    public static String waitText(WebDriver driver) {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            Object t = ((JavascriptExecutor) driver).executeScript(FIND + "return d?d.innerText:'';");
            if (t != null && !String.valueOf(t).isBlank()) {
                return String.valueOf(t).replaceAll("\\s+", " ").trim();
            }
            pause(250);
        }
        return "";
    }

    /** Clicks "Cancel" (or the close button) of the window. */
    public static void cancel(WebDriver driver) {
        ((JavascriptExecutor) driver).executeScript(FIND + "if(!d)return;var b=[...d.querySelectorAll('button')];"
                + "var c=b.find(function(x){return /^(cancel|no|close)$/i.test(x.innerText.trim());})||b.find(function(x){return /close/i.test(x.getAttribute('aria-label')||'');});if(c)c.click();");
        pause(800);
    }

    /** Clicks the confirming button (Delete / Confirm / Yes / Remove) of the window; returns the messages the application showed afterwards. */
    public static List<String> confirm(WebDriver driver) {
        ToastWatcher.clear(driver);
        List<WebElement> buttons = driver.findElements(By.xpath("(//*[@role='alertdialog' or @role='dialog'])[last()]//button"));
        WebElement ok = null;
        for (WebElement b : buttons) {
            if (b.getText().trim().matches("(?i)(delete|confirm|yes.*|remove|ok|continue)")) {
                ok = b;
            }
        }
        if (ok == null) {
            throw new AssertionError("The confirmation window has no button to confirm the deletion (buttons: " + buttons.stream().map(b -> "\"" + b.getText().trim() + "\"").toList() + ").");
        }
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", ok);
        List<String> messages = new ArrayList<>();
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            pause(400);
            for (String t : ToastWatcher.all(driver)) {
                if (!messages.contains(t)) {
                    messages.add(t);
                }
            }
        }
        return messages;
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
