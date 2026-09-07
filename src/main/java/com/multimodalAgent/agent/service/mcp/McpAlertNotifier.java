package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通过 MCP tools/call 发送高风险预警。
 */
public class McpAlertNotifier implements AlertNotifier {

    private final McpProtocolClient client;

    public McpAlertNotifier(McpProtocolClient client) {
        this.client = client;
    }

    @Override
    public void notify(AlertRecord alertRecord, PsychologicalReport report) {
        Map<String, Object> payload = new LinkedHashMap<>(ReportPayloads.from(report));
        payload.put("recipient", alertRecord.getRecipient());
        payload.put("alertId", alertRecord.getId());
        client.callTool("multimodalAgent.email.send_alert", payload);
    }
}
