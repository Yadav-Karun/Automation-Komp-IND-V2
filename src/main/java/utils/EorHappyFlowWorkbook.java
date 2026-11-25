package utils;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import model.EorEmployee;
import model.EorRun;

/**
 * Writes the upload workbook of the EOR happy flow: a COPY of the template (the template itself is never changed),
 * row 2 = employee 1, row 3 = employee 2. Client, project, project code, country and contract duration stay as the template has them.
 */
public class EorHappyFlowWorkbook {

    private static final Random random = new Random();

    public static Path write(EorRun run, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        Files.copy(EorWorkbooks.template(), target, StandardCopyOption.REPLACE_EXISTING);

        Workbook workbook;
        try (var in = Files.newInputStream(target)) {
            workbook = new XSSFWorkbook(in);
        }
        while (workbook.getNumberOfSheets() > 1) {
            workbook.removeSheetAt(workbook.getNumberOfSheets() - 1); // only the first sheet is uploaded
        }
        Sheet sheet = workbook.getSheetAt(0);
        Map<String, Integer> columns = columns(sheet);
        Row example = sheet.getRow(1);
        run.client = new DataFormatter().formatCellValue(example.getCell(columns.get("client name")));
        run.project = new DataFormatter().formatCellValue(example.getCell(columns.get("project name")));

        for (int i = 1; i < run.employees.size(); i++) {
            copyFixedCells(example, sheet.createRow(i + 1)); // before the example row gets the values of employee 1
        }
        for (int i = 0; i < run.employees.size(); i++) {
            fillRow(sheet.getRow(i + 1), columns, run.employees.get(i));
        }
        for (int r = run.employees.size() + 1; r <= sheet.getLastRowNum(); r++) {
            if (sheet.getRow(r) != null) {
                sheet.removeRow(sheet.getRow(r)); // template rows below the employees
            }
        }
        workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
        try (OutputStream out = Files.newOutputStream(target)) {
            workbook.write(out);
        }
        workbook.close();
        return target;
    }

    private static void fillRow(Row row, Map<String, Integer> col, EorEmployee e) {
        // employee
        text(row, col, "state", e.state);
        text(row, col, "first name", e.firstName);
        text(row, col, "middle name", e.middleName);
        text(row, col, "last name", e.lastName);
        text(row, col, "gender", e.gender);
        text(row, col, "email", e.email);
        text(row, col, "work experience", e.experience);
        text(row, col, "mobile number", e.phone);
        text(row, col, "working location", e.workingLocation);
        text(row, col, "end client name", e.endClient);
        text(row, col, "department", HappyFlowData.DEPARTMENT_OF.get(e.email));
        text(row, col, "designation", e.designation);
        number(row, col, "joining date", DateUtil.getExcelDate(LocalDate.now().minusDays(1 + random.nextInt(60))));
        number(row, col, "leave entitlement", 5 + random.nextInt(16));
        text(row, col, "mode of work", new String[] {"Offline", "Online", "Hybrid"}[random.nextInt(3)]);
        text(row, col, "job description", e.jobDescription);
        text(row, col, "employment type", random.nextBoolean() ? "Part Time" : "Full Time");

        // fixed values
        text(row, col, "invoicing location", "India");
        text(row, col, "manager name", "KARUN YADAV");
        text(row, col, "manager email", "KY.KARUNYADAV@YOPMAIL.COM");
        text(row, col, "salary frequency", "Monthly");
        text(row, col, "currency", "Rupees");
        text(row, col, "pf(y/n)", "Y");
        text(row, col, "esic(y/n)", "Y");
        text(row, col, "pf", random.nextBoolean() ? "capped" : "actual");
        number(row, col, "probation period", 30);
        number(row, col, "notice period", 60);

        // salary
        number(row, col, "monthly ctc", 20000);
        number(row, col, "annual ctc", 20000 * 12);
        number(row, col, "basic salary", 13000);
        number(row, col, "da", 500 + 100 * random.nextInt(11));
        number(row, col, "hra", 7000);
        number(row, col, "special allowance", 3000);
        number(row, col, "conveyance allowance", 1600);
        number(row, col, "shift allowance", 400);
        number(row, col, "overtime", 800);
        number(row, col, "holiday allowance", 5000);
        number(row, col, "medical alloawance", 2000);
        number(row, col, "other benefits", 4000);
        number(row, col, "leave encashment", 800);
        number(row, col, "gross monthly", 18000 + 100 * random.nextInt(20)); // below the monthly CTC

        // these stay empty
        for (String name : new String[] {"pf employee", "esic employee", "labour welfare fund employee", "professional tax", "advance salary", "other deductions", "tds",
                "total deduction", "net take home salary", "pf employer", "esic employer", "labour welfare fund employer", "gratuity", "bonus", "other statutory benefits",
                "statutory benefits", "performance incentive", "insurance", "food", "cab", "staff welfare", "variable salary"}) {
            Integer c = col.get(name);
            if (c != null && row.getCell(c) != null) {
                row.getCell(c).setBlank();
            }
        }
    }

    private static void text(Row row, Map<String, Integer> col, String name, String value) {
        cell(row, col, name).setCellValue(value);
    }

    private static void number(Row row, Map<String, Integer> col, String name, double value) {
        cell(row, col, name).setCellValue(value);
    }

    private static Cell cell(Row row, Map<String, Integer> col, String name) {
        Integer c = col.get(name);
        if (c == null) {
            throw new IllegalStateException("The template has no column \"" + name + "\" (columns: " + col.keySet() + ")");
        }
        Cell cell = row.getCell(c) != null ? row.getCell(c) : row.createCell(c);
        if (cell.getCellType() == CellType.FORMULA) {
            cell.setBlank();
        }
        return cell;
    }

    // the values of the template row (client, project, code, country, contract duration ...) go to the next row too
    private static void copyFixedCells(Row from, Row to) {
        for (int c = 0; c < from.getLastCellNum(); c++) {
            Cell source = from.getCell(c);
            if (source == null) {
                continue;
            }
            Cell copy = to.createCell(c);
            copy.setCellStyle(source.getCellStyle());
            if (source.getCellType() == CellType.STRING) {
                copy.setCellValue(source.getStringCellValue());
            } else if (source.getCellType() == CellType.NUMERIC) {
                copy.setCellValue(source.getNumericCellValue());
            }
        }
    }

    // header name (lower case, only plain characters) -> column number
    private static Map<String, Integer> columns(Sheet sheet) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        Row header = sheet.getRow(0);
        for (int c = 0; c < header.getLastCellNum(); c++) {
            String name = new DataFormatter().formatCellValue(header.getCell(c)).toLowerCase(Locale.ROOT).replaceAll("[^\\x20-\\x7E]", "").replaceAll("\\s+", " ").trim();
            if (!name.isEmpty()) {
                columns.putIfAbsent(name, c);
            }
        }
        return columns;
    }
}
