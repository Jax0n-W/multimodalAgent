package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import java.util.Map;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 通过 HTTP MCP 服务发送高风险预警。
 */
public class HttpAlertNotifier implements AlertNotifier {

    private final WebClient webClient;

    public HttpAlertNotifier(WebClient.Builder webClientBuilder, multimodalAgentProperties properties) {
        this.webClient = webClientBuilder.baseUrl(properties.getMcp().getEmail().getUrl()).build();
    }

    @Override
    public void notify(AlertRecord alertRecord, PsychologicalReport report) {
        webClient.post()
                .uri("/send")
                .bodyValue(Map.of(
                        "recipient", alertRecord.getRecipient(),
                        "reportId", report.getId(),
                        "userId", report.getUser().getId(),
                        "username", report.getUser().getUsername(),
                        "riskLevel", report.getRiskLevel().name(),
                        "summary", report.getSummary(),
                        "content", report.getContent()))
                .retrieve()
                .toBodilessEntity()
                .block();
    }
}
