package com.td.xp;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class PreviewControllerTests {
    private final PreviewController controller = new PreviewController();
    @Test void preservesPositionsFormatsAndOriginalRowNumbers() throws Exception {
        byte[] bytes;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("结算名单");
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue("供应商甲");
            var amount = row.createCell(2); amount.setCellValue(1234.5);
            var money = book.createCellStyle(); money.setDataFormat(book.createDataFormat().getFormat("0.00")); amount.setCellStyle(money);
            var date = row.createCell(3); date.setCellValue(LocalDate.of(2026,10,16));
            var style = book.createCellStyle(); style.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd")); date.setCellStyle(style);
            book.createSheet("空表"); book.write(out); bytes = out.toByteArray();
        }
        var result = controller.preview(new MockMultipartFile("file", "结算.xlsx", "application/octet-stream", bytes));
        assertEquals(2, result.sheets().size());
        var sheet = result.sheets().getFirst();
        assertEquals(4, sheet.columnCount());
        assertEquals(3, sheet.rows().getFirst().rowNumber());
        assertEquals(java.util.List.of("供应商甲", "", "1234.50", "2026-10-16"), sheet.rows().getFirst().cells());
    }
    @Test void rejectsWrongExtensionAndFakeExcelAndEmptyFiles() {
        for (var file : java.util.List.of(
                new MockMultipartFile("file", "a.csv", "text/plain", new byte[]{1}),
                new MockMultipartFile("file", "a.xlsx", "text/plain", "not excel".getBytes()),
                new MockMultipartFile("file", "a.xlsx", "text/plain", new byte[0]))) {
            var error = assertThrows(IllegalArgumentException.class, () -> controller.preview(file));
            assertFalse(error.getMessage().isBlank());
            assertFalse(error.getMessage().contains("Exception"));
        }
    }
    @Test void rejectsWorkbookWithNoData() throws Exception {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            book.createSheet("空表"); book.write(out);
            var error = assertThrows(IllegalArgumentException.class, () -> controller.preview(new MockMultipartFile("file", "empty.xlsx", "", out.toByteArray())));
            assertTrue(error.getMessage().contains("没有可预览"));
        }
    }
}
