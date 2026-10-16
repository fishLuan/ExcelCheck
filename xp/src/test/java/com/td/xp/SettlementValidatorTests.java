package com.td.xp;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementValidatorTests {
    private Sheet sheet(XSSFWorkbook book, String name) {
        Sheet s=book.createSheet(name); Row h=s.createRow(0);
        for(int i=0;i<SettlementValidator.FIELDS.size();i++)h.createCell(i).setCellValue(SettlementValidator.FIELDS.get(i));
        return s;
    }
    private Row row(Sheet sheet,int index) {
        Row r=sheet.createRow(index);
        String[] values={"供应商甲","91310000ABCDEFGH12","张三","13800138000","1234.50","2024-2-29",""};
        for(int i=0;i<values.length;i++)r.createCell(i).setCellValue(values[i]);
        return r;
    }
    @Test void acceptsValidRowsAndReorderedHeaders() throws Exception {
        try(var b=new XSSFWorkbook()){
            Sheet s=sheet(b,"结算");Row r=row(s,1);
            s.getRow(0).getCell(0).setCellValue("联系人");s.getRow(0).getCell(2).setCellValue("供应商名称");
            r.getCell(4).setCellValue(0);
            var result=SettlementValidator.validate(b);
            assertEquals(1,result.validRows());assertTrue(result.issues().isEmpty());assertFalse(result.databaseChecked());
        }
    }
    @Test void reportsAllFieldFailuresAtOriginalRow() throws Exception {
        try(var b=new XSSFWorkbook()){
            Row r=row(sheet(b,"结算"),5);
            String[] values={"　 ","91310000abcdefghi2"," ","23800138000","-0.001","2023-2-29"};
            for(int i=0;i<values.length;i++)r.getCell(i).setCellValue(values[i]);
            var result=SettlementValidator.validate(b);
            assertEquals(6,result.issues().size());assertEquals(1,result.invalidRows());
            assertTrue(result.issues().stream().allMatch(e->e.rowNumber()==6));
        }
    }
    @Test void detectsDuplicatesAcrossSheetsOnBothRows() throws Exception {
        try(var b=new XSSFWorkbook()){
            row(sheet(b,"甲"),1);row(sheet(b,"乙"),3);
            var result=SettlementValidator.validate(b);
            assertEquals(2,result.invalidRows());assertEquals(2,result.issues().size());
        }
    }
    @Test void validatesUnderlyingAmountAndExcelDates() throws Exception {
        try(var b=new XSSFWorkbook()){
            Row r=row(sheet(b,"结算"),1);
            r.getCell(4).setCellValue(-0.001);
            var style=b.createCellStyle();style.setDataFormat(b.createDataFormat().getFormat("0.00"));r.getCell(4).setCellStyle(style);
            r.getCell(5).setCellValue(java.time.LocalDate.of(2024,2,29));
            var date=b.createCellStyle();date.setDataFormat(b.createDataFormat().getFormat("yyyy-mm-dd"));r.getCell(5).setCellStyle(date);
            var result=SettlementValidator.validate(b);
            assertEquals(1,result.issues().size());assertEquals("结算金额",result.issues().getFirst().field());
            r.getCell(4).setCellValue(12);r.getCell(5).setCellValue(60);
            assertEquals("合作开始日期",SettlementValidator.validate(b).issues().getFirst().field());
        }
    }
    @Test void rejectsMissingAndDuplicateHeadersAndFormula() throws Exception {
        try(var b=new XSSFWorkbook()){
            Sheet s=sheet(b,"结算");Row r=row(s,1);
            s.getRow(0).getCell(0).setCellValue("联系人");
            r.getCell(4).setCellFormula("1+1");
            var result=SettlementValidator.validate(b);
            assertEquals(1,result.invalidRows());assertTrue(result.issues().stream().anyMatch(e->e.message().startsWith("缺少")));
            assertTrue(result.issues().stream().anyMatch(e->e.message().startsWith("表头重复")));
            assertTrue(result.issues().stream().anyMatch(e->e.message().contains("公式")));
        }
    }
}
