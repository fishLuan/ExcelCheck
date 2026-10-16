package com.td.xp;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.io.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class StorageValidationTests {
    private PreviewController.Preview check(int col,Object value)throws Exception{
        var source=new ImportServiceTests().file("91310000AB00000001");
        try(var b=new XSSFWorkbook(source.getInputStream());var out=new ByteArrayOutputStream()){
            var cell=b.getSheetAt(0).getRow(4).getCell(col);
            if(value instanceof Double number)cell.setCellValue(number);else cell.setCellValue((String)value);
            b.write(out);return new PreviewController().parse(new MockMultipartFile("file","test.xlsx","",out.toByteArray()),java.util.Set.of(),true);
        }
    }
    @Test void preventsSilentRoundingOverflowAndAcceptsExactBoundary()throws Exception{
        for(String amount:java.util.List.of("1.239","1000000000000000000.00")){
            var result=check(4,amount);assertEquals(1,result.validation().invalidRows());assertEquals(5,result.validation().issues().getFirst().rowNumber());assertEquals("结算金额",result.validation().issues().getFirst().field());
        }
        assertTrue(check(4,"999999999999999999.99").validation().issues().isEmpty());assertTrue(check(4,"1.2300").validation().issues().isEmpty());
    }
    @Test void validatesMysqlCharacterByteAndDateLimitsBeforeConfirmation()throws Exception{
        assertEquals("供应商名称",check(0,"甲".repeat(256)).validation().issues().getFirst().field());
        assertEquals("联系人",check(2,"甲".repeat(101)).validation().issues().getFirst().field());
        assertEquals("备注",check(6,"甲".repeat(22000)).validation().issues().getFirst().field());
        assertEquals("合作开始日期",check(5,"0999-01-01").validation().issues().getFirst().field());
        assertTrue(check(0,"甲".repeat(255)).validation().issues().isEmpty());
    }
}
