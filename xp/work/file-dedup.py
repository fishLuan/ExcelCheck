from pathlib import Path
p=Path('src/main/java/com/td/xp/ImportService.java');s=p.read_text(encoding='utf-8-sig')
s=s.replace('String token, boolean canConfirm, String expiresAt) {}','String token, boolean canConfirm, String expiresAt, String existingFileBatchId) {}')
s=s.replace('PreviewController.Preview preview, List<Supplier> rows) {}','PreviewController.Preview preview, List<Supplier> rows, String fileHash) {}')
s=s.replace('s.execute("CREATE TABLE IF NOT EXISTS supplier', '''s.execute("ALTER TABLE import_batch ADD COLUMN IF NOT EXISTS file_sha256 VARCHAR(64)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_import_batch_file_sha256 ON import_batch(file_sha256)");
            s.execute("CREATE TABLE IF NOT EXISTS supplier''')
s=s.replace('boolean ready=preview.validation().issues().isEmpty() && preview.validation().totalRows()>0;', '''String hash=fileHash(file);
            Receipt previous;
            try(var connection=connect()) { previous=findFile(connection,hash); }
            catch(SQLException e) { throw new IllegalStateException("无法检查文件导入记录，请稍后重试。",e); }
            if(previous!=null) {
                var old=preview.validation();var notices=new ArrayList<>(old.notices());
                notices.add("此文件已成功导入，原批次："+previous.batchId()+"，共"+previous.importedRows()+"条；不会再次写入。修改文件名也不会重复导入。");
                preview=new PreviewController.Preview(preview.fileName(),preview.sheets(),new SettlementValidator.Result(old.totalRows(),old.validRows(),old.invalidRows(),old.issues(),old.databaseChecked(),notices));
            }
            boolean ready=previous==null && preview.validation().issues().isEmpty() && preview.validation().totalRows()>0;''')
s=s.replace('new Pending(token,expires,preview,rows)', 'new Pending(token,expires,preview,rows,hash)')
s=s.replace('token,ready,expires.toString());','token,ready,expires.toString(),previous==null?null:previous.batchId());')
s=s.replace('if(clock.instant().isAfter(pending.expires()))', '''Receipt sameFile=findFile(c,pending.fileHash());
                    if(sameFile!=null) { c.commit();return sameFile; }
                    if(clock.instant().isAfter(pending.expires()))''')
s=s.replace('INSERT INTO import_batch(id,row_count) VALUES(?,?)','INSERT INTO import_batch(id,row_count,file_sha256) VALUES(?,?,?)').replace('b.setInt(2,pending.rows().size());b.executeUpdate();','b.setInt(2,pending.rows().size());b.setString(3,pending.fileHash());b.executeUpdate();')
anchor='    private PreviewController.Preview withDatabaseDuplicates'
s=s.replace(anchor,'''    private static String fileHash(MultipartFile file) throws IOException {
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
''' + anchor)
p.write_text(s,encoding='utf-8')
p=Path('src/main/resources/static/index.html');s=p.read_text(encoding='utf-8-sig').replace("receipt?'导入成功':data.canConfirm?", "receipt?(receipt.alreadyImported?'文件已导入，本次未重复写入':'导入成功'):data.existingFileBatchId?'文件已导入，本次不会重复写入':data.canConfirm?")
s=s.replace("'本次为重复确认，未重复写入。'", "'该批次或同一文件已成功导入，本次未重复写入。'")
s=s.replace("$('status').textContent='导入成功，共 '+result.importedRows+' 条。';", "$('status').textContent=result.alreadyImported?'该文件已导入，本次未重复写入。':'导入成功，共 '+result.importedRows+' 条。';")
p.write_text(s,encoding='utf-8')
p=Path('src/test/java/com/td/xp/ImportServiceTests.java');s=p.read_text(encoding='utf-8-sig');pos=s.rfind('}')
s=s[:pos]+'''    @Test void identicalFileRenamedOrReuploadedFromAnotherSessionCannotDuplicate() throws Exception {
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
        String disk="jdbc:h2:file:"+directory.resolve("fingerprint").toAbsolutePath().toString().replace('\\\\','/');
        var original=file("91310000AB00000001");var first=new ImportService(disk);var session=new MockHttpSession();
        var preview=first.prepare(original,session);var receipt=first.confirm(preview.token(),session);
        var restarted=new ImportService(disk);var after=restarted.prepare(original,new MockHttpSession());
        assertFalse(after.canConfirm());assertEquals(receipt.batchId(),after.existingFileBatchId());
    }
''' + s[pos:];p.write_text(s,encoding='utf-8')
p=Path('README.md');s=p.read_text(encoding='utf-8-sig');s+='''
## 同一文件重复导入

文件使用 SHA-256 内容指纹识别，文件名不参与判重。上传已成功导入的同一文件时禁用确认并提示原批次；改名、新会话、重启均不绕过去重。两个会话预览同一文件后同时确认，只允许一个批次写入，另一请求返回原批次结果并标明 alreadyImported=true。文件指纹具有数据库唯一索引，并与供应商、批次在同一事务提交，失败回滚不占用文件指纹。

修改内容或重新保存造成文件字节变化时，仍通过统一社会信用代码主键与确认前数据库查重防止供应商重复。旧版本的批次记录可无文件指纹，已有供应商依然受信用代码唯一约束保护。
''';p.write_text(s,encoding='utf-8')
