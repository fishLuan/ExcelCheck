package com.td.xp;

import org.apache.poi.ss.usermodel.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.*;
import java.util.*;

/** Validates original cells, never rounded display strings. */
public final class SettlementValidator {
    static final List<String> FIELDS = List.of("供应商名称", "统一社会信用代码", "联系人", "手机号", "结算金额", "合作开始日期", "备注");
    public record Issue(String sheet, int rowNumber, int columnNumber, String field, String message) {}
    public record Result(int totalRows, int validRows, int invalidRows, List<Issue> issues,
                         boolean databaseChecked, List<String> notices) {}
    private record Entry(String sheet, int row, int column) {}

    public static Result validate(Workbook book) {
        return validate(book, Set.of(), false);
    }
    public static Result validate(Workbook book, Set<String> existingCodes, boolean databaseChecked) {
        var issues = new ArrayList<Issue>();
        var codes = new HashMap<String, List<Entry>>();
        int total = 0;
        var formatter = new DataFormatter(Locale.CHINA);
        formatter.setUseCachedValuesForFormulaCells(true);
        for (Sheet sheet : book) {
            int index = book.getSheetIndex(sheet);
            if (book.isSheetHidden(index) || book.isSheetVeryHidden(index)) continue;
            Row header = null;
            for (Row row : sheet) if (nonempty(row, formatter)) { header = row; break; }
            if (header == null) continue;
            Map<String, Integer> columns = new HashMap<>();
            for (Cell cell : header) {
                String title = clean(formatter.formatCellValue(cell));
                if (!FIELDS.contains(title)) continue;
                if (columns.putIfAbsent(title, cell.getColumnIndex()) != null)
                    issues.add(new Issue(sheet.getSheetName(), header.getRowNum()+1, cell.getColumnIndex()+1, title, "表头重复，无法确定字段对应列"));
            }
            for (String field : FIELDS.subList(0, 6)) if (!columns.containsKey(field))
                issues.add(new Issue(sheet.getSheetName(), header.getRowNum()+1, 0, field, "缺少必填字段表头，请使用字段名称作为表头"));
            boolean hasData = false;
            for (Row row : sheet) {
                if (row.getRowNum() <= header.getRowNum() || !nonempty(row, formatter)) continue;
                total++; hasData = true;
                for (String field : FIELDS.subList(0, 6)) {
                    Integer col = columns.get(field);
                    if (col == null) continue;
                    Cell cell = row.getCell(col);
                    String value = clean(formatter.formatCellValue(cell));
                    String error = null;
                    if (cell != null && cell.getCellType() == CellType.FORMULA) error = "请将公式转换为实际值后上传，避免使用过期计算结果";
                    else if (value.isEmpty()) error = "必填，不能是空值或纯空格";
                    else if (field.equals("统一社会信用代码") && !value.matches("[0-9A-HJ-NPQRTUWXY]{18}")) error = "必须为18位数字或大写字母，不能包含 I、O、Z、S、V";
                    else if (databaseChecked && field.equals("供应商名称") && value.codePointCount(0,value.length()) > 255) error = "供应商名称超过数据库允许的255个字符";
                    else if (databaseChecked && field.equals("联系人") && value.codePointCount(0,value.length()) > 100) error = "联系人超过数据库允许的100个字符";
                    else if (field.equals("手机号")) {
                        String phone = raw(cell, value);
                        if (!phone.matches("1[0-9]{10}")) error = "必须为11位数字，且以1开头";
                    } else if (field.equals("结算金额")) {
                        try {
                            String number = raw(cell, value);
                            if (!number.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) throw new NumberFormatException();
                            var amount = new BigDecimal(number);
                            if (amount.signum() < 0) error = "不能为负数";
                            else if (databaseChecked) {
                                if (amount.stripTrailingZeros().scale() > 2) error = "数据库金额列只支持最多2位有效小数，请修正后上传；系统不会自动四舍五入";
                                else if (amount.compareTo(new BigDecimal("999999999999999999.99")) > 0) error = "金额超过数据库 DECIMAL(20,2) 上限999999999999999999.99";
                            }
                        } catch (NumberFormatException e) { error = "必须是有效数字（不含货币符号或千分位文字）"; }
                    } else if (field.equals("合作开始日期") && !validDate(cell, value, book, databaseChecked ? 1000 : 1)) error = databaseChecked ? "必须是真实存在的1000至9999年日期；支持 Excel 日期、yyyy-MM-dd、yyyy/M/d、yyyy年M月d日" : "必须是真实存在的日期；请使用 Excel 日期或 yyyy-MM-dd、yyyy/M/d、yyyy年M月d日";
                    if (error != null) issues.add(new Issue(sheet.getSheetName(), row.getRowNum()+1, col+1, field, error));
                    if (field.equals("统一社会信用代码") && error == null) {
                        codes.computeIfAbsent(value, key -> new ArrayList<>()).add(new Entry(sheet.getSheetName(), row.getRowNum()+1, col+1));
                        if (existingCodes.contains(value)) issues.add(new Issue(sheet.getSheetName(), row.getRowNum()+1, col+1, field, "该供应商已在数据库中，不允许重复入库"));
                    }
                }
                if (databaseChecked && columns.containsKey("备注")) {
                    int col=columns.get("备注");String note=clean(formatter.formatCellValue(row.getCell(col)));
                    if (note.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65535)
                        issues.add(new Issue(sheet.getSheetName(),row.getRowNum()+1,col+1,"备注","备注超过数据库 TEXT 字段65535字节限制"));
                }
            }
            if (!hasData) issues.add(new Issue(sheet.getSheetName(), header.getRowNum()+1, 0, "数据", "只有表头，没有供应商数据"));
        }
        for (var entries : codes.values()) if (entries.size() > 1) {
            String positions = entries.stream().limit(5).map(e -> e.sheet()+" 第"+e.row()+"行").collect(java.util.stream.Collectors.joining("、"));
            for (var entry : entries) issues.add(new Issue(entry.sheet(), entry.row(), entry.column(), "统一社会信用代码", "文件内供应商重复（共"+entries.size()+"条，最多列出5处）："+positions));
        }
        var badRows = new HashSet<String>();
        for (var issue : issues) {
            Sheet sheet = book.getSheet(issue.sheet());
            Row first = null;
            for (Row row : sheet) if (nonempty(row, formatter)) { first = row; break; }
            if (first != null && issue.rowNumber() > first.getRowNum()+1) badRows.add(issue.sheet()+"\u0000"+issue.rowNumber());
        }
        // A broken schema invalidates every data row of that sheet.
        for (var issue : issues) if (issue.message().startsWith("缺少") || issue.message().startsWith("表头重复")) {
            Sheet sheet = book.getSheet(issue.sheet());
            for (Row row : sheet) if (row.getRowNum()+1 > issue.rowNumber() && nonempty(row, formatter)) badRows.add(issue.sheet()+"\u0000"+(row.getRowNum()+1));
        }
        return new Result(total, total-badRows.size(), badRows.size(), issues, databaseChecked, List.of(
            databaseChecked ? "已完成数据库查重；确认入库时会再次检查，全部通过才整批写入。" : "尚未连接供应商数据库：仅完成文件内查重，未校验数据库已有供应商；当前不能确认入库。",
            databaseChecked ? "金额按现有数据库 DECIMAL(20,2) 校验：非负、最多2位有效小数和18位整数，不自动舍入。业务上的合理金额阈值仍待明确。" : "“结算金额必须合理”尚无业务阈值：当前检查有效数字及非负，不设置金额上限或小数位限制。"));
    }
    static String clean(String text) { return text == null ? "" : text.replace('\u00a0',' ').replace('\u3000',' ').strip(); }
    private static boolean nonempty(Row row, DataFormatter formatter) {
        for (Cell cell : row) if (!clean(formatter.formatCellValue(cell)).isEmpty()) return true;
        return false;
    }
    private static String raw(Cell cell, String fallback) {
        if (cell != null && cell.getCellType() == CellType.NUMERIC)
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        return fallback;
    }
    private static boolean validDate(Cell cell, String value, Workbook book, int minYear) {
        if (cell != null && cell.getCellType() == CellType.NUMERIC) {
            double serial = cell.getNumericCellValue();
            boolean date1904 = book instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook x && x.isDate1904();
            if (!DateUtil.isCellDateFormatted(cell) || !DateUtil.isValidExcelDate(serial) || (!date1904 && (int)serial == 60)) return false;
            try { int year = cell.getLocalDateTimeCellValue().getYear(); return year >= minYear && year <= 9999; }
            catch (RuntimeException e) { return false; }
        }
        for (String pattern : List.of("uuuu-M-d", "uuuu/M/d", "uuuu年M月d日")) {
            try { LocalDate date = LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)); if(date.getYear() >= minYear && date.getYear() <= 9999) return true; }
            catch (DateTimeParseException ignored) {}
        }
        return false;
    }
}
