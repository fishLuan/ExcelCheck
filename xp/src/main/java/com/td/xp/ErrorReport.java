package com.td.xp;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

/** Generated from server validation of the uploaded workbook, never client-supplied errors. */
public final class ErrorReport {
    private ErrorReport() {}
    public static byte[] create(PreviewController.Preview preview) throws IOException {
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            var section = doc.getDocument().getBody().addNewSectPr();
            var size = section.addNewPgSz(); size.setW(BigInteger.valueOf(16838)); size.setH(BigInteger.valueOf(11906)); size.setOrient(STPageOrientation.LANDSCAPE);
            var margins = section.addNewPgMar();
            margins.setTop(BigInteger.valueOf(720)); margins.setBottom(BigInteger.valueOf(720));
            margins.setLeft(BigInteger.valueOf(720)); margins.setRight(BigInteger.valueOf(720));
            var title = doc.createParagraph(); title.setStyle("Title"); title.setSpacingAfter(160);
            run(title, "供应商结算校验错误报告", true, 20);
            paragraph(doc, "源文件：" + preview.fileName());
            var v = preview.validation();
            paragraph(doc, "共 " + v.totalRows() + " 条数据，" + v.validRows() + " 条通过字段及文件内查重，" + v.invalidRows() + " 条待修正。以下包含全部工作表的错误，不受页面筛选或分页影响。");
            paragraph(doc, "修正方法：根据工作表、Excel 原始行号和单元格位置，在原 Excel 文件中修改并保存，再重新上传校验。空行跳过后不重新编号；同一行多个错误分别列出。表头问题须先修正表头。");
            for (String notice : v.notices()) paragraph(doc, notice);
            for (var sheet : preview.sheets()) {
                if (sheet.headerIssues().isEmpty() && sheet.invalidRows().isEmpty()) continue;
                var heading = doc.createParagraph(); heading.setSpacingBefore(180); heading.setKeepNext(true); heading.setSpacingAfter(100);
                run(heading, "工作表 " + sheet.name(), true, 13);
                var table = doc.createTable(1, 5); table.setWidth("100%");
                var props = table.getCTTbl().getTblPr(); props.addNewTblLayout().setType(STTblLayoutType.FIXED);
                int[] widths = {850,1100,2000,2600,8700};
                var grid = table.getCTTbl().getTblGrid();
                if (grid == null) grid = table.getCTTbl().addNewTblGrid();
                while (grid.sizeOfGridColArray() > 0) grid.removeGridCol(0);
                for (int width : widths) grid.addNewGridCol().setW(BigInteger.valueOf(width));
                String[] labels = {"Excel 行号", "单元格", "字段", "原始值", "错误原因"};
                var header = table.getRow(0); header.setRepeatHeader(true);
                for (int i=0;i<labels.length;i++) setCell(header.getCell(i), labels[i], widths[i], true);
                for (var issue : sheet.headerIssues()) {
                    var source = sheet.rows().stream().filter(r -> r.rowNumber() == issue.rowNumber()).findFirst().orElse(null);
                    addIssue(table, issue, source, widths);
                }
                for (var row : sheet.invalidRows()) for (var issue : row.issues()) addIssue(table, issue, row, widths);
                doc.createParagraph().setSpacingAfter(100);
            }
            doc.write(out); return out.toByteArray();
        }
    }
    private static void addIssue(XWPFTable table, SettlementValidator.Issue issue, PreviewController.RowData source, int[] widths) {
        int col = issue.columnNumber();
        String original = col > 0 && source != null && source.cells().size() >= col ? source.cells().get(col-1) : "";
        if (original.isBlank()) original = "（空）";
        String coordinate = col > 0 ? org.apache.poi.ss.util.CellReference.convertNumToColString(col-1)+issue.rowNumber() : "整行 / 表头";
        var row = table.createRow();
        String[] values = {String.valueOf(issue.rowNumber()), coordinate, issue.field(), original, issue.message()};
        for (int i=0;i<values.length;i++) setCell(row.getCell(i), values[i], widths[i], false);
    }
    private static void setCell(XWPFTableCell cell, String text, int width, boolean header) {
        cell.setWidth(String.valueOf(width)); cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        if (header) cell.setColor("E9EFF4");
        var p = cell.getParagraphs().getFirst(); p.setSpacingBefore(60); p.setSpacingAfter(60);
        run(p, text, header, 10);
    }
    private static void paragraph(XWPFDocument doc, String text) {
        var p = doc.createParagraph(); p.setSpacingAfter(100); run(p, text, false, 10);
    }
    private static void run(XWPFParagraph p, String text, boolean bold, int size) {
        var r=p.createRun(); r.setFontFamily("Microsoft YaHei"); r.setFontSize(size); r.setBold(bold);
        // Word XML cannot contain control characters from arbitrary spreadsheet content.
        String clean=text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        String[] lines=clean.split("\\R", -1);
        for(int i=0;i<lines.length;i++){if(i>0)r.addBreak();r.setText(lines[i]);}
    }
}


