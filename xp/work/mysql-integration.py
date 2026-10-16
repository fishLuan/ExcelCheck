from pathlib import Path
p=Path('pom.xml');s=p.read_text(encoding='utf-8-sig').replace('<dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId></dependency>','''<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
    <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>test</scope></dependency>''');p.write_text(s,encoding='utf-8')
p=Path('src/main/resources/application.properties');s=p.read_text(encoding='utf-8-sig');s='\n'.join(l for l in s.splitlines() if not l.startswith('settlement.database-url='));s+='''
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.connection-timeout=10000
spring.sql.init.mode=never
''';p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/td/xp/SettlementValidator.java');s=p.read_text(encoding='utf-8-sig')
s=s.replace('else if (field.equals("手机号")) {','''else if (databaseChecked && field.equals("供应商名称") && value.codePointCount(0,value.length()) > 255) error = "供应商名称超过数据库允许的255个字符";
                    else if (databaseChecked && field.equals("联系人") && value.codePointCount(0,value.length()) > 100) error = "联系人超过数据库允许的100个字符";
                    else if (field.equals("手机号")) {''')
s=s.replace('if (new BigDecimal(number).signum() < 0) error = "不能为负数";', '''var amount = new BigDecimal(number);
                            if (amount.signum() < 0) error = "不能为负数";
                            else if (databaseChecked) {
                                if (amount.stripTrailingZeros().scale() > 2) error = "数据库金额列只支持最多2位有效小数，请修正后上传；系统不会自动四舍五入";
                                else if (amount.compareTo(new BigDecimal("999999999999999999.99")) > 0) error = "金额超过数据库 DECIMAL(20,2) 上限999999999999999999.99";
                            }''')
s=s.replace('!validDate(cell, value, book)', '!validDate(cell, value, book, databaseChecked ? 1000 : 1)')
s=s.replace('error = "必须是真实存在的日期；请使用 Excel 日期或 yyyy-MM-dd、yyyy/M/d、yyyy年M月d日";', 'error = databaseChecked ? "必须是真实存在的1000至9999年日期；支持 Excel 日期、yyyy-MM-dd、yyyy/M/d、yyyy年M月d日" : "必须是真实存在的日期；请使用 Excel 日期或 yyyy-MM-dd、yyyy/M/d、yyyy年M月d日";')
s=s.replace('            }\n            if (!hasData)', '''                if (databaseChecked && columns.containsKey("备注")) {
                    int col=columns.get("备注");String note=clean(formatter.formatCellValue(row.getCell(col)));
                    if (note.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65535)
                        issues.add(new Issue(sheet.getSheetName(),row.getRowNum()+1,col+1,"备注","备注超过数据库 TEXT 字段65535字节限制"));
                }
            }
            if (!hasData)''')
s=s.replace('"“结算金额必须合理”尚无业务阈值：当前检查有效数字及非负，不设置金额上限或小数位限制。"', 'databaseChecked ? "金额按现有数据库 DECIMAL(20,2) 校验：非负、最多2位有效小数和18位整数，不自动舍入。业务上的合理金额阈值仍待明确。" : "“结算金额必须合理”尚无业务阈值：当前检查有效数字及非负，不设置金额上限或小数位限制。"')
s=s.replace('String value, Workbook book) {','String value, Workbook book, int minYear) {').replace('year >= 1 && year <= 9999','year >= minYear && year <= 9999').replace('date.getYear() >= 1 && date.getYear() <= 9999','date.getYear() >= minYear && date.getYear() <= 9999')
p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/td/xp/ImportService.java');s=p.read_text(encoding='utf-8-sig').replace('import org.springframework.beans.factory.annotation.Value;', 'import javax.sql.DataSource;\nimport org.springframework.jdbc.datasource.DriverManagerDataSource;')
s=s.replace('private final String url;', 'private final DataSource dataSource;\n    private final boolean mysql;\n    private final String lockName;\n    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ImportService.class);')
start=s.index('    @org.springframework.beans.factory.annotation.Autowired');end=s.index('    public Set<String> existingCodes()',start)
s=s[:start]+'''    @org.springframework.beans.factory.annotation.Autowired
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
''' + s[end:]
s=s.replace('var preview=new PreviewController().parse(file,existingCodes(),true);', '''var preview=new PreviewController().parse(file,existingCodes(),true);
            if(preview.fileName().codePointCount(0,preview.fileName().length())>255) throw new IllegalArgumentException("文件名超过255个字符，请缩短文件名后重新上传。");''')
s=s.replace('c.setAutoCommit(false);\n                try {','c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);\n                c.setAutoCommit(false);\n                boolean namedLock=false;\n                try {')
s=s.replace('try(var lock=c.prepareStatement("SELECT id FROM import_lock WHERE id=1 FOR UPDATE");var ignored=lock.executeQuery()){ignored.next();}', '''if(mysql) {
                        try(var lock=c.prepareStatement("SELECT GET_LOCK(?,10)")) {
                            lock.setString(1,lockName);try(var r=lock.executeQuery()) {
                                if(!r.next() || r.getInt(1)!=1) throw new SQLException("等待导入锁超时，请重试确认。","HYT00");
                            }
                        }
                        namedLock=true;
                    } else {
                        try(var lock=c.prepareStatement("SELECT id FROM import_lock WHERE id=1 FOR UPDATE");var ignored=lock.executeQuery()){ignored.next();}
                    }''')
s=s.replace('INSERT INTO import_batch(id,row_count,file_sha256) VALUES(?,?,?)','INSERT INTO import_batch(id,row_count,file_sha256,file_name) VALUES(?,?,?,?)').replace('b.setString(3,pending.fileHash());b.executeUpdate();','b.setString(3,pending.fileHash());b.setString(4,pending.preview().fileName());b.executeUpdate();').replace('s.setString(5,row.amount().toPlainString());','s.setBigDecimal(5,row.amount());')
s=s.replace('} catch(Exception e) {c.rollback();throw e;}', '''} catch(Exception e) {
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
                }''')
s=s.replace('catch(SQLException e) {throw new IllegalStateException("入库失败，本批次已回滚，请重试确认。",e);}', '''catch(SQLException e) {
                log.error("供应商批次入库失败，batchId={}，SQLState={}",token,e.getSQLState());
                throw new IllegalStateException("入库失败，请重试当前批次确认；系统会防止重复写入。",e);
            }''')
p.write_text(s,encoding='utf-8')
p=Path('src/test/java/com/td/xp/ImportServiceTests.java');s=p.read_text(encoding='utf-8-sig').replace('1234.567890123456789','1234.56');p.write_text(s,encoding='utf-8')
p=Path('src/test/java/com/td/xp/XpApplicationTests.java');s=p.read_text(encoding='utf-8-sig').replace('@SpringBootTest(properties = "settlement.database-url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1")','''@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password="})''');s=s.replace('\t@Test','''    @org.springframework.boot.test.context.TestConfiguration
    static class TestDatabase {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        ImportService testImportService() throws Exception {return new ImportService("jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1");}
    }
\t@Test''');p.write_text(s,encoding='utf-8')
