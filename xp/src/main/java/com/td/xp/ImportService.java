package com.td.xp;

import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpSession;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import java.io.*;
import java.sql.*;
import java.time.*;
import java.time.format.*;
import java.math.BigDecimal;
import java.util.*;

@Service
public class ImportService {
    private final DataSource dataSource;
    private final boolean mysql;
    private final String lockName;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ImportService.class);
    private final Clock clock;
    public record Supplier(String name, String code, String contact, String phone, BigDecimal amount, LocalDate date, String note) {}
    public record Receipt(String batchId, int importedRows, boolean alreadyImported) {}
    public record UploadResult(String fileName, List<PreviewController.SheetData> sheets, SettlementValidator.Result validation,
                               String token, boolean canConfirm, String expiresAt, String existingFileBatchId) {}
    private record Pending(String token, Instant expires, PreviewController.Preview preview, List<Supplier> rows, String fileHash) {}
    public static class StorageFailure extends IllegalStateException {
        StorageFailure(String message, Throwable cause) {super(message,cause);}
    }
    public static class Conflict extends RuntimeException {
        final PreviewController.Preview preview;
        Conflict(String message, PreviewController.Preview preview) { super(message); this.preview=preview; }
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ImportService(DataSource dataSource) throws SQLException { this(dataSource, Clock.systemUTC()); }
    ImportService(String url) throws SQLException { this(url, Clock.systemUTC()); }
    ImportService(String url, Clock clock) throws SQLException { this(testDataSource(url),clock); }
    ImportService(DataSource dataSource, Clock clock) throws SQLException {
        this.dataSource=dataSource;this.clock=clock;
        try(var c=connect()) {
            mysql=c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
            lockName="supplier-import:"+Integer.toHexString(Objects.toString(c.getCatalog(),"").hashCode());
            if(mysql) {
                // Verify the existing schema using read-only statements. Never create or migrate production tables.
                try(var s=c.createStatement()) {
                    s.executeQuery("SELECT id,file_name,file_sha256,row_count FROM import_batch WHERE 1=0").close();
                    s.executeQuery("SELECT credit_code,supplier_name,contact,phone,amount,cooperation_date,note,batch_id FROM supplier WHERE 1=0").close();
                }
            } else if(!c.getMetaData().getDatabaseProductName().equalsIgnoreCase("H2")) {
                throw new SQLException("仅支持MySQL数据库（H2仅用于测试）");
            }
        }
    }
    private static DataSource testDataSource(String url) throws SQLException {
        if(!url.startsWith("jdbc:h2:")) throw new IllegalArgumentException("此构造方法仅供H2测试使用");
        var source=new DriverManagerDataSource(url,"sa","");
        try(var c=source.getConnection();var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS import_lock (id INT PRIMARY KEY)");
            s.execute("MERGE INTO import_lock KEY(id) VALUES(1)");
            s.execute("CREATE TABLE IF NOT EXISTS import_batch (id VARCHAR(36) PRIMARY KEY,file_name VARCHAR(255) NOT NULL,file_sha256 VARCHAR(64) NOT NULL UNIQUE,row_count INT NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS supplier (credit_code VARCHAR(18) PRIMARY KEY,supplier_name VARCHAR(255) NOT NULL,contact VARCHAR(100) NOT NULL,phone VARCHAR(11) NOT NULL,amount DECIMAL(20,2) NOT NULL,cooperation_date DATE NOT NULL,note CLOB,batch_id VARCHAR(36) NOT NULL REFERENCES import_batch(id))");
        }
        return source;
    }
    private Connection connect() throws SQLException { return dataSource.getConnection(); }
    public Set<String> existingCodes() {
        try(var c=connect()) { return existingCodes(c); }
        catch(SQLException e) {
            log.error("读取供应商数据库失败，SQLState={}",e.getSQLState());
            throw new StorageFailure("数据库暂时不可用，请检查数据库服务或稍后重试；当前未执行导入。",e);
        }
    }
    private Set<String> existingCodes(Connection c) throws SQLException {
        var codes=new HashSet<String>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT credit_code FROM supplier")){while(r.next())codes.add(r.getString(1));}
        return codes;
    }
    public UploadResult prepare(MultipartFile file, HttpSession session) throws IOException {
        synchronized(session) {
            session.removeAttribute("pendingImport");
            var preview=new PreviewController().parse(file,existingCodes(),true);
            if(preview.fileName().codePointCount(0,preview.fileName().length())>255) throw new IllegalArgumentException("文件名超过255个字符，请缩短文件名后重新上传。");
            String hash=fileHash(file);
            Receipt previous;
            try(var connection=connect()) { previous=findFile(connection,hash); }
            catch(SQLException e) { throw new StorageFailure("无法读取文件导入记录，请检查数据库连接后重试；当前未执行导入。",e); }
            if(previous!=null) {
                var old=preview.validation();var notices=new ArrayList<>(old.notices());
                notices.add("此文件已成功导入，原批次："+previous.batchId()+"，共"+previous.importedRows()+"条；不会再次写入。修改文件名也不会重复导入。");
                preview=new PreviewController.Preview(preview.fileName(),preview.sheets(),new SettlementValidator.Result(old.totalRows(),old.validRows(),old.invalidRows(),old.issues(),old.databaseChecked(),notices));
            }
            boolean ready=previous==null && preview.validation().issues().isEmpty() && preview.validation().totalRows()>0;
            String token=UUID.randomUUID().toString();Instant expires=clock.instant().plusSeconds(1800);
            var rows=ready?readSuppliers(file,preview):List.<Supplier>of();
            session.setAttribute("pendingImport",new Pending(token,expires,preview,rows,hash));
            return new UploadResult(preview.fileName(),preview.sheets(),preview.validation(),token,ready,expires.toString(),previous==null?null:previous.batchId());
        }
    }
    public Receipt confirm(String token, HttpSession session) {
        synchronized(session) {
            var pending=(Pending)session.getAttribute("pendingImport");
            if(pending==null || !pending.token().equals(token)) throw new Conflict("待确认批次不存在或已被新上传替换，请重新上传预览。",null);
            try(var c=connect()) {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                c.setAutoCommit(false);
                boolean namedLock=false;
                try {
                    // Serialize the recheck and writes across requests/connections, including different sessions.
                    if(mysql) {
                        try(var lock=c.prepareStatement("SELECT GET_LOCK(?,10)")) {
                            lock.setString(1,lockName);try(var r=lock.executeQuery()) {
                                if(!r.next() || r.getInt(1)!=1) throw new SQLException("等待导入锁超时，请重试确认。","HYT00");
                            }
                        }
                        namedLock=true;
                    } else {
                        try(var lock=c.prepareStatement("SELECT id FROM import_lock WHERE id=1 FOR UPDATE");var ignored=lock.executeQuery()){ignored.next();}
                    }
                    try(var s=c.prepareStatement("SELECT row_count FROM import_batch WHERE id=?")){
                        s.setString(1,token);try(var r=s.executeQuery()){if(r.next()){int count=r.getInt(1);c.commit();return new Receipt(token,count,true);}}
                    }
                    Receipt sameFile=findFile(c,pending.fileHash());
                    if(sameFile!=null) { c.commit();return sameFile; }
                    if(clock.instant().isAfter(pending.expires())) throw new Conflict("预览已超过30分钟，请重新上传校验。",null);
                    if(!pending.preview().validation().issues().isEmpty() || pending.rows().isEmpty()) throw new Conflict("当前批次仍有校验错误，请修正全部错误后重新上传。",pending.preview());
                    Set<String> existing=existingCodes(c);
                    if(pending.rows().stream().anyMatch(r->existing.contains(r.code()))) {
                        var refreshed=withDatabaseDuplicates(pending.preview(),existing);
                        throw new Conflict("预览后有供应商已被其他批次导入，本次未写入任何数据。请查看更新后的错误并重新上传。",refreshed);
                    }
                    try(var b=c.prepareStatement("INSERT INTO import_batch(id,row_count,file_sha256,file_name) VALUES(?,?,?,?)")) {b.setString(1,token);b.setInt(2,pending.rows().size());b.setString(3,pending.fileHash());b.setString(4,pending.preview().fileName());b.executeUpdate();}
                    try(var s=c.prepareStatement("INSERT INTO supplier(credit_code,supplier_name,contact,phone,amount,cooperation_date,note,batch_id) VALUES(?,?,?,?,?,?,?,?)")) {
                        for(var row:pending.rows()) {
                            s.setString(1,row.code());s.setString(2,row.name());s.setString(3,row.contact());s.setString(4,row.phone());
                            s.setBigDecimal(5,row.amount());s.setObject(6,row.date());s.setString(7,row.note());s.setString(8,token);s.addBatch();
                        }
                        s.executeBatch();
                    }
                    c.commit();return new Receipt(token,pending.rows().size(),false);
                } catch(Exception e) {
                    try { c.rollback(); } catch(SQLException rollback) {e.addSuppressed(rollback);}
                    throw e;
                } finally {
                    if(namedLock) {
                        try(var release=c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                            release.setString(1,lockName);try(var result=release.executeQuery()) {
                                if(!result.next() || result.getInt(1)!=1) c.abort(Runnable::run);
                            }
                        } catch(SQLException releaseFailure) {
                            log.error("释放导入数据库锁失败，正在丢弃连接，SQLState={}",releaseFailure.getSQLState());
                            try {c.abort(Runnable::run);}catch(SQLException ignored){}
                        }
                    }
                }
            } catch(Conflict e) {throw e;}
            catch(SQLException e) {
                log.error("供应商批次入库失败，batchId={}，SQLState={}",token,e.getSQLState());
                // Other applications may insert without using our named lock; unique indexes remain authoritative.
                if(e.getSQLState()!=null && e.getSQLState().startsWith("23")) {
                    try(var check=connect()) {
                        var same=findFile(check,pending.fileHash());if(same!=null)return same;
                        var codes=existingCodes(check);
                        if(pending.rows().stream().anyMatch(row->codes.contains(row.code())))
                            throw new Conflict("供应商已被其他操作导入，本次已整批回滚，请查看错误并重新上传。",withDatabaseDuplicates(pending.preview(),codes));
                    } catch(SQLException checkFailure) { e.addSuppressed(checkFailure); }
                }
                throw new StorageFailure("数据库入库未能完成。请重试当前批次确认，系统会防止重复写入；如持续失败请联系管理员检查数据库。",e);
            }
        }
    }
    private static String fileHash(MultipartFile file) throws IOException {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var input=new java.security.DigestInputStream(file.getInputStream(),digest)) {input.transferTo(OutputStream.nullOutputStream());}
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException("SHA-256不可用",e);}
    }
    private Receipt findFile(Connection c,String hash) throws SQLException {
        try(var query=c.prepareStatement("SELECT id,row_count FROM import_batch WHERE file_sha256=?")) {
            query.setString(1,hash);try(var result=query.executeQuery()) {
                return result.next()?new Receipt(result.getString(1),result.getInt(2),true):null;
            }
        }
    }
    private PreviewController.Preview withDatabaseDuplicates(PreviewController.Preview p, Set<String> codes) {
        var issues=new ArrayList<SettlementValidator.Issue>();var sheets=new ArrayList<PreviewController.SheetData>();int bad=0;
        for(var sheet:p.sheets()) {
            int codeCol=sheet.rows().isEmpty()?-1:sheet.rows().getFirst().cells().stream().map(SettlementValidator::clean).toList().indexOf("统一社会信用代码");
            var good=new ArrayList<PreviewController.RowData>();var invalid=new ArrayList<PreviewController.RowData>();
            for(var row:sheet.validRows()) {
                if(codeCol>=0 && codes.contains(SettlementValidator.clean(row.cells().get(codeCol)))) {
                    var issue=new SettlementValidator.Issue(sheet.name(),row.rowNumber(),codeCol+1,"统一社会信用代码","该供应商已在数据库中，不允许重复入库");
                    issues.add(issue);invalid.add(new PreviewController.RowData(row.rowNumber(),row.cells(),List.of(issue)));bad++;
                } else good.add(row);
            }
            sheets.add(new PreviewController.SheetData(sheet.name(),sheet.columnCount(),sheet.rows(),good,invalid,sheet.headerIssues()));
        }
        var old=p.validation();return new PreviewController.Preview(p.fileName(),sheets,new SettlementValidator.Result(old.totalRows(),old.totalRows()-bad,bad,issues,true,old.notices()));
    }
    private List<Supplier> readSuppliers(MultipartFile file,PreviewController.Preview preview) throws IOException {
        var result=new ArrayList<Supplier>();var formatter=new DataFormatter(Locale.CHINA);
        try(var input=file.getInputStream();var book=new XSSFWorkbook(input)) {
            for(var data:preview.sheets()) {
                if(data.validRows().isEmpty())continue;
                var sheet=book.getSheet(data.name());var header=data.rows().getFirst();
                var columns=new HashMap<String,Integer>();for(int i=0;i<header.cells().size();i++)columns.put(SettlementValidator.clean(header.cells().get(i)),i);
                for(var source:data.validRows()) {
                    var row=sheet.getRow(source.rowNumber()-1);var values=new HashMap<String,String>();
                    for(String field:SettlementValidator.FIELDS){Integer col=columns.get(field);Cell cell=col==null?null:row.getCell(col);values.put(field,SettlementValidator.clean(formatter.formatCellValue(cell)));}
                    Cell money=row.getCell(columns.get("结算金额")),phone=row.getCell(columns.get("手机号")),date=row.getCell(columns.get("合作开始日期"));
                    BigDecimal amount=(money.getCellType()==CellType.NUMERIC?BigDecimal.valueOf(money.getNumericCellValue()):new BigDecimal(values.get("结算金额"))).setScale(2,java.math.RoundingMode.UNNECESSARY);
                    String mobile=phone.getCellType()==CellType.NUMERIC?BigDecimal.valueOf(phone.getNumericCellValue()).stripTrailingZeros().toPlainString():values.get("手机号");
                    LocalDate day=date.getCellType()==CellType.NUMERIC?date.getLocalDateTimeCellValue().toLocalDate():parseDate(values.get("合作开始日期"));
                    result.add(new Supplier(values.get("供应商名称"),values.get("统一社会信用代码"),values.get("联系人"),mobile,amount,day,values.get("备注")));
                }
            }
        }
        return List.copyOf(result);
    }
    private LocalDate parseDate(String value) {
        for(String pattern:List.of("uuuu-M-d","uuuu/M/d","uuuu年M月d日"))try{return LocalDate.parse(value,DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT));}catch(DateTimeParseException ignored){}
        throw new IllegalArgumentException("日期不合法，请重新上传。");
    }
}
