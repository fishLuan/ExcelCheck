package com.td.xp;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
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
    private final String url;
    private final Clock clock;
    public record Supplier(String name, String code, String contact, String phone, BigDecimal amount, LocalDate date, String note) {}
    public record Receipt(String batchId, int importedRows, boolean alreadyImported) {}
    public record UploadResult(String fileName, List<PreviewController.SheetData> sheets, SettlementValidator.Result validation,
                               String token, boolean canConfirm, String expiresAt, String existingFileBatchId) {}
    private record Pending(String token, Instant expires, PreviewController.Preview preview, List<Supplier> rows, String fileHash) {}
    public static class Conflict extends RuntimeException {
        final PreviewController.Preview preview;
        Conflict(String message, PreviewController.Preview preview) { super(message); this.preview=preview; }
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ImportService(@Value("${settlement.database-url:jdbc:h2:file:./data/settlement}") String url) throws SQLException { this(url, Clock.systemUTC()); }
    ImportService(String url, Clock clock) throws SQLException {
        this.url=url; this.clock=clock;
        try(var c=connect();var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS import_lock (id INT PRIMARY KEY)");
            s.execute("MERGE INTO import_lock KEY(id) VALUES(1)");
            s.execute("CREATE TABLE IF NOT EXISTS import_batch (id VARCHAR(36) PRIMARY KEY, row_count INT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("ALTER TABLE import_batch ADD COLUMN IF NOT EXISTS file_sha256 VARCHAR(64)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_import_batch_file_sha256 ON import_batch(file_sha256)");
            s.execute("CREATE TABLE IF NOT EXISTS supplier (credit_code VARCHAR(18) PRIMARY KEY, supplier_name CLOB NOT NULL, contact CLOB NOT NULL, phone VARCHAR(11) NOT NULL, amount CLOB NOT NULL, cooperation_date DATE NOT NULL, note CLOB, batch_id VARCHAR(36) NOT NULL REFERENCES import_batch(id))");
        }
    }
    private Connection connect() throws SQLException { return DriverManager.getConnection(url,"sa",""); }
    public Set<String> existingCodes() {
        try(var c=connect()) { return existingCodes(c); }
        catch(SQLException e) { throw new IllegalStateException("数据库暂时不可用",e); }
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
            String hash=fileHash(file);
            Receipt previous;
            try(var connection=connect()) { previous=findFile(connection,hash); }
            catch(SQLException e) { throw new IllegalStateException("无法检查文件导入记录，请稍后重试。",e); }
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
                c.setAutoCommit(false);
                try {
                    // Serialize the recheck and writes across requests/connections, including different sessions.
                    try(var lock=c.prepareStatement("SELECT id FROM import_lock WHERE id=1 FOR UPDATE");var ignored=lock.executeQuery()){ignored.next();}
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
                    try(var b=c.prepareStatement("INSERT INTO import_batch(id,row_count,file_sha256) VALUES(?,?,?)")) {b.setString(1,token);b.setInt(2,pending.rows().size());b.setString(3,pending.fileHash());b.executeUpdate();}
                    try(var s=c.prepareStatement("INSERT INTO supplier(credit_code,supplier_name,contact,phone,amount,cooperation_date,note,batch_id) VALUES(?,?,?,?,?,?,?,?)")) {
                        for(var row:pending.rows()) {
                            s.setString(1,row.code());s.setString(2,row.name());s.setString(3,row.contact());s.setString(4,row.phone());
                            s.setString(5,row.amount().toPlainString());s.setObject(6,row.date());s.setString(7,row.note());s.setString(8,token);s.addBatch();
                        }
                        s.executeBatch();
                    }
                    c.commit();return new Receipt(token,pending.rows().size(),false);
                } catch(Exception e) {c.rollback();throw e;}
            } catch(Conflict e) {throw e;}
            catch(SQLException e) {throw new IllegalStateException("入库失败，本批次已回滚，请重试确认。",e);}
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
                    BigDecimal amount=money.getCellType()==CellType.NUMERIC?BigDecimal.valueOf(money.getNumericCellValue()):new BigDecimal(values.get("结算金额"));
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
