from pathlib import Path
p=Path('src/main/java/com/td/xp/ImportService.java');s=p.read_text(encoding='utf-8-sig').replace('private final String url;', 'private final String url;\n    private final Clock clock;')
s=s.replace('    public ImportService(@Value', '    @org.springframework.beans.factory.annotation.Autowired\n    public ImportService(@Value')
s=s.replace('String url) throws SQLException {\n        this.url=url;', 'String url) throws SQLException { this(url, Clock.systemUTC()); }\n    ImportService(String url, Clock clock) throws SQLException {\n        this.url=url; this.clock=clock;')
s=s.replace('Instant.now()', 'clock.instant()');p.write_text(s,encoding='utf-8')
p=Path('src/test/java/com/td/xp/XpApplicationTests.java');s=p.read_text(encoding='utf-8-sig').replace('@SpringBootTest','@SpringBootTest(properties = "settlement.database-url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1")');p.write_text(s,encoding='utf-8')
p=Path('src/test/java/com/td/xp/ImportServiceTests.java');s=p.read_text(encoding='utf-8-sig');s=s.replace('import java.time.Instant;', 'import java.time.*;\nimport java.nio.file.Path;\nimport org.junit.jupiter.api.io.TempDir;')
s=s.replace('mvc.perform(multipart("/api/preview").file(file("91310000AB00000001"))).andExpect(status().isOk());assertEquals(0,count());','''var session=new MockHttpSession();
        var response=mvc.perform(multipart("/api/preview").file(file("91310000AB00000001")).session(session)).andExpect(status().isOk()).andReturn().getResponse();assertEquals(0,count());
        var match=java.util.regex.Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"").matcher(response.getContentAsString());assertTrue(match.find());String token=match.group(1);
        mvc.perform(post("/api/imports/confirm").session(session).contentType("application/json").content("{\\\"token\\\":\\\""+token+"\\\"}")).andExpect(status().isOk());assertEquals(1,count());
        mvc.perform(post("/api/imports/confirm").session(session).contentType("application/json").content("{\\\"token\\\":\\\""+token+"\\\"}")).andExpect(status().isOk());assertEquals(1,count());''')
pos=s.rfind('}')
s=s[:pos]+'''    @Test void expiredPreviewCannotWrite() throws Exception {
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
        String disk="jdbc:h2:file:"+directory.resolve("settlement").toAbsolutePath().toString().replace('\\\\','/');
        var first=new ImportService(disk);var session=new MockHttpSession();var upload=first.prepare(file("91310000AB00000001"),session);
        first.confirm(upload.token(),session);
        var restarted=new ImportService(disk);var preview=restarted.prepare(file("91310000AB00000001"),new MockHttpSession());
        assertFalse(preview.canConfirm());assertTrue(preview.validation().issues().getFirst().message().contains("数据库"));
    }
''' + s[pos:];p.write_text(s,encoding='utf-8')
p=Path('README.md');s=p.read_text(encoding='utf-8-sig').replace('（第二阶段）','（第三阶段）')
s=s.replace('- 仅解析校验，不写入数据库。数据库已有供应商查重尚未接入，不能据字段校验通过确认入库。','''- 上传只预览校验，不写入供应商表；全部校验通过后点击“确认入库”，在确认框核对文件名和总条数后点击“确认写入数据库”。任一行有错误时禁止整批入库，不进行部分导入。
- 已接入 H2 本地文件数据库，默认路径为启动目录下 `data/settlement.mv.db`（在项目目录启动即为 `D:\\Downloads\\xp\\xp\\data\\settlement.mv.db`），重启后保留已导入供应商和批次记录。可通过 `settlement.database-url` 配置 H2 URL。
- 数据库信用代码为主键；预览和确认都执行数据库查重。确认使用数据库锁串行查重写入，并在同一事务提交供应商与批次，任意插入失败整批回滚。
- 确认仅接收批次 token，使用服务器保存的已校验数据，不接收浏览器回传的供应商字段。待确认批次绑定当前浏览器会话，有效期30分钟，同一会话新上传替换旧预览；服务器重启后未确认的预览需重新上传。
- 同一批次重复确认返回原成功结果，不重复写入。网络中断后应重试当前批次确认；成功后页面显示导入条数和批次编号。
- 金额使用 BigDecimal 读取原始数字，并以精确十进制字符串存于 amount CLOB，避免未约定小数位时强制舍入；日期使用数据库 DATE。后续如需SQL金额汇总，可在业务明确精度后迁移为定点数列。
- 当前没有账号登录，批次归属按浏览器会话隔离。''')
s=s.replace('以及校验汇总。','以及校验汇总、token、canConfirm、expiresAt。')
s=s.replace('- `POST /api/errors/word`', '- `POST /api/imports/confirm`，JSON `{ "token": "预览返回的token" }`：确认整批入库，返回 batchId、importedRows、alreadyImported；需携带上传时同一会话 Cookie。冲突返回409，并在供应商已被其他批次导入时返回更新的错误预览。\n- `POST /api/errors/word`')
s=s.replace('服务器重新解析校验原文件','服务器重新解析并执行字段、文件内和数据库查重后校验原文件');p.write_text(s,encoding='utf-8')
