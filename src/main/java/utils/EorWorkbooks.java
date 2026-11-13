package utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import model.EorEmployee;
import model.EorRun;

/**
 * The EOR onboarding workbook of the QA owner ({@code employeeBulkUploadExcelPath} in config/documents.properties; only COPIED, the original is never changed - it may be open in Excel).
 * The first sheet ("Onboarding Data") holds ONE prepared example row with the client, project, manager, dates, salary and benefit values; that row is repeated for every
 * new employee (formulas such as the annual and monthly CTC follow the row) and only the personal columns are replaced:
 * State, First Name, Middle Name, Last Name, Gender, Email, Work Experience, Mobile Number, Working Location, End Client Name, Designation, Job Description.
 * Every other sheet of the template is removed from the copy; a file is written to a ".part" file first and only a finished one is moved into place.
 */
public final class EorWorkbooks {

    private static final Pattern ROW_TWO = Pattern.compile("(\\$?[A-Z]{1,3}\\$?)2(?![0-9])");

    private EorWorkbooks() {
    }

    public static Path template() {
        return Paths.get(ConfigManager.document("employeeBulkUploadExcelPath"));
    }

    /** Writes the workbook of the run (one row per employee) to {@code target}. */
    public static Path write(EorRun run, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Path part = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(template(), part, StandardCopyOption.REPLACE_EXISTING);
            try (Workbook wb = open(part)) {
                while (wb.getNumberOfSheets() > 1) {
                    wb.removeSheetAt(wb.getNumberOfSheets() - 1); // the first sheet is the one that is uploaded
                }
                Sheet sheet = wb.getSheetAt(0);
                Map<String, Integer> cols = headers(sheet);
                Row example = sheet.getRow(1);
                if (example == null) {
                    throw new IllegalStateException("The first sheet of the EOR template has no example row (row 2).");
                }
                if (run.client.isBlank()) {
                    run.client = value(example, cols, "client name");
                    run.project = value(example, cols, "project name");
                }
                for (int i = 0; i < run.employees.size(); i++) {
                    Row row = i == 0 ? example : copyRow(sheet, example, i + 1);
                    fill(row, cols, run.employees.get(i));
                }
                for (int r = run.employees.size() + 1; r <= sheet.getLastRowNum(); r++) {
                    Row extra = sheet.getRow(r);
                    if (extra != null) {
                        sheet.removeRow(extra); // rows of the template below the example row
                    }
                }
                FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
                ev.evaluateAll(); // the stored result of every formula is up to date
                try (OutputStream out = Files.newOutputStream(part.resolveSibling(part.getFileName() + ".new"))) {
                    wb.write(out);
                }
            }
            Files.move(part.resolveSibling(part.getFileName() + ".new"), target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(part);
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("The EOR workbook could not be written to " + target + ": " + e.getMessage(), e);
        }
    }

    private static void fill(Row row, Map<String, Integer> cols, EorEmployee e) {
        text(row, cols, "state", e.state);
        text(row, cols, "first name", e.firstName);
        text(row, cols, "middle name", e.middleName == null ? "" : e.middleName);
        text(row, cols, "last name", e.lastName);
        text(row, cols, "gender", e.gender);
        text(row, cols, "email", e.email);
        text(row, cols, "work experience", e.experience);
        text(row, cols, "mobile number", e.phone);
        text(row, cols, "working location", e.workingLocation);
        text(row, cols, "end client name", e.endClient);
        text(row, cols, "designation", e.designation);
        text(row, cols, "job description", e.jobDescription);
    }

    private static Row copyRow(Sheet sheet, Row from, int index) {
        Row to = sheet.createRow(index);
        int excelRow = index + 1;
        for (int c = 0; c < from.getLastCellNum(); c++) {
            Cell s = from.getCell(c);
            if (s == null) {
                continue;
            }
            Cell d = to.createCell(c);
            d.setCellStyle(s.getCellStyle());
            switch (s.getCellType()) {
                case STRING -> d.setCellValue(s.getStringCellValue());
                case NUMERIC -> d.setCellValue(s.getNumericCellValue());
                case BOOLEAN -> d.setCellValue(s.getBooleanCellValue());
                case FORMULA -> {
                    Matcher m = ROW_TWO.matcher(s.getCellFormula());
                    d.setCellFormula(m.replaceAll("$1" + excelRow)); // AE2*12 -> AE3*12 in the next row
                }
                default -> { /* blank */ }
            }
        }
        return to;
    }

    private static void text(Row r, Map<String, Integer> cols, String header, String value) {
        Integer c = cols.get(header);
        if (c == null) {
            throw new IllegalStateException("The EOR template has no column \"" + header + "\" (the columns are: " + cols.keySet() + ").");
        }
        Cell cell = r.getCell(c);
        if (cell == null) {
            cell = r.createCell(c);
        }
        if (cell.getCellType() == CellType.FORMULA) {
            cell.setBlank();
        }
        cell.setCellValue(value == null ? "" : value);
    }

    private static String value(Row r, Map<String, Integer> cols, String header) {
        Integer c = cols.get(header);
        Cell cell = c == null ? null : r.getCell(c);
        return cell == null ? "" : new DataFormatter().formatCellValue(cell).trim();
    }

    private static Map<String, Integer> headers(Sheet sheet) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Row h = sheet.getRow(0);
        DataFormatter f = new DataFormatter();
        for (int c = 0; c < h.getLastCellNum(); c++) {
            Cell cell = h.getCell(c);
            if (cell != null) {
                String name = f.formatCellValue(cell).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                if (!name.isEmpty()) {
                    out.putIfAbsent(name, c);
                }
            }
        }
        return out;
    }

    /** The rows of the first sheet as written (header in lower case -> shown value); used to check the file that is uploaded. */
    public static java.util.List<Map<String, String>> readRows(Path file) {
        java.util.List<Map<String, String>> rows = new java.util.ArrayList<>();
        try (Workbook wb = open(file)) {
            Sheet sheet = wb.getSheetAt(0);
            Map<String, Integer> cols = headers(sheet);
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            DataFormatter f = new DataFormatter();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, String> m = new LinkedHashMap<>();
                boolean any = false;
                for (Map.Entry<String, Integer> e : cols.entrySet()) {
                    Cell cell = row.getCell(e.getValue());
                    String v = cell == null ? "" : f.formatCellValue(cell, ev).trim();
                    if (!v.isEmpty()) {
                        any = true;
                    }
                    m.put(e.getKey(), v);
                }
                if (any) {
                    rows.add(m);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("The workbook " + file + " cannot be read: " + e.getMessage(), e);
        }
        return rows;
    }

    private static Workbook open(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return new XSSFWorkbook(in);
        }
    }
}
