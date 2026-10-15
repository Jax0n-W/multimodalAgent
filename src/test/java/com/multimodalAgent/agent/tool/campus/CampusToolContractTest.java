package com.multimodalAgent.agent.tool.campus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.SpringAiModelToolDefinitionProjector;
import com.multimodalAgent.agent.repository.ConsultantScheduleSlotRepository;
import com.multimodalAgent.agent.runtime.model.ModelToolDefinition;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.ToolValidationException;
import com.multimodalAgent.agent.service.CrisisTicketService;
import com.multimodalAgent.agent.service.ReportService;
import com.multimodalAgent.agent.service.knowledge.KnowledgeService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CampusToolContractTest {

    @Test
    void registryPublishesFiveStrongJsonSchemaContracts() {
        Duration readTimeout = Duration.ofSeconds(4);
        List<AgentTool<?, ?>> tools = List.of(
                new SearchCampusPolicyTool(mock(KnowledgeService.class), readTimeout),
                new SearchCounselingResourcesTool(mock(KnowledgeService.class), readTimeout),
                new QueryConsultantScheduleTool(
                        mock(ConsultantScheduleSlotRepository.class),
                        readTimeout
                ),
                new QueryMyRecentAssessmentsTool(mock(ReportService.class), readTimeout),
                new SubmitCrisisTicketTool(
                        mock(CrisisTicketService.class),
                        Duration.ofSeconds(9)
                )
        );
        ToolRegistry registry = new ToolRegistry(tools);
        SpringAiModelToolDefinitionProjector projector =
                new SpringAiModelToolDefinitionProjector(new ObjectMapper());

        List<ModelToolDefinition> definitions = registry.descriptors().stream()
                .map(projector::project)
                .toList();

        assertEquals(Set.of(
                SearchCampusPolicyTool.NAME,
                SearchCounselingResourcesTool.NAME,
                QueryConsultantScheduleTool.NAME,
                QueryMyRecentAssessmentsTool.NAME,
                SubmitCrisisTicketTool.NAME
        ), definitions.stream().map(ModelToolDefinition::name)
                .collect(java.util.stream.Collectors.toSet()));
        for (ModelToolDefinition definition : definitions) {
            assertEquals("object", definition.inputSchema().get("type"));
            Map<?, ?> properties = assertInstanceOf(
                    Map.class,
                    definition.inputSchema().get("properties")
            );
            assertTrue(!properties.isEmpty());
        }
        assertTrue(registry.find(SubmitCrisisTicketTool.NAME)
                .orElseThrow().descriptor().requiresApproval());
        assertTrue(registry.find(SubmitCrisisTicketTool.NAME)
                .orElseThrow().descriptor().idempotent());
        assertEquals(ToolRisk.HIGH, registry.find(SubmitCrisisTicketTool.NAME)
                .orElseThrow().descriptor().risk());
    }

    @Test
    void serverRejectsUnknownOrOutOfRangeModelArguments() {
        ToolArgumentResolver resolver = new ToolArgumentResolver(
                new ObjectMapper(),
                Validation.buildDefaultValidatorFactory().getValidator()
        );

        assertThrows(ToolValidationException.class, () -> resolver.resolve(
                Map.of("query", "宿舍", "topK", 5, "userId", 99),
                SearchCampusPolicyTool.Input.class
        ));
        assertThrows(ToolValidationException.class, () -> resolver.resolve(
                Map.of("query", "宿舍", "topK", 100),
                SearchCampusPolicyTool.Input.class
        ));
    }
}
