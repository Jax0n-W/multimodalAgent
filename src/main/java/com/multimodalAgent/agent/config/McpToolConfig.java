package com.multimodalAgent.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.service.mcp.AlertNotifier;
import com.multimodalAgent.agent.service.mcp.ExcelReportWriter;
import com.multimodalAgent.agent.service.mcp.HttpAlertNotifier;
import com.multimodalAgent.agent.service.mcp.HttpExcelReportWriter;
import com.multimodalAgent.agent.service.mcp.LocalExcelReportWriter;
import com.multimodalAgent.agent.service.mcp.LogAlertNotifier;
import com.multimodalAgent.agent.service.mcp.McpAlertNotifier;
import com.multimodalAgent.agent.service.mcp.McpExcelReportWriter;
import com.multimodalAgent.agent.service.mcp.McpProtocolClient;
import com.multimodalAgent.agent.service.mcp.SmtpAlertNotifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
/**
 * MCP 工具链配置。
 *
 * <p>这里按配置选择 Excel 写入方式和预警通知方式，同时提供独立线程池，
 * 避免后台工具调用阻塞学生端聊天。</p>
 */
public class McpToolConfig {

    @Bean
    public TaskExecutor mcpTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("multimodalAgent-mcp-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(200);
        executor.initialize();
        return executor;
    }

    @Bean
    public ExcelReportWriter excelReportWriter(
            multimodalAgentProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper
    ) {
        if ("mcp".equalsIgnoreCase(properties.getMcp().getExcel().getMode())) {
            return new McpExcelReportWriter(new McpProtocolClient(
                    webClientBuilder,
                    objectMapper,
                    properties.getMcp().getExcel().getUrl()));
        }
        if ("http".equalsIgnoreCase(properties.getMcp().getExcel().getMode())) {
            return new HttpExcelReportWriter(webClientBuilder, properties);
        }
        return new LocalExcelReportWriter(properties);
    }

    @Bean
    public AlertNotifier alertNotifier(
            multimodalAgentProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            JavaMailSender mailSender
    ) {
        String mode = properties.getMcp().getEmail().getMode();
        if ("mcp".equalsIgnoreCase(mode)) {
            return new McpAlertNotifier(new McpProtocolClient(
                    webClientBuilder,
                    objectMapper,
                    properties.getMcp().getEmail().getUrl()));
        }
        if ("http".equalsIgnoreCase(mode)) {
            return new HttpAlertNotifier(webClientBuilder, properties);
        }
        if ("smtp".equalsIgnoreCase(mode)) {
            return new SmtpAlertNotifier(mailSender, properties);
        }
        return new LogAlertNotifier();
    }
}
