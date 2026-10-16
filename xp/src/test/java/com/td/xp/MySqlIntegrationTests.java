package com.td.xp;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import java.sql.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in, only touches a separately created disposable database. */
@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL", matches=".+")
class MySqlIntegrationTests {
    DriverManagerDataSource source; ImportService service;
    @BeforeEach void setup() throws Exception {
        source=new DriverManagerDataSource(System.getenv("MYSQL_TEST_URL"),System.getenv("MYSQL_TEST_USER"),System.getenv("MYSQL_TEST_PASSWORD"));
        try(var c=source.getConnection();var s=c.createStatement()) {
            if(!c.getCatalog().startsWith("codex_import_test_"))throw new IllegalStateException("MySQL测试仅允许使用codex_import_test_前缀的隔离库");
            s.executeUpdate("DELETE FROM supplier");s.executeUpdate("DELETE FROM import_batch");
        }
        service=new ImportService(source);
    }
    int count(String table)throws Exception{try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getInt(1);}}
    org.springframework.mock.web.MockMultipartFile file(String... codes)throws Exception{return new ImportServiceTests().file(codes);}
    @Test void realMysqlPreviewConfirmDuplicateAndStoredValues()throws Exception{
        var file=file("91310000AB00000001");var session=new MockHttpSession();var upload=service.prepare(file,session);
        assertTrue(upload.canConfirm());assertEquals(0,count("supplier"));
        var receipt=service.confirm(upload.token(),session);assertFalse(receipt.alreadyImported());assertEquals(1,count("supplier"));assertEquals(1,count("import_batch"));
        assertTrue(service.confirm(upload.token(),session).alreadyImported());
        var again=service.prepare(file,new MockHttpSession());assertFalse(again.canConfirm());assertEquals(receipt.batchId(),again.existingFileBatchId());
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT s.amount,s.cooperation_date,b.file_name FROM supplier s JOIN import_batch b ON b.id=s.batch_id")){
            assertTrue(r.next());assertEquals("1234.56",r.getString(1));assertEquals("2024-02-29",r.getString(2));assertEquals("名单.xlsx",r.getString(3));
        }
    }
    @Test void concurrentSameFileWritesOnlyOnceAndReleasesConnectionLock()throws Exception{
        var file=file("91310000AB00000001","91310000AB00000002");var a=new MockHttpSession();var b=new MockHttpSession();
        var first=service.prepare(file,a);var second=service.prepare(file,b);
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);var one=pool.submit(()->{gate.await();return service.confirm(first.token(),a);});var two=pool.submit(()->{gate.await();return service.confirm(second.token(),b);});gate.countDown();
            var r1=one.get(20,TimeUnit.SECONDS);var r2=two.get(20,TimeUnit.SECONDS);assertEquals(r1.batchId(),r2.batchId());assertNotEquals(r1.alreadyImported(),r2.alreadyImported());
        }
        assertEquals(2,count("supplier"));assertEquals(1,count("import_batch"));
        try(var c=source.getConnection();var s=c.prepareStatement("SELECT IS_FREE_LOCK(?)")){s.setString(1,"supplier-import:"+Integer.toHexString(c.getCatalog().hashCode()));try(var r=s.executeQuery()){r.next();assertEquals(1,r.getInt(1));}}
    }
    @Test void databaseFailureRollsBackSuppliersBatchAndFingerprint()throws Exception{
        var session=new MockHttpSession();var upload=service.prepare(file("91310000AB00000001","91310000AB00000002"),session);
        try(var c=source.getConnection();var s=c.createStatement()){s.execute("CREATE TRIGGER reject_test BEFORE INSERT ON supplier FOR EACH ROW BEGIN IF NEW.credit_code='91310000AB00000002' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='intentional isolated test failure'; END IF; END");}
        try{assertThrows(IllegalStateException.class,()->service.confirm(upload.token(),session));assertEquals(0,count("supplier"));assertEquals(0,count("import_batch"));}
        finally{try(var c=source.getConnection();var s=c.createStatement()){s.execute("DROP TRIGGER reject_test");}}
        assertEquals(2,service.confirm(upload.token(),session).importedRows());assertEquals(1,count("import_batch"));
    }
    @Test void concurrentOverlappingFilesKeepWholeBatchAtomic()throws Exception{
        var a=new MockHttpSession();var b=new MockHttpSession();var first=service.prepare(file("91310000AB00000001"),a);var second=service.prepare(file("91310000AB00000001","91310000AB00000002"),b);
        service.confirm(first.token(),a);
        var error=assertThrows(ImportService.Conflict.class,()->service.confirm(second.token(),b));assertNotNull(error.preview);assertEquals(5,error.preview.validation().issues().getFirst().rowNumber());assertEquals(1,count("supplier"));assertEquals(1,count("import_batch"));
        var response=new PreviewController(service).exportErrors(file("91310000AB00000001"));assertTrue(response.getHeaders().getContentType().toString().contains("wordprocessingml"));
    }
}

