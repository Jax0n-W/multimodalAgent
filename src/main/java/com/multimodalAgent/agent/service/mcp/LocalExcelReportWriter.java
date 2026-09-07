package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 本地 Excel 文件写入实现。
 *
 * <p>适合演示环境使用，会把报告追加到 data 目录下的工作簿中。</p>
 */
public class LocalExcelReportWriter implements ExcelReportWriter {

    private final Path path;
    private final Object lock = new Object();

    public LocalExcelReportWriter(multimodalAgentProperties properties) {
        this.path = Path.of(properties.getMcp().getExcel().getLocalPath());
    }

    @Override
    public void write(PsychologicalReport report) {
        writePayload(ReportPayloads.from(report));
    }

    public void writePayload(Map<String, Object> payload) {
        synchronized (lock) {
            try {
                // 写文件需要串行化，防止多个高风险报告同时写入造成工作簿损坏。
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Workbook workbook = openWorkbook();
                Sheet sheet = workbook.getNumberOfSheets() == 0
                        ? workbook.createSheet("reports")
                        : workbook.getSheetAt(0);
                if (sheet.getPhysicalNumberOfRows() == 0) {
                    writeHeader(sheet.createRow(0));
                }
                Row row = sheet.createRow(sheet.getLastRowNum() + 1);
                writeReport(row, payload);
                for (int i = 0; i < 13; i++) {
                    sheet.autoSizeColumn(i);
                }
                try (OutputStream outputStream = Files.newOutputStream(path)) {
                    workbook.write(outputStream);
                }
                workbook.close();
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to write local Excel report", exception);
            }
        }
    }

    private Workbook openWorkbook() throws Exception {
        if (!Files.exists(path)) {
            return new XSSFWorkbook();
        }
        // 已存在时追加到原工作簿，保留历史写入记录。
        try (InputStream inputStream = Files.newInputStream(path)) {
            return WorkbookFactory.create(inputStream);
        }
    }

    private void writeHeader(Row row) {
        String[] headers = {
                "报告ID", "用户ID", "账号", "会话ID", "意图", "情绪标签", "情绪总分",
                "风险等级", "置信度", "判断摘要", "多模态标签", "对话内容", "对话时间"
        };
        for (int i = 0; i < headers.length; i++) {
            cell(row, i).setCellValue(headers[i]);
        }
    }

    private void writeReport(Row row, Map<String, Object> payload) {
        cell(row, 0).setCellValue(asLong(payload.get("reportId")));
        cell(row, 1).setCellValue(asLong(payload.get("userId")));
        cell(row, 2).setCellValue(asText(payload.get("username")));
        cell(row, 3).setCellValue(asText(payload.get("sessionId")));
        cell(row, 4).setCellValue(asText(payload.get("intent")));
        cell(row, 5).setCellValue(asText(payload.get("emotion")));
        cell(row, 6).setCellValue(asDouble(payload.get("emotionScore")));
        cell(row, 7).setCellValue(asText(payload.get("riskLevel")));
        cell(row, 8).setCellValue(asDouble(payload.get("confidence")));
        cell(row, 9).setCellValue(asText(payload.get("summary")));
        cell(row, 10).setCellValue(asText(payload.get("emotionTags")));
        cell(row, 11).setCellValue(asText(payload.get("content")));
        cell(row, 12).setCellValue(asText(payload.get("createdAt")));
    }

    private Cell cell(Row row, int index) {
        return row.createCell(index);
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(asText(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(asText(value));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }
}
