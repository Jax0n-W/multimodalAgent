package com.multimodalAgent.agent.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
/**
 * multimodalAgent MCP JSON-RPC 工具服务端。
 */
public class multimodalAgentMcpServer {

    private static final Logger log = LoggerFactory.getLogger(multimodalAgentMcpServer.class);

    private final ObjectMapper objectMapper;
    private final LocalExcelReportWriter localExcelReportWriter;

    public multimodalAgentMcpServer(ObjectMapper objectMapper, multimodalAgentProperties properties) {
        this.objectMapper = objectMapper;
        this.localExcelReportWriter = new LocalExcelReportWriter(properties);
    }

    public Map<String, Object> handle(JsonNode request) {
        Object id = jsonId(request.path("id"));
        try {
            String method = request.path("method").asText("");
            Object result = switch (method) {
                case "initialize" -> initialize();
                case "tools/list" -> toolsList();
                case "tools/call" -> toolsCall(request.path("params"));
                default -> throw new McpException(-32601, "Method not found: " + method);
            };
            return response(id, result);
        } catch (McpException exception) {
            return error(id, exception.code(), exception.getMessage());
        } catch (Exception exception) {
            return error(id, -32603, exception.getMessage());
        }
    }

    private Map<String, Object> initialize() {
        return Map.of(
                "protocolVersion", "2024-11-05",
                "serverInfo", Map.of("name", "multimodalAgent-mcp-server", "version", "0.1.0"),
                "capabilities", Map.of("tools", Map.of()));
    }

    private Map<String, Object> toolsList() {
        return Map.of("tools", List.of(
                Map.of(
                        "name", "multimodalAgent.excel.write_report",
                        "description", "Write a psychological report row to the multimodalAgent Excel ledger.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "required", List.of("reportId", "username", "riskLevel", "summary", "content"),
                                "properties", Map.of(
                                        "reportId", Map.of("type", "number"),
                                        "username", Map.of("type", "string"),
                                        "riskLevel", Map.of("type", "string"),
                                        "summary", Map.of("type", "string"),
                                        "content", Map.of("type", "string")))),
                Map.of(
                        "name", "multimodalAgent.email.send_alert",
                        "description", "Send or record a high-risk counselor alert.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "required", List.of("recipient", "reportId", "username", "riskLevel", "summary"),
                                "properties", Map.of(
                                        "recipient", Map.of("type", "string"),
                                        "reportId", Map.of("type", "number"),
                                        "username", Map.of("type", "string"),
                                        "riskLevel", Map.of("type", "string"),
                                        "summary", Map.of("type", "string"))))
        ));
    }

    private Map<String, Object> toolsCall(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");
        return switch (name) {
            case "multimodalAgent.excel.write_report" -> writeExcel(arguments);
            case "multimodalAgent.email.send_alert" -> sendAlert(arguments);
            default -> throw new McpException(-32602, "Unknown tool: " + name);
        };
    }

    private Map<String, Object> writeExcel(JsonNode arguments) {
        Map<String, Object> payload = objectMapper.convertValue(arguments, new TypeReference<>() {
        });
        localExcelReportWriter.writePayload(payload);
        return toolText("Excel report written through MCP protocol.");
    }

    private Map<String, Object> sendAlert(JsonNode arguments) {
        log.warn(
                "MCP high-risk alert: recipient={}, reportId={}, user={}, risk={}, summary={}",
                ReportPayloads.text(arguments, "recipient"),
                ReportPayloads.number(arguments, "reportId"),
                ReportPayloads.text(arguments, "username"),
                ReportPayloads.text(arguments, "riskLevel"),
                ReportPayloads.text(arguments, "summary"));
        return toolText("High-risk alert recorded through MCP protocol.");
    }

    private Map<String, Object> toolText(String text) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)));
    }

    private Map<String, Object> response(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message == null ? "" : message));
        return response;
    }

    private Object jsonId(JsonNode idNode) {
        if (idNode == null || idNode.isMissingNode() || idNode.isNull()) {
            return null;
        }
        if (idNode.isNumber()) {
            return idNode.asLong();
        }
        return idNode.asText();
    }

    private static class McpException extends RuntimeException {
        private final int code;

        McpException(int code, String message) {
            super(message);
            this.code = code;
        }

        int code() {
            return code;
        }
    }
}
