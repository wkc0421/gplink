package org.jetlinks.community.saas.propertycatalog.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.web.exception.ValidationException;
import org.jetlinks.community.saas.propertycatalog.dto.CatalogRequests.*;
import org.springframework.stereotype.Component;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class PropertyCatalogExcel {
    public Preview parse(byte[] content, String filename) {
        if (filename == null || !filename.toLowerCase(Locale.ROOT).matches(".*\\.(xlsx?|csv)$"))
            throw new ValidationException("请上传 .xlsx、.xls 或 UTF-8 .csv 文件");
        try (Workbook workbook = filename.toLowerCase(Locale.ROOT).endsWith(".csv") ? csvWorkbook(content) : WorkbookFactory.create(new ByteArrayInputStream(content))) {
            DataFormatter format = new DataFormatter(Locale.ROOT);
            for (Sheet sheet : workbook) {
                for (int n = 0; n < Math.min(10, sheet.getLastRowNum() + 1); n++) {
                    Row row = sheet.getRow(n); if (row == null) continue;
                    Map<String, Integer> headers = new HashMap<>();
                    for (Cell cell : row) headers.put(format.formatCellValue(cell).trim(), cell.getColumnIndex());
                    boolean original = headers.containsKey("FCode") && headers.containsKey("FName");
                    boolean template = headers.containsKey("采集项ID") && headers.containsKey("采集项名称");
                    if (original || template) return parseSheet(sheet, n, headers, original, format, filename);
                }
            }
            throw new ValidationException("未找到采集项表头，请使用 FCode/FName 或 分类路径、采集项名称、采集项ID、备注 模板");
        } catch (ValidationException error) {
            throw error;
        } catch (Exception error) {
            throw new ValidationException("Excel 无法解析，请检查文件格式及内容");
        }
    }

    private Workbook csvWorkbook(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.indexOf('\uFFFD') >= 0) throw new ValidationException("CSV 请使用 UTF-8 编码保存");
        Workbook workbook = new XSSFWorkbook(); Sheet sheet = workbook.createSheet("采集项");
        List<String> fields = new ArrayList<>(); StringBuilder field = new StringBuilder();
        boolean quoted = false, closedQuote = false; int row = 0;
        try {
            for (int i = 0; i <= text.length(); i++) {
                char ch = i == text.length() ? '\n' : text.charAt(i);
                if (quoted) {
                    if (i == text.length()) throw new ValidationException("CSV 引号未闭合");
                    if (ch == '"') {
                        if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                        else { quoted = false; closedQuote = true; }
                    } else field.append(ch);
                } else if (ch == '"' && field.length() == 0 && !closedQuote) quoted = true;
                else if (ch == ',' || ch == '\n' || ch == '\r') {
                    fields.add(field.toString()); field.setLength(0); closedQuote = false;
                    if (fields.size() > 100) throw new ValidationException("CSV 列数过多");
                    if (ch != ',') {
                        if (row > PropertyCatalogService.MAX_IMPORT_ROWS + 2) throw new ValidationException("每次最多导入 10000 条采集项");
                        Row target = sheet.createRow(row++);
                        for (int col = 0; col < fields.size(); col++) target.createCell(col).setCellValue(fields.get(col));
                        fields.clear(); if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    }
                } else {
                    if (closedQuote || ch == '"') throw new ValidationException("CSV 引号格式无效");
                    field.append(ch);
                }
            }
            return workbook;
        } catch (RuntimeException error) {
            try { workbook.close(); } catch (Exception ignored) { }
            throw error;
        }
    }

    private Preview parseSheet(Sheet sheet, int headerRow, Map<String, Integer> headers, boolean original, DataFormatter format, String filename) {
        Preview preview = new Preview();
        int codeColumn = headers.get(original ? "FCode" : "采集项ID");
        int nameColumn = headers.get(original ? "FName" : "采集项名称");
        for (int n = headerRow + 1; n <= sheet.getLastRowNum(); n++) {
            Row row = sheet.getRow(n); if (row == null) continue;
            String code = cell(row, codeColumn, format), name = cell(row, nameColumn, format);
            if (original && "类型编码".equals(code) && "类型名称".equals(name)) continue;
            String category = cell(row, headers.get("分类路径"), format), remark = cell(row, headers.get("备注"), format);
            if (original && remark.isEmpty()) {
                Set<String> notes = new LinkedHashSet<>();
                for (int col = codeColumn + 1; col < row.getLastCellNum(); col++) {
                    String note = cell(row, col, format); if (!note.isEmpty()) notes.add(note);
                }
                remark = String.join("；", notes);
            }
            if (code.isEmpty() && name.isEmpty() && category.isEmpty() && remark.isEmpty()) continue;
            if ("None".equalsIgnoreCase(code) && "None".equalsIgnoreCase(name)) { preview.getWarnings().add("第 " + (n + 1) + " 行 None 占位项已忽略"); continue; }
            if (original && headers.containsKey("Status")) {
                String status = cell(row, headers.get("Status"), format);
                if (!"1".equals(status)) { preview.getWarnings().add("第 " + (n + 1) + " 行状态不是 1，已忽略"); continue; }
            }
            if (preview.getRows().size() >= PropertyCatalogService.MAX_IMPORT_ROWS) throw new ValidationException("每次最多导入 10000 条采集项");
            ImportRow value = new ImportRow(); value.setRowNumber(n + 1); value.setCode(code); value.setName(name);
            value.setCategoryPath(category); value.setRemark(remark);
            Map<String, Object> source = new LinkedHashMap<>(); source.put("file", filename); source.put("sheet", sheet.getSheetName()); source.put("row", n + 1);
            if (original) source.put("fid", cell(row, headers.get("Fid"), format));
            value.setSource(source); value.setErrors(PropertyCatalogService.rowErrors(value)); preview.getRows().add(value);
        }
        if (preview.getRows().isEmpty()) throw new ValidationException("文件中没有有效采集项");
        return preview;
    }

    private static String cell(Row row, Integer index, DataFormatter format) {
        if (index == null) return "";
        Cell cell = row.getCell(index); if (cell == null) return "";
        // Do not evaluate uploaded formulas or turn formulas into property identifiers.
        if (cell.getCellType() == CellType.FORMULA) throw new ValidationException("导入字段不能使用公式（第 " + (row.getRowNum() + 1) + " 行）");
        return format.formatCellValue(cell).trim();
    }
}
