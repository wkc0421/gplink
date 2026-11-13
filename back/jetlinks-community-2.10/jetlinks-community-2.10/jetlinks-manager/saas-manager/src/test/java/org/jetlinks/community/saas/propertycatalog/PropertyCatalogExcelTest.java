package org.jetlinks.community.saas.propertycatalog;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.community.saas.propertycatalog.service.PropertyCatalogExcel;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class PropertyCatalogExcelTest {
    final PropertyCatalogExcel parser = new PropertyCatalogExcel();
    @Test void originalWorkbookUsesFCodeAndSkipsPlaceholderSecondHeaderAndDisabledRows() throws Exception {
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("电能质量数据ID");
            String[][] data = {{"Id", "Status", "Fid", "FName", "FCode"}, {"ID", "状态", "类型ID", "类型名称", "类型编码"},
                {"1", "1", "0", "None", "None"}, {"2", "1", "1", "A相电压", " Ua ", "记录字", "记录字", "Bit0"}, {"3", "0", "2", "停用", "Ub"},
                {"4", "1", "3", "错误", "illegal-code"}};
            for (int r = 0; r < data.length; r++) { var row = sheet.createRow(r); for (int c = 0; c < data[r].length; c++) row.createCell(c).setCellValue(data[r][c]); }
            workbook.write(bytes); var result = parser.parse(bytes.toByteArray(), "original.xlsx");
            assertThat(result.getRows()).hasSize(2); assertThat(result.getRows().get(0).getCode()).isEqualTo("Ua");
            assertThat(result.getRows().get(0).getRowNumber()).isEqualTo(4); assertThat(result.getRows().get(0).getSource().get("fid")).isEqualTo("1");
            assertThat(result.getRows().get(0).getRemark()).isEqualTo("记录字；Bit0");
            assertThat(result.getRows().get(1).getErrors()).isNotEmpty(); assertThat(result.getWarnings()).hasSize(2);
        }
    }
    @Test void csvSupportsBomQuotedCommasQuotesAndNewlines() {
        String csv = "\uFEFF分类路径,采集项名称,采集项ID,备注\r\n电气/电压,A相电压,Ua,\"包含逗号,以及\"\"引号\"\"\n下一行\"\r\n";
        var result = parser.parse(csv.getBytes(StandardCharsets.UTF_8), "template.csv");
        assertThat(result.getRows()).hasSize(1); assertThat(result.getRows().get(0).getRemark()).isEqualTo("包含逗号,以及\"引号\"\n下一行");
        assertThat(result.getRows().get(0).getCategoryPath()).isEqualTo("电气/电压");
    }
    @Test void malformedCsvIsRejected() {
        assertThatThrownBy(() -> parser.parse("采集项名称,采集项ID\n\"未闭合,Ua".getBytes(StandardCharsets.UTF_8), "bad.csv")).hasMessageContaining("引号");
    }
}
