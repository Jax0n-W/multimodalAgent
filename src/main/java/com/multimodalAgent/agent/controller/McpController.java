package com.multimodalAgent.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.multimodalAgent.agent.service.mcp.multimodalAgentMcpServer;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
/**
 * MCP JSON-RPC 服务端入口。
 */
public class McpController {

    private final multimodalAgentMcpServer mcpServer;

    public McpController(multimodalAgentMcpServer mcpServer) {
        this.mcpServer = mcpServer;
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> handle(@RequestBody JsonNode request) {
        return mcpServer.handle(request);
    }
}
