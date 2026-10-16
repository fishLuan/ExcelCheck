from pathlib import Path
p=Path('pom.xml');s=p.read_text(encoding='utf-8-sig').replace('</dependencies>','<dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId></dependency>\n\t</dependencies>');p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/td/xp/SettlementValidator.java');s=p.read_text(encoding='utf-8-sig').replace('var issues = new ArrayList<Issue>();','return validate(book, Set.of(), false);\n    }\n    public static Result validate(Workbook book, Set<String> existingCodes, boolean databaseChecked) {\n        var issues = new ArrayList<Issue>();',1)
s=s.replace('if (field.equals("统一社会信用代码") && error == null)\n                        codes.computeIfAbsent(value, key -> new ArrayList<>()).add(new Entry(sheet.getSheetName(), row.getRowNum()+1, col+1));','''if (field.equals("统一社会信用代码") && error == null) {
                        codes.computeIfAbsent(value, key -> new ArrayList<>()).add(new Entry(sheet.getSheetName(), row.getRowNum()+1, col+1));
                        if (existingCodes.contains(value)) issues.add(new Issue(sheet.getSheetName(), row.getRowNum()+1, col+1, field, "该供应商已在数据库中，不允许重复入库"));
                    }''')
s=s.replace('issues, false, List.of(', 'issues, databaseChecked, List.of(').replace('"尚未连接供应商数据库：仅完成文件内查重，未校验数据库已有供应商；当前不能确认入库。",','databaseChecked ? "已完成数据库查重；确认入库时会再次检查，全部通过才整批写入。" : "尚未连接供应商数据库：仅完成文件内查重，未校验数据库已有供应商；当前不能确认入库。",')
p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/td/xp/PreviewController.java');s=p.read_text(encoding='utf-8-sig');s=s.replace('    @PostMapping("/api/preview")\n    public Preview preview(@RequestParam("file") MultipartFile file) {','''    private ImportService imports;
    public PreviewController() {}
    @org.springframework.beans.factory.annotation.Autowired
    public PreviewController(ImportService imports) { this.imports = imports; }

    @PostMapping("/api/preview")
    public ImportService.UploadResult upload(@RequestParam("file") MultipartFile file, jakarta.servlet.http.HttpSession session) throws java.io.IOException {
        return imports.prepare(file, session);
    }
    @PostMapping("/api/imports/confirm")
    public ImportService.Receipt confirm(@RequestBody ConfirmRequest request, jakarta.servlet.http.HttpSession session) {
        if (request.token() == null || request.token().isBlank()) throw new IllegalArgumentException("缺少待确认批次，请先上传预览。");
        return imports.confirm(request.token(), session);
    }
    public record ConfirmRequest(String token) {}
    public Preview preview(MultipartFile file) { return parse(file, Set.of(), false); }
    Preview parse(MultipartFile file, Set<String> existingCodes, boolean databaseChecked) {''')
s=s.replace('SettlementValidator.validate(workbook)', 'SettlementValidator.validate(workbook, existingCodes, databaseChecked)')
s=s.replace('var result = preview(file);','var result = imports == null ? preview(file) : parse(file, imports.existingCodes(), true);')
s=s.replace('    @RestControllerAdvice','''    @RestControllerAdvice''')
s=s.replace('        @ExceptionHandler(IllegalArgumentException.class)','''        @ExceptionHandler(ImportService.Conflict.class)
        public ResponseEntity<Map<String, Object>> conflict(ImportService.Conflict e) {
            var body = new HashMap<String, Object>(); body.put("message", e.getMessage());
            if (e.preview != null) body.put("preview", e.preview);
            return ResponseEntity.status(409).body(body);
        }
        @ExceptionHandler(IllegalArgumentException.class)''')
s=s.replace('解析暂时失败，请重新上传；若仍然失败，请联系管理员检查服务。','操作暂时失败，未完成的数据会回滚；确认结果不明时可重试确认，系统会防止重复入库。若持续失败请联系管理员。')
p.write_text(s,encoding='utf-8')
