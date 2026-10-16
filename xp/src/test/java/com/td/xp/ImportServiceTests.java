package com.td.xp;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.*;
import java.sql.*;
import java.time.*;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImportServiceTests {
    String url; ImportService service;
    @BeforeEach void setup() throws Exception {url="jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";service=new ImportService(url);}
    MockMultipartFile file(String... codes) throws Exception {
        try(var b=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=b.createSheet("名单");var h=sheet.createRow(2);
            for(int i=0;i<SettlementValidator.FIELDS.size();i++)h.createCell(i).setCellValue(SettlementValidator.FIELDS.get(i));
            for(int i=0;i<codes.length;i++){
                var r=sheet.createRow(4+i*2);String[] values={"供应商"+i,codes[i],"张三","13800138000","1234.567890123456789","2024/2/29","备注"};
                for(int j=0;j<values.length;j++)r.createCell(j).setCellValue(values[j]);
            }
            b.write(out);return new MockMultipartFile("file","名单.xlsx","",out.toByteArray());
        }
    }
    int count() throws Exception {try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM supplier")){r.next();return r.getInt(1);}}
    @Test void previewWritesNothingAndConfirmationPersistsExactValuesAndIsIdempotent() throws Exception {
        var session=new MockHttpSession();var upload=service.prepare(file("91310000AB00000001"),session);
        assertTrue(upload.canConfirm());assertTrue(upload.validation().databaseChecked());assertEquals(0,count());
        var result=service.confirm(upload.token(),session);assertEquals(1,count());assertFalse(result.alreadyImported());
        assertTrue(service.confirm(upload.token(),session).alreadyImported());assertEquals(1,count());
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement();var r=s.executeQuery("SELECT amount,cooperation_date,phone FROM supplier")){
            r.next();assertEquals("1234.567890123456789",r.getString(1));assertEquals("2024-02-29",r.getString(2));assertEquals("13800138000",r.getString(3));
        }
        var again=service.prepare(file("91310000AB00000001"),new MockHttpSession());assertFalse(again.canConfirm());assertEquals(5,again.validation().issues().getFirst().rowNumber());
    }
    @Test void blocksInvalidForeignAndReplacedBatches() throws Exception {
        var session=new MockHttpSession();var bad=service.prepare(file("bad"),session);assertFalse(bad.canConfirm());assertThrows(ImportService.Conflict.class,()->service.confirm(bad.token(),session));
        var first=service.prepare(file("91310000AB00000001"),session);assertThrows(ImportService.Conflict.class,()->service.confirm(first.token(),new MockHttpSession()));
        service.prepare(file("91310000AB00000002"),session);assertThrows(ImportService.Conflict.class,()->service.confirm(first.token(),session));assertEquals(0,count());
    }
    @Test void concurrentBatchesRecheckDatabaseAndNeverPartiallyImport() throws Exception {
        var a=new MockHttpSession();var b=new MockHttpSession();
        var one=service.prepare(file("91310000AB00000001"),a);var two=service.prepare(file("91310000AB00000001","91310000AB00000002"),b);
        try(var executor=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);
            Callable<Boolean> taskA=()->{gate.await();try{service.confirm(one.token(),a);return true;}catch(ImportService.Conflict e){return false;}};
            Callable<Boolean> taskB=()->{gate.await();try{service.confirm(two.token(),b);return true;}catch(ImportService.Conflict e){assertNotNull(e.preview);return false;}};
            var f1=executor.submit(taskA);var f2=executor.submit(taskB);gate.countDown();boolean r1=f1.get(20,TimeUnit.SECONDS),r2=f2.get(20,TimeUnit.SECONDS);
            assertNotEquals(r1,r2);assertEquals(r1?1:2,count());
        }
    }
    @Test void insertFailureRollsBackWholeBatchAndRetryWorks() throws Exception {
        var session=new MockHttpSession();var upload=service.prepare(file("91310000AB00000001","91310000AB00000002"),session);
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){s.execute("ALTER TABLE supplier ADD CONSTRAINT test_failure CHECK (credit_code <> '91310000AB00000002')");}
        assertThrows(IllegalStateException.class,()->service.confirm(upload.token(),session));assertEquals(0,count());
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){s.execute("ALTER TABLE supplier DROP CONSTRAINT test_failure");}
        assertEquals(2,service.confirm(upload.token(),session).importedRows());assertEquals(2,count());
    }
    @Test void httpCannotImportWithoutSessionPreview() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new PreviewController(service)).setControllerAdvice(new PreviewController.Errors()).build();
        mvc.perform(post("/api/imports/confirm").contentType("application/json").content("{\"token\":\"forged\"}")).andExpect(status().isConflict());
        var session=new MockHttpSession();
        var response=mvc.perform(multipart("/api/preview").file(file("91310000AB00000001")).session(session)).andExpect(status().isOk()).andReturn().getResponse();assertEquals(0,count());
        var match=java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.getContentAsString());assertTrue(match.find());String token=match.group(1);
        mvc.perform(post("/api/imports/confirm").session(session).contentType("application/json").content("{\"token\":\""+token+"\"}")).andExpect(status().isOk());assertEquals(1,count());
        mvc.perform(post("/api/imports/confirm").session(session).contentType("application/json").content("{\"token\":\""+token+"\"}")).andExpect(status().isOk());assertEquals(1,count());
    }
    @Test void expiredPreviewCannotWrite() throws Exception {
        class TestClock extends Clock {
            Instant now=Instant.parse("2026-10-16T00:00:00Z");
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return now;}
        }
        var clock=new TestClock();var timed=new ImportService(url,clock);var session=new MockHttpSession();
        var upload=timed.prepare(file("91310000AB00000001"),session);clock.now=clock.now.plusSeconds(1801);
        assertThrows(ImportService.Conflict.class,()->timed.confirm(upload.token(),session));assertEquals(0,count());
    }
    @Test void fileDatabaseSurvivesServiceRecreation(@TempDir Path directory) throws Exception {
        String disk="jdbc:h2:file:"+directory.resolve("settlement").toAbsolutePath().toString().replace('\\','/');
        var first=new ImportService(disk);var session=new MockHttpSession();var upload=first.prepare(file("91310000AB00000001"),session);
        first.confirm(upload.token(),session);
        var restarted=new ImportService(disk);var preview=restarted.prepare(file("91310000AB00000001"),new MockHttpSession());
        assertFalse(preview.canConfirm());assertTrue(preview.validation().issues().getFirst().message().contains("数据库"));
    }
    @Test void identicalFileRenamedOrReuploadedFromAnotherSessionCannotDuplicate() throws Exception {
        var original=file("91310000AB00000001");var firstSession=new MockHttpSession();
        var first=service.prepare(original,firstSession);var receipt=service.confirm(first.token(),firstSession);
        var renamed=new MockMultipartFile("file","改名名单.xlsx","",original.getBytes());var otherSession=new MockHttpSession();
        var again=service.prepare(renamed,otherSession);
        assertFalse(again.canConfirm());assertEquals(receipt.batchId(),again.existingFileBatchId());
        assertTrue(again.validation().notices().stream().anyMatch(n->n.contains("此文件已成功导入")));
        var retry=service.confirm(again.token(),otherSession);assertTrue(retry.alreadyImported());assertEquals(receipt.batchId(),retry.batchId());assertEquals(1,count());
        try(var c=DriverManager.getConnection(url,"sa","");var stmt=c.createStatement();var rs=stmt.executeQuery("SELECT COUNT(*) FROM import_batch")){rs.next();assertEquals(1,rs.getInt(1));}
    }
    @Test void sameFileConfirmedConcurrentlyCreatesOnlyOneBatch() throws Exception {
        var original=file("91310000AB00000001","91310000AB00000002");
        var a=new MockHttpSession();var b=new MockHttpSession();
        var first=service.prepare(original,a);var second=service.prepare(original,b);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var one=executor.submit(()->{gate.await();return service.confirm(first.token(),a);});
            var two=executor.submit(()->{gate.await();return service.confirm(second.token(),b);});gate.countDown();
            var r1=one.get(20,TimeUnit.SECONDS);var r2=two.get(20,TimeUnit.SECONDS);
            assertEquals(r1.batchId(),r2.batchId());assertNotEquals(r1.alreadyImported(),r2.alreadyImported());assertEquals(2,count());
        }
    }
    @Test void fileFingerprintSurvivesRestart(@TempDir Path directory) throws Exception {
        String disk="jdbc:h2:file:"+directory.resolve("fingerprint").toAbsolutePath().toString().replace('\\','/');
        var original=file("91310000AB00000001");var first=new ImportService(disk);var session=new MockHttpSession();
        var preview=first.prepare(original,session);var receipt=first.confirm(preview.token(),session);
        var restarted=new ImportService(disk);var after=restarted.prepare(original,new MockHttpSession());
        assertFalse(after.canConfirm());assertEquals(receipt.batchId(),after.existingFileBatchId());
    }
}
