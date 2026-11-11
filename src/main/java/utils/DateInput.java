package utils;

import java.time.LocalDate;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Types a date into an {@code <input type="date">} the way a user does: the digits in the browser's own order (dd-mm-yyyy, mm/dd/yyyy ...), so it works on any
 * machine locale. The value of such an input is always ISO (yyyy-MM-dd): when it does not hold the date afterwards it is set directly and the application is told.
 */
public final class DateInput {

    private DateInput() {
    }

    public static void type(WebDriver driver, WebElement input, LocalDate date) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'})", input);
        input.click();
        // no Ctrl+A here: in a date box it selects the whole PAGE (seen as a page full of highlighted text). The digits simply replace the parts of the date.
        input.sendKeys(keys(driver, date));
        if (!date.toString().equals(input.getAttribute("value"))) {
            ((JavascriptExecutor) driver).executeScript(
                    "const el=arguments[0];"
                            + "Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,arguments[1]);"
                            + "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));",
                    input, date.toString());
        }
    }

    /** The key presses in the browser's date order: "10112026" for dd-mm-yyyy, "11102026" for mm-dd-yyyy, "20261110" for yyyy-mm-dd. */
    private static String keys(WebDriver driver, LocalDate d) {
        Object parts = ((JavascriptExecutor) driver).executeScript(
                "return new Intl.DateTimeFormat(navigator.language,{day:'2-digit',month:'2-digit',year:'numeric'})"
                        + ".formatToParts(new Date(2000,10,25)).filter(p=>p.type!=='literal').map(p=>p.type).join(',');");
        StringBuilder sb = new StringBuilder();
        for (String p : String.valueOf(parts).split(",")) {
            switch (p) {
                case "day" -> sb.append(String.format("%02d", d.getDayOfMonth()));
                case "month" -> sb.append(String.format("%02d", d.getMonthValue()));
                case "year" -> sb.append(String.format("%04d", d.getYear()));
                default -> { }
            }
        }
        return sb.toString();
    }
}
