package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.domain.PsychologicalReport;

/**
 * 通过 MCP tools/call 写入 Excel。
 */
public class McpExcelReportWriter implements ExcelReportWriter {

    private final McpProtocolClient client;

    public McpExcelReportWriter(McpProtocolClient client) {
        this.client = client;
    }

    @Override
    public void write(PsychologicalReport report) {
        client.callTool("multimodalAgent.excel.write_report", ReportPayloads.from(report));
    }
}
