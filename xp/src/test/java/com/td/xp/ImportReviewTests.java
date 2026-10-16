package com.td.xp;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImportReviewTests {
    private MockMultipartFile file(boolean brokenHeader, boolean invalid) throws Exception {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet=book.createSheet("名单");var header=sheet.createRow(2);
            for(int i=0;i<SettlementValidator.FIELDS.size();i++) header.createCell(i).setCellValue(SettlementValidator.FIELDS.get(i));
            if(brokenHeader)header.getCell(0).setCellValue("错误表头");
            for(int index : new int[]{4,8}) {
                var row=sheet.createRow(index);
                String[] values={"供应商", "91310000AB0000000"+(index==4?"1":"2"), "张三", "13800138000", "12.34", "2026-10-16", ""};
                for(int i=0;i<values.length;i++)row.createCell(i).setCellValue(values[i]);
                if(invalid&&index==8){row.getCell(3).setCellValue("23800138000");row.getCell(4).setCellValue(-1);}
            }
            book.write(out);return new MockMultipartFile("file","名单.xlsx","application/octet-stream",out.toByteArray());
        }
    }
    @Test void separatesRowsAndKeepsExcelCoordinatesAfterBlankRows() throws Exception {
        var result=new PreviewController().preview(file(false,true));var sheet=result.sheets().getFirst();
        assertEquals(5,sheet.validRows().getFirst().rowNumber());assertEquals(9,sheet.invalidRows().getFirst().rowNumber());
        assertEquals(2,sheet.invalidRows().getFirst().issues().size());
        assertEquals(1,result.validation().validRows());assertEquals(1,result.validation().invalidRows());
    }
    @Test void brokenHeaderPreventsRowsFromAppearingAsValid() throws Exception {
        var result=new PreviewController().preview(file(true,false));var sheet=result.sheets().getFirst();
        assertTrue(sheet.validRows().isEmpty());assertEquals(2,sheet.invalidRows().size());
        assertEquals(3,sheet.headerIssues().getFirst().rowNumber());
        assertTrue(sheet.invalidRows().getFirst().issues().getFirst().message().contains("表头第3行"));
    }
    @Test void fixingWorkbookThenReuploadingClearsErrors() throws Exception {
        var controller=new PreviewController();assertEquals(1,controller.preview(file(false,true)).validation().invalidRows());
        var fixed=controller.preview(file(false,false));assertEquals(2,fixed.validation().validRows());assertTrue(fixed.validation().issues().isEmpty());
    }
    @Test void exportsRealWordContainingEveryErrorAndOriginalCoordinates() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new PreviewController()).setControllerAdvice(new PreviewController.Errors()).build();
        var response=mvc.perform(multipart("/api/errors/word").file(file(false,true))).andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(response.getContentType().contains("wordprocessingml"));assertTrue(response.getHeader("Content-Disposition").contains("attachment"));
        try(var doc=new XWPFDocument(new ByteArrayInputStream(response.getContentAsByteArray()))){
            assertEquals(1,doc.getTables().size());var table=doc.getTables().getFirst();assertEquals(3,table.getRows().size());
            String text=table.getText();assertTrue(text.contains("D9"));assertTrue(text.contains("E9"));assertTrue(text.contains("手机号"));assertTrue(text.contains("不能为负数"));
        }
        mvc.perform(multipart("/api/errors/word").file(file(false,false))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/errors/word").file(new MockMultipartFile("file","fake.xlsx","",new byte[]{1}))).andExpect(status().isBadRequest());
    }
}
