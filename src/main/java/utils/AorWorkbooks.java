package utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import model.AorEmployee;
import model.AorRun;

/**
 * The two workbooks of the AOR happy flow. The templates of the QA owner are only COPIED (their originals are never changed, and they may be open in Excel):
 * <ul>
 *   <li>the bulk-upload workbook ({@code aorBulkUploadExcelPath}): the sheet named like the client ("Bleach") is filled, the other sheets (examples of other clients)
 *       are left out of the copy so that the application cannot read them, the pre-filled values of the template (client, project, country, verified status,
 *       bank account length, acknowledgement) are kept, and rows that are not needed are removed;</li>
 *   <li>the Payee Sheet ({@code aorPayeeSheetExcelPath}): State, FHRID, Wm Name, Final Payout, remark, delivery_hub, derived from the SAME employees.</li>
 * </ul>
 * Values are written as values, not as formulas (the formulas of the template have no stored result when a program writes them); only "IFSC LENGTH" keeps its
 * formula and gets its result calculated.
 */
public final class AorWorkbooks {

    private AorWorkbooks() {
    }

    /** The pristine template (a copy of the QA owner's file); the owner's own file is only the fallback when no template is configured. */
    public static Path templateOf(String templateKey, String fallbackKey) {
        try {
            Path t = Paths.get(ConfigManager.document(templateKey));
            if (Files.isRegularFile(t)) {
                return t;
            }
        } catch (RuntimeException e) {
            // not configured: the fallback below
        }
        return Paths.get(ConfigManager.document(fallbackKey));
    }

    // ------------------------------------------------------------------ the employee workbook

    /** Copies the bulk-upload template to {@code target} and fills the client's sheet with the employees of the run. */
    public static Path writeEmployeeWorkbook(AorRun run, Path target) {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            fillEmployeeWorkbook(run, part);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING); // only a finished workbook replaces the old file
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // nothing more to do
            }
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new IllegalStateException("The workbook " + target + " could not be written: " + e.getMessage(), e);
        }
        return target;
    }

    private static Path fillEmployeeWorkbook(AorRun run, Path target) {
        Path template = templateOf("aorBulkUploadTemplatePath", "aorBulkUploadExcelPath");
        copy(template, target);
        try (Workbook wb = open(target)) {
            Sheet sheet = sheetOf(wb, run.client, template);
            Map<String, Integer> cols = headers(sheet);
            checkPrefilled(sheet, cols, run);
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            for (int i = 0; i < run.employees.size(); i++) {
                AorEmployee e = run.employees.get(i);
                Row r = rowAt(sheet, i + 1);
                text(r, cols, "state", e.state);
                text(r, cols, "delivery partner id", e.deliveryPartnerId);
                text(r, cols, "company employee id", e.companyEmployeeId);
                text(r, cols, "name", e.name);
                text(r, cols, "name as per company", e.name);
                text(r, cols, "marge name", "AOR-" + e.name);
                text(r, cols, "dp name as per company", e.name);
                number(r, cols, "contact number", Long.parseLong(e.contactNumber));
                text(r, cols, "father name", e.fatherName);
                date(r, cols, "date of onboarding", e.onboardingDate);
                text(r, cols, "hub location", e.hubLocation);
                text(r, cols, "hub name", e.hubName);
                text(r, cols, "da status", "Active");
                date(r, cols, "date of joining", e.joiningDate);
                date(r, cols, "date of birth", e.birthDate);
                text(r, cols, "personal email id", e.personalEmail);
                number(r, cols, "aadhar number", Long.parseLong(e.aadhaar));
                text(r, cols, "pan number", e.pan);
                number(r, cols, "account number", Long.parseLong(e.accountNumber));
                text(r, cols, "ifsc code", e.ifscCode);
                text(r, cols, "driving licence number", e.drivingLicence);
                text(r, cols, "vehicle insurance", e.vehicleInsurance);
                // left blank by the rules: Letter Issue Date, Letter Issued By, Pan Status, IFSC-RIGHT. Kept as the template has them: Verified Status,
                // Bank A/C Length, Acknowledgement Status. IFSC Length is the template's formula (its result is calculated now).
                Cell len = cell(r, cols, "ifsc length");
                if (len != null && len.getCellType() == CellType.FORMULA) {
                    ev.evaluateFormulaCell(len);
                } else if (len != null) {
                    len.setCellValue(e.ifscCode.length());
                }
            }
            for (int rowNum = run.employees.size() + 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row extra = sheet.getRow(rowNum);
                if (extra != null) {
                    sheet.removeRow(extra); // template rows without an employee: an incomplete row would be a rejected row
                }
            }
            save(wb, target);
        } catch (IOException e) {
            throw new IllegalStateException("The employee workbook " + target + " could not be written: " + e.getMessage(), e);
        }
        return target;
    }

    // ------------------------------------------------------------------ the Payee Sheet

    /**
     * Copies the Payee Sheet template to {@code target} and fills the client's sheet from the employees of the run.
     * {@code wmNameIsEmployeeId}: the written spec says "Wm Name = Company Employee Id", the owner's own example sheets put the employee's NAME there
     * (default; -Daor.payee.wmname=employeeId switches).
     */
    public static Path writePayeeWorkbook(AorRun run, Path target) {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            fillPayeeWorkbook(run, part);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING); // only a finished workbook replaces the old file
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // nothing more to do
            }
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new IllegalStateException("The workbook " + target + " could not be written: " + e.getMessage(), e);
        }
        return target;
    }

    private static Path fillPayeeWorkbook(AorRun run, Path target) {
        Path template = templateOf("aorPayeeSheetTemplatePath", "aorPayeeSheetExcelPath");
        copy(template, target);
        boolean wmIsId = "employeeId".equalsIgnoreCase(ConfigManager.get("aor.payee.wmname", "name"));
        try (Workbook wb = open(target)) {
            Sheet sheet = sheetOf(wb, run.client, template);
            Map<String, Integer> cols = headers(sheet);
            for (int i = 0; i < run.employees.size(); i++) {
                AorEmployee e = run.employees.get(i);
                Row r = rowAt(sheet, i + 1);
                text(r, cols, "state", e.state);
                text(r, cols, "fhrid", e.deliveryPartnerId);
                text(r, cols, "wm name", wmIsId ? e.companyEmployeeId : e.name);
                number(r, cols, "final payout", e.finalPayout);
                // "remark" stays blank
                text(r, cols, "delivery_hub", e.hubName);
            }
            for (int rowNum = run.employees.size() + 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row extra = sheet.getRow(rowNum);
                if (extra != null) {
                    sheet.removeRow(extra);
                }
            }
            save(wb, target);
        } catch (IOException e) {
            throw new IllegalStateException("The Payee Sheet " + target + " could not be written: " + e.getMessage(), e);
        }
        return target;
    }

    // ------------------------------------------------------------------ edge cases: the same writers, then single cells are changed

    /**
     * Edge cases of the bulk upload: the normal workbook of the run is written to {@code target} (the pristine template is only read, the edge-case file is
     * replaced), then cells are changed. One map per employee row: header (any case) -> new text; {@code null} clears the cell.
     */
    public static Path writeEmployeeWorkbookWith(AorRun run, Path target, List<Map<String, String>> overrides) {
        writeEmployeeWorkbook(run, target);
        return override(target, run.client, overrides, List.of());
    }

    /** The Payee Sheet of the run, then cells are changed (one map per row) and further rows are appended (header -> text). */
    public static Path writePayeeWorkbookWith(AorRun run, Path target, List<Map<String, String>> overrides, List<Map<String, String>> extraRows) {
        writePayeeWorkbook(run, target);
        return override(target, run.client, overrides, extraRows);
    }

    private static Path override(Path target, String client, List<Map<String, String>> overrides, List<Map<String, String>> extraRows) {
        try (Workbook wb = open(target)) {
            Sheet sheet = wb.getSheet(client) != null ? wb.getSheet(client) : wb.getSheetAt(0);
            Map<String, Integer> cols = headers(sheet);
            for (int i = 0; i < overrides.size(); i++) {
                changeRow(sheet, cols, sheet.getRow(i + 1) == null ? sheet.createRow(i + 1) : sheet.getRow(i + 1), overrides.get(i));
            }
            int next = sheet.getLastRowNum() + 1;
            for (Map<String, String> extra : extraRows) {
                changeRow(sheet, cols, sheet.createRow(next++), extra);
            }
            save(wb, target);
        } catch (IOException e) {
            throw new IllegalStateException("The workbook " + target + " could not be changed: " + e.getMessage(), e);
        }
        return target;
    }

    private static final java.util.Set<String> NUMERIC_COLUMNS = java.util.Set.of("contact number", "aadhar number", "account number", "manager contact number", "final payout");

    private static void changeRow(Sheet sheet, Map<String, Integer> cols, Row row, Map<String, String> changes) {
        for (Map.Entry<String, String> c : changes.entrySet()) {
            Integer col = cols.get(c.getKey().toLowerCase(Locale.ROOT));
            if (col == null) {
                throw new IllegalArgumentException("The workbook has no column \"" + c.getKey() + "\" (columns: " + cols.keySet() + ")");
            }
            Cell cell = row.getCell(col);
            if (cell == null) {
                cell = row.createCell(col);
            }
            String v = c.getValue();
            if (v == null) {
                cell.setBlank();
            } else if (NUMERIC_COLUMNS.contains(c.getKey().toLowerCase(Locale.ROOT)) && v.matches("-?\\d{1,15}(\\.\\d+)?")) {
                cell.setCellValue(Double.parseDouble(v)); // a number that is wrong (negative, zero, decimals ...) stays a number: the business rule is tested, not "is it text"
            } else {
                cell.setCellValue(v);
            }
        }
    }

    /** The heading of a column is changed ({@code newHeader} null = the heading is removed): a workbook with a missing or wrongly named column. */
    public static void changeHeader(Path file, String header, String newHeader) {
        try (Workbook wb = open(file)) {
            Sheet sheet = wb.getSheetAt(0);
            Integer col = headers(sheet).get(header.toLowerCase(Locale.ROOT));
            if (col == null) {
                throw new IllegalArgumentException("The workbook has no column \"" + header + "\"");
            }
            Cell cell = sheet.getRow(0).getCell(col);
            if (newHeader == null) {
                cell.setBlank();
            } else {
                cell.setCellValue(newHeader);
            }
            save(wb, file);
        } catch (IOException e) {
            throw new IllegalStateException("The workbook " + file + " could not be changed: " + e.getMessage(), e);
        }
    }

    /** The sheet of the employees gets another name (the sheet carries the name of the client). */
    public static void renameSheet(Path file, String newName) {
        try (Workbook wb = open(file)) {
            wb.setSheetName(0, newName);
            save(wb, file);
        } catch (IOException e) {
            throw new IllegalStateException("The workbook " + file + " could not be changed: " + e.getMessage(), e);
        }
    }

    /** The heading row is deleted: the first employee stands where the headings should be. */
    public static void deleteHeaderRow(Path file) {
        try (Workbook wb = open(file)) {
            Sheet sheet = wb.getSheetAt(0);
            sheet.shiftRows(1, sheet.getLastRowNum(), -1);
            save(wb, file);
        } catch (IOException e) {
            throw new IllegalStateException("The workbook " + file + " could not be changed: " + e.getMessage(), e);
        }
    }

    /** A column that the template does not have, with one value in the first row. */
    public static void addColumn(Path file, String header, String value) {
        try (Workbook wb = open(file)) {
            Sheet sheet = wb.getSheetAt(0);
            int col = sheet.getRow(0).getLastCellNum();
            sheet.getRow(0).createCell(col).setCellValue(header);
            Row r = sheet.getRow(1) == null ? sheet.createRow(1) : sheet.getRow(1);
            r.createCell(col).setCellValue(value);
            save(wb, file);
        } catch (IOException e) {
            throw new IllegalStateException("The workbook " + file + " could not be changed: " + e.getMessage(), e);
        }
    }

    /** Copies row {@code rowIndex} below the last row (the same employee twice); formulas are copied as their result. */
    public static void duplicateRow(Path file, int rowIndex) {
        try (Workbook wb = open(file)) {
            Sheet sheet = wb.getSheetAt(0);
            Row from = sheet.getRow(rowIndex);
            Row to = sheet.createRow(sheet.getLastRowNum() + 1);
            for (Cell c : from) {
                Cell n = to.createCell(c.getColumnIndex());
                n.setCellStyle(c.getCellStyle());
                CellType t = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
                switch (t) {
                    case STRING -> n.setCellValue(c.getStringCellValue());
                    case NUMERIC -> n.setCellValue(c.getNumericCellValue());
                    case BOOLEAN -> n.setCellValue(c.getBooleanCellValue());
                    default -> n.setBlank();
                }
            }
            save(wb, file);
        } catch (IOException e) {
            throw new IllegalStateException("The workbook " + file + " could not be changed: " + e.getMessage(), e);
        }
    }

    /**
     * Writes rows exactly as they were read from earlier upload workbooks (header, in lower case -> text; dates as ISO, numbers as digits) into the pristine template. Used to
     * rebuild a retained workbook from the workbooks that were really uploaded.
     */
    public static Path writeRawRows(Path target, String client, List<Map<String, String>> rows) {
        Path template = templateOf("aorBulkUploadTemplatePath", "aorBulkUploadExcelPath");
        Path part = target.resolveSibling(target.getFileName() + ".part");
        copy(template, part);
        try (Workbook wb = open(part)) {
            Sheet sheet = sheetOf(wb, client, template);
            Map<String, Integer> cols = headers(sheet);
            AorRun placeholder = new AorRun();
            placeholder.client = client;
            placeholder.project = String.valueOf(rows.isEmpty() ? "" : rows.get(0).getOrDefault("project name", ""));
            for (int i = 0; i < rows.size(); i++) {
                placeholder.employees.add(new AorEmployee());
            }
            checkPrefilled(sheet, cols, placeholder);
            for (int i = 0; i < rows.size(); i++) {
                Row r = rowAt(sheet, i + 1);
                for (Map.Entry<String, String> e : rows.get(i).entrySet()) {
                    String h = e.getKey();
                    String v = e.getValue();
                    if (v == null || v.isBlank() || !cols.containsKey(h) || h.equals("ifsc length")) {
                        continue;
                    }
                    if (h.startsWith("date of") || h.equals("letter issue date")) {
                        date(r, cols, h, v);
                    } else if ((h.equals("contact number") || h.equals("aadhar number") || h.equals("account number") || h.equals("manager contact number")) && v.matches("\\d{1,15}")) {
                        number(r, cols, h, Long.parseLong(v));
                    } else {
                        text(r, cols, h, v);
                    }
                }
                Cell len = cell(r, cols, "ifsc length");
                if (len.getCellType() != CellType.FORMULA) {
                    len.setCellValue(rows.get(i).getOrDefault("ifsc code", "").length());
                }
            }
            for (int rowNum = rows.size() + 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row extra = sheet.getRow(rowNum);
                if (extra != null) {
                    sheet.removeRow(extra);
                }
            }
            save(wb, part);
        } catch (IOException e) {
            throw new IllegalStateException("The workbook " + target + " could not be written: " + e.getMessage(), e);
        }
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("The workbook " + target + " could not be replaced: " + e.getMessage(), e);
        }
        return target;
    }

    /** A file that is not a workbook at all (a text file with the extension of the target). */
    public static Path writePlainText(Path target, String text) {
        try {
            Files.writeString(target, text);
        } catch (IOException e) {
            throw new IllegalStateException("The file " + target + " could not be written: " + e.getMessage(), e);
        }
        return target;
    }

    // ------------------------------------------------------------------ reading a workbook back

    /**
     * The rows of the first sheet of a workbook as header (lower case) -> value. Dates come as ISO yyyy-MM-dd, whole numbers without ".0", a formula as its result.
     * Used to check what was really written (the report compares it with the data rules) and to see that a downloaded file can be opened.
     */
    public static List<Map<String, String>> readRows(Path file) {
        try (Workbook wb = open(file)) {
            Sheet sheet = wb.getSheetAt(0);
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            Row head = sheet.getRow(sheet.getFirstRowNum());
            List<String> names = new ArrayList<>();
            if (head != null) {
                for (int c = 0; c < head.getLastCellNum(); c++) {
                    names.add(valueOf(head.getCell(c), ev).trim().toLowerCase(Locale.ROOT));
                }
            }
            List<Map<String, String>> out = new ArrayList<>();
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, String> m = new LinkedHashMap<>();
                boolean any = false;
                for (int c = 0; c < names.size(); c++) {
                    String v = valueOf(row.getCell(c), ev);
                    m.put(names.get(c), v);
                    any |= !v.isEmpty();
                }
                if (any) {
                    out.add(m);
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("The workbook " + file + " cannot be read: " + e.getMessage(), e);
        }
    }

    /** The names of the sheets of a workbook. */
    public static List<String> sheetNames(Path file) {
        try (Workbook wb = open(file)) {
            return new ArrayList<>(java.util.Arrays.asList(sheetNames(wb).replace("[", "").replace("]", "").split(", ")));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** The rows of ONE sheet (by its exact name): header (lower case) -> value, like {@link #readRows(Path)}. */
    public static List<Map<String, String>> readRows(Path file, String sheetName) {
        try (Workbook wb = open(file)) {
            Sheet sheet = wb.getSheet(sheetName);
            if (sheet == null) {
                throw new IllegalStateException("no sheet '" + sheetName + "'");
            }
            return readSheet(wb, sheet);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("The sheet '" + sheetName + "' of " + file + " cannot be read: " + e.getMessage(), e);
        }
    }

    private static List<Map<String, String>> readSheet(Workbook wb, Sheet sheet) {
        FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
        Row head = sheet.getRow(sheet.getFirstRowNum());
        List<String> names = new ArrayList<>();
        if (head != null) {
            for (int c = 0; c < head.getLastCellNum(); c++) {
                names.add(valueOf(head.getCell(c), ev).trim().toLowerCase(Locale.ROOT));
            }
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            boolean any = false;
            for (int c = 0; c < names.size(); c++) {
                String v = valueOf(row.getCell(c), ev);
                m.put(names.get(c), v);
                any |= !v.isEmpty();
            }
            if (any) {
                out.add(m);
            }
        }
        return out;
    }

    /** True when the file is an Excel workbook that can be opened (any file that is not one gives false). */
    public static boolean opens(Path file) {
        try (Workbook wb = open(file)) {
            return wb.getNumberOfSheets() > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void copy(Path from, Path to) {
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING); // a copy: the original is never touched (it may be open in Excel)
        } catch (IOException e) {
            throw new IllegalStateException("The template " + from + " could not be copied to " + to + ": " + e.getMessage(), e);
        }
    }

    private static Workbook open(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return new XSSFWorkbook(in);
        }
    }

    private static void save(Workbook wb, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            wb.write(out);
        }
    }

    /** The sheet named like the client; every other sheet is removed from the copy. */
    private static Sheet sheetOf(Workbook wb, String client, Path template) {
        Sheet wanted = wb.getSheet(client);
        if (wanted == null) {
            throw new IllegalStateException("The workbook " + template + " has no sheet named '" + client + "' (sheets: " + sheetNames(wb) + ").");
        }
        for (int i = wb.getNumberOfSheets() - 1; i >= 0; i--) {
            if (!wb.getSheetName(i).equals(client)) {
                wb.removeSheetAt(i);
            }
        }
        return wb.getSheetAt(0);
    }

    private static String sheetNames(Workbook wb) {
        List<String> n = new ArrayList<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            n.add(wb.getSheetName(i));
        }
        return n.toString();
    }

    private static Map<String, Integer> headers(Sheet sheet) {
        Row head = sheet.getRow(0);
        if (head == null) {
            throw new IllegalStateException("The sheet '" + sheet.getSheetName() + "' has no header row.");
        }
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int c = 0; c < head.getLastCellNum(); c++) {
            Cell h = head.getCell(c);
            if (h != null && h.getCellType() == CellType.STRING) {
                m.put(h.getStringCellValue().trim().toLowerCase(Locale.ROOT), c);
            }
        }
        return m;
    }

    /** The client and the project of the template rows must be the ones of the run ("exactly as supplied, never replaced"). */
    private static void checkPrefilled(Sheet sheet, Map<String, Integer> cols, AorRun run) {
        // the template has 10 prefilled rows; more employees get the same prefilled values (client, project, country, verified status, bank length, acknowledgement) copied from row 1
        Row first = sheet.getRow(1);
        for (int i = 0; i < run.employees.size(); i++) {
            Row existing = sheet.getRow(i + 1);
            boolean prefilled = existing != null && !valueOf(cell(existing, cols, "client name"), null).isEmpty();
            if (!prefilled && first != null && i > 0) {
                Row copy = rowAt(sheet, i + 1);
                for (String h : List.of("client name", "project name", "country", "address", "bank name", "manager name", "manager contact number", "area manager email", "area manager name", "supervisor name", "verified status", "bank a/c length", "acknowledgement status")) {
                    Integer c = cols.get(h);
                    Cell from = c == null ? null : first.getCell(c);
                    if (from == null) {
                        continue;
                    }
                    Cell to = copy.getCell(c) != null ? copy.getCell(c) : copy.createCell(c);
                    CellType t = from.getCellType() == CellType.FORMULA ? from.getCachedFormulaResultType() : from.getCellType();
                    if (t == CellType.NUMERIC) {
                        to.setCellValue(from.getNumericCellValue());
                    } else if (t == CellType.STRING) {
                        to.setCellValue(from.getStringCellValue());
                    }
                }
            }
            Row r = sheet.getRow(i + 1);
            String client = r == null ? "" : valueOf(cell(r, cols, "client name"), null);
            String project = r == null ? "" : valueOf(cell(r, cols, "project name"), null);
            if (!run.client.equals(client) || !run.project.equals(project)) {
                throw new IllegalStateException("Row " + (i + 2) + " of the template sheet '" + sheet.getSheetName() + "' has client '" + client + "' and project '"
                        + project + "', but the run needs '" + run.client + "' / '" + run.project + "' (the template rows must be prefilled with them).");
            }
        }
    }

    private static Row rowAt(Sheet sheet, int index) {
        Row r = sheet.getRow(index);
        return r != null ? r : sheet.createRow(index);
    }

    private static Cell cell(Row row, Map<String, Integer> cols, String header) {
        Integer c = cols.get(header);
        if (c == null) {
            throw new IllegalStateException("The template has no column '" + header + "' (columns: " + cols.keySet() + ").");
        }
        Cell cell = row.getCell(c);
        return cell != null ? cell : row.createCell(c);
    }

    private static void text(Row r, Map<String, Integer> cols, String header, String value) {
        Cell c = cell(r, cols, header);
        if (c.getCellType() == CellType.FORMULA) {
            c.setBlank(); // a value replaces a formula of the template: its result is written, not recalculated by the reader
        }
        c.setCellValue(value);
    }

    private static void number(Row r, Map<String, Integer> cols, String header, long value) {
        Cell c = cell(r, cols, header);
        if (c.getCellType() == CellType.FORMULA) {
            c.setBlank();
        }
        c.setCellValue((double) value);
    }

    private static void date(Row r, Map<String, Integer> cols, String header, String iso) {
        Cell c = cell(r, cols, header);
        try {
            c.setCellValue(Date.from(LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant())); // the template cell already has its date format ...
            if (!org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(c)) { // ... except in a row that the template does not have: such a cell would read back as a serial number
                Workbook wb = r.getSheet().getWorkbook();
                org.apache.poi.ss.usermodel.CellStyle st = wb.createCellStyle();
                st.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd-mmm-yyyy"));
                c.setCellStyle(st);
            }
        } catch (java.time.format.DateTimeParseException notADate) {
            c.setCellValue(iso); // a wrong value that the application accepted (for example "11-Nov-2031" as text) stays exactly as it was
        }
    }

    private static String valueOf(Cell c, FormulaEvaluator ev) {
        if (c == null) {
            return "";
        }
        CellType type = c.getCellType();
        if (type == CellType.FORMULA && ev != null) {
            try {
                type = ev.evaluateFormulaCell(c);
            } catch (RuntimeException e) {
                return "";
            }
            switch (type) {
                case STRING -> {
                    return c.getStringCellValue().trim();
                }
                case NUMERIC -> {
                    return number(c.getNumericCellValue());
                }
                case BOOLEAN -> {
                    return String.valueOf(c.getBooleanCellValue());
                }
                default -> {
                    return "";
                }
            }
        }
        return switch (type) {
            case STRING -> c.getStringCellValue().trim();
            case NUMERIC -> DateUtil.isCellDateFormatted(c)
                    ? c.getLocalDateTimeCellValue().toLocalDate().toString()
                    : number(c.getNumericCellValue());
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> "";
        };
    }

    private static String number(double v) {
        return new BigDecimal(v).stripTrailingZeros().toPlainString();
    }
}
