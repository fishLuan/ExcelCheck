package com.td.xp;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.util.*;

@RestController
public class PreviewController {
    private static class PreviewException extends IllegalArgumentException { PreviewException(String message) { super(message); } }
    public record Preview(String fileName, List<SheetData> sheets, SettlementValidator.Result validation) {}
    public record SheetData(String name, int columnCount, List<RowData> rows,
                            List<RowData> validRows, List<RowData> invalidRows,
                            List<SettlementValidator.Issue> headerIssues) {}
    public record RowData(int rowNumber, List<String> cells, List<SettlementValidator.Issue> issues) {}

    private ImportService imports;
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
    Preview parse(MultipartFile file, Set<String> existingCodes, boolean databaseChecked) {
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw new PreviewException("仅支持 .xlsx 文件，请在 Excel 中另存为 .xlsx 后上传。");
        if (file.getSize() > 10L * 1024 * 1024) throw new PreviewException("文件超过 10 MB，请精简文件后重新上传。");
        if (file.isEmpty()) throw new PreviewException("文件为空，请选择包含数据的 Excel 文件。");
        try (var input = file.getInputStream(); var workbook = new XSSFWorkbook(input)) {
            var formatter = new DataFormatter(Locale.CHINA);
            formatter.setUseCachedValuesForFormulaCells(true);
            var sheets = new ArrayList<SheetData>();
            int totalRows = 0;
            for (Sheet sheet : workbook) {
                int index = workbook.getSheetIndex(sheet);
                if (workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)) continue;
                var rows = new ArrayList<RowData>();
                int columns = 0;
                for (Row row : sheet) {
                    if (row.getLastCellNum() > 200) throw new PreviewException("工作表“" + sheet.getSheetName() + "”超过 200 列，请精简后上传。");
                    var cells = new ArrayList<String>();
                    boolean hasValue = false;
                    for (int i = 0; i < row.getLastCellNum(); i++) {
                        Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        String value = cell == null ? "" : formatter.formatCellValue(cell);
                        cells.add(value);
                        hasValue |= !SettlementValidator.clean(value).isEmpty();
                    }
                    if (!hasValue) continue;
                    if (++totalRows > 10000) throw new PreviewException("文件有效行数超过 10000 行，请拆分后上传。");
                    columns = Math.max(columns, cells.size());
                    rows.add(new RowData(row.getRowNum() + 1, cells, List.of()));
                }
                sheets.add(new SheetData(sheet.getSheetName(), columns, rows, List.of(), List.of(), List.of()));
            }
            if (totalRows == 0) throw new PreviewException("文件没有可预览的数据，请检查可见工作表是否为空。");
            var validation = SettlementValidator.validate(workbook, existingCodes, databaseChecked);
            var grouped = new ArrayList<SheetData>();
            for (var sheet : sheets) {
                int header = sheet.rows().isEmpty() ? 0 : sheet.rows().getFirst().rowNumber();
                var headerIssues = validation.issues().stream()
                    .filter(e -> e.sheet().equals(sheet.name()) && e.rowNumber() == header).toList();
                var schemaIssues = headerIssues.stream().filter(e -> !e.field().equals("数据")).toList();
                var byRow = new HashMap<Integer, List<SettlementValidator.Issue>>();
                validation.issues().stream().filter(e -> e.sheet().equals(sheet.name()) && e.rowNumber() != header)
                    .forEach(e -> byRow.computeIfAbsent(e.rowNumber(), key -> new ArrayList<>()).add(e));
                var valid = new ArrayList<RowData>();
                var invalid = new ArrayList<RowData>();
                for (var row : sheet.rows()) {
                    if (row.rowNumber() == header) continue;
                    var errors = new ArrayList<>(byRow.getOrDefault(row.rowNumber(), List.of()));
                    for (var issue : schemaIssues) errors.add(new SettlementValidator.Issue(sheet.name(), row.rowNumber(),
                        0, issue.field(), "表头第" + header + "行有误：" + issue.message()));
                    var result = new RowData(row.rowNumber(), row.cells(), errors);
                    (errors.isEmpty() ? valid : invalid).add(result);
                }
                grouped.add(new SheetData(sheet.name(), sheet.columnCount(), sheet.rows(), valid, invalid, headerIssues));
            }
            return new Preview(name, grouped, validation);
        } catch (PreviewException e) { throw e; }
        catch (Exception e) {
            throw new PreviewException("无法解析此文件：文件可能损坏、受密码保护，或并非真正的 .xlsx 文件。请用 Excel 打开并另存为 .xlsx 后重试。");
        }
    }

    @PostMapping("/api/errors/word")
    public ResponseEntity<byte[]> exportErrors(@RequestParam("file") MultipartFile file) throws java.io.IOException {
        var result = imports == null ? preview(file) : parse(file, imports.existingCodes(), true);
        if (result.validation().issues().isEmpty()) throw new PreviewException("当前文件没有校验错误，无需导出错误报告。");
        return ResponseEntity.ok()
            .header("Content-Disposition", org.springframework.http.ContentDisposition.attachment()
                .filename("供应商结算校验错误报告.docx", java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .body(ErrorReport.create(result));
    }

    @RestControllerAdvice
    public static class Errors {
        @ExceptionHandler(ImportService.Conflict.class)
        public ResponseEntity<Map<String, Object>> conflict(ImportService.Conflict e) {
            var body = new HashMap<String, Object>(); body.put("message", e.getMessage());
            if (e.preview != null) body.put("preview", e.preview);
            return ResponseEntity.status(409).body(body);
        }
        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
        @ExceptionHandler(MaxUploadSizeExceededException.class)
        public ResponseEntity<Map<String, String>> tooLarge() {
            return ResponseEntity.status(413).body(Map.of("message", "文件超过 10 MB，请精简文件后重新上传。"));
        }
        @ExceptionHandler(Exception.class)
        public ResponseEntity<Map<String, String>> unexpected(Exception e) {
            return ResponseEntity.status(500).body(Map.of("message", "操作暂时失败，未完成的数据会回滚；确认结果不明时可重试确认，系统会防止重复入库。若持续失败请联系管理员。"));
        }
    }
}


