package com.multimodalAgent.agent.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * MCP JSON-RPC 客户端。
 */
public class McpProtocolClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final AtomicLong ids = new AtomicLong(1);
    private volatile boolean initialized;

    public McpProtocolClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper, String endpoint) {
        this.webClient = webClientBuilder.baseUrl(endpoint).build();
        this.objectMapper = objectMapper;
    }

    public JsonNode callTool(String name, Map<String, Object> arguments) {
        ensureInitialized();
        request("tools/list", Map.of());
        return request("tools/call", Map.of("name", name, "arguments", arguments));
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (!initialized) {
                request("initialize", Map.of(
                        "protocolVersion", "2024-11-05",
                        "clientInfo", Map.of("name", "multimodalAgent-agent", "version", "0.1.0"),
                        "capabilities", Map.of()));
                initialized = true;
            }
        }
    }

    private JsonNode request(String method, Map<String, Object> params) {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", ids.getAndIncrement(),
                "method", method,
                "params", params);
        JsonNode response = webClient.post()
                .uri("")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        if (response == null) {
            throw new IllegalStateException("MCP server returned empty response");
        }
        if (response.hasNonNull("error")) {
            throw new IllegalStateException("MCP error: " + response.path("error"));
        }
        return response.path("result");
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }
}
