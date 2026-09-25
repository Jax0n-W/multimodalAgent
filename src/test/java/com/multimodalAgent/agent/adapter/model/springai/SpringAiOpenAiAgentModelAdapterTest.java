package com.multimodalAgent.agent.adapter.model.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelToolDefinition;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolErrorCode;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.runtime.trace.DecisionTraceBuilder;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiOpenAiAgentModelAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldMapConversationToolsFinalAnswerAndUsageWithoutProviderExecution() {
        CapturingChatModel chatModel = new CapturingChatModel(finalResponse("done", 11, 7));
        SpringAiOpenAiAgentModelAdapter adapter = adapter(chatModel);
        ModelToolDefinition definition = definition("knowledge_search");

        ModelTurn turn = adapter.generate(new AgentModelRequest(
                List.of(
                        AgentMessage.system("system"),
                        AgentMessage.user("question"),
                        AgentMessage.assistantToolCalls(List.of(new ToolCall(
                                "call-1",
                                "knowledge_search",
                                Map.of("query", "Redis Sentinel")
                        ))),
                        AgentMessage.toolResult("call-1", "knowledge_search", "facts")
                ),
                List.of(definition)
        ));

        assertEquals(ModelFinishReason.STOP, turn.finishReason());
        assertEquals("done", turn.content());
        assertEquals(new TokenUsage(11, 7), turn.tokenUsage());

        Prompt prompt = chatModel.prompts().get(0);
        List<Message> messages = prompt.getInstructions();
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertInstanceOf(UserMessage.class, messages.get(1));
        assertEquals("system", messages.get(0).getText());
        assertEquals("question", messages.get(1).getText());
        AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, messages.get(2));
        assertEquals("call-1", assistant.getToolCalls().get(0).id());
        assertEquals("knowledge_search", assistant.getToolCalls().get(0).name());
        assertEquals("{\"query\":\"Redis Sentinel\"}", assistant.getToolCalls().get(0).arguments());
        ToolResponseMessage toolResult = assertInstanceOf(ToolResponseMessage.class, messages.get(3));
        assertEquals("call-1", toolResult.getResponses().get(0).id());
        assertEquals("knowledge_search", toolResult.getResponses().get(0).name());
        assertEquals("facts", toolResult.getResponses().get(0).responseData());

        ToolCallingChatOptions options = assertInstanceOf(
                ToolCallingChatOptions.class,
                prompt.getOptions()
        );
        assertFalse(options.getInternalToolExecutionEnabled());
        assertEquals(1, options.getToolCallbacks().size());
        ToolCallback callback = options.getToolCallbacks().get(0);
        assertEquals("knowledge_search", callback.getToolDefinition().name());
        assertThrows(IllegalStateException.class, () -> callback.call("{}"));
    }

    @Test
    void shouldMapProviderToolCallsInOrderAndPreserveIdentityAndJsonArguments() {
        AssistantMessage output = new AssistantMessage("", Map.of(), List.of(
                providerCall("provider-call-a", "tool_a", "{\"query\":\"A\"}"),
                providerCall("provider-call-c", "tool_c", "{\"query\":\"C\",\"topK\":2}")
        ));
        CapturingChatModel chatModel = new CapturingChatModel(response(output, "TOOL_CALLS", null));

        ModelTurn turn = adapter(chatModel).generate(new AgentModelRequest(
                List.of(AgentMessage.user("use tools")),
                List.of(definition("tool_a"), definition("tool_c"))
        ));

        assertEquals(ModelFinishReason.TOOL_CALLS, turn.finishReason());
        assertEquals(List.of("provider-call-a", "provider-call-c"),
                turn.toolCalls().stream().map(ToolCall::id).toList());
        assertEquals(List.of("tool_a", "tool_c"),
                turn.toolCalls().stream().map(ToolCall::name).toList());
        assertEquals("A", turn.toolCalls().get(0).arguments().get("query"));
        assertEquals(2, turn.toolCalls().get(1).arguments().get("topK"));
        assertEquals(TokenUsage.UNKNOWN, turn.tokenUsage());
        assertFalse(turn.tokenUsage().isComplete());
    }

    @Test
    void shouldFailClosedForUnsupportedFinishReason() {
        CapturingChatModel chatModel = new CapturingChatModel(response(
                new AssistantMessage("partial"),
                "LENGTH",
                null
        ));

        assertThrows(
                SpringAiModelAdapterException.class,
                () -> adapter(chatModel).generate(new AgentModelRequest(
                        List.of(AgentMessage.user("answer")),
                        List.of()
                ))
        );
    }

    @Test
    void shouldGenerateJsonSchemaFromToolInputType() {
        SpringAiModelToolDefinitionProjector projector =
                new SpringAiModelToolDefinitionProjector(objectMapper);

        ModelToolDefinition definition = projector.project(KnowledgeLookupTool.DESCRIPTOR);

        assertEquals("knowledge_lookup", definition.name());
        assertEquals("object", definition.inputSchema().get("type"));
        Map<?, ?> properties = assertInstanceOf(
                Map.class,
                definition.inputSchema().get("properties")
        );
        assertTrue(properties.containsKey("query"));
    }

    @Test
    void shouldExecuteProviderToolCallOnlyThroughRuntimeAndReturnResultToSecondModelCall() {
        CapturingChatModel chatModel = new CapturingChatModel(
                toolCallResponse("provider-call-1", "knowledge_lookup", "Redis Sentinel"),
                finalResponse("Redis Sentinel supports automatic failover.", 9, 5)
        );
        KnowledgeLookupTool tool = new KnowledgeLookupTool();
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();

        AgentRunResult result = coordinator(chatModel, tool, publisher).execute(request(
                "run-round-trip",
                "Please use knowledge_lookup for Redis Sentinel.",
                Set.of("knowledge_lookup")
        ));

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals(1, tool.executions());
        assertEquals(2, chatModel.prompts().size());
        List<Message> secondMessages = chatModel.prompts().get(1).getInstructions();
        ToolResponseMessage response = assertInstanceOf(
                ToolResponseMessage.class,
                secondMessages.get(secondMessages.size() - 1)
        );
        assertEquals("provider-call-1", response.getResponses().get(0).id());
        assertTrue(response.getResponses().get(0).responseData().contains("automatic failover"));
        new DecisionTraceBuilder().build(publisher.events());
    }

    @Test
    void shouldLeaveSemanticallyInvalidArgumentsForRuntimeValidation() {
        CapturingChatModel chatModel = new CapturingChatModel(
                toolCallResponse("provider-call-invalid", "knowledge_lookup", "")
        );
        KnowledgeLookupTool tool = new KnowledgeLookupTool();
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();

        AgentRunResult result = coordinator(chatModel, tool, publisher).execute(request(
                "run-invalid-args",
                "use tool",
                Set.of("knowledge_lookup")
        ));

        assertEquals(AgentStopReason.TOOL_ERROR, result.stopReason());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.toolErrorCode());
        assertEquals(0, tool.executions());
        assertEquals(List.of(
                        AgentEventType.RUN_STARTED,
                        AgentEventType.MODEL_STARTED,
                        AgentEventType.MODEL_COMPLETED,
                        AgentEventType.TOOL_REQUESTED,
                        AgentEventType.TOOL_VALIDATION_FAILED,
                        AgentEventType.RUN_STOPPED
                ),
                publisher.events().stream().map(event -> event.type()).toList());
    }

    @Test
    void shouldMapProviderFailureToRuntimeModelErrorEvents() {
        ChatModel failing = prompt -> {
            throw new IllegalStateException("provider unavailable");
        };
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();

        AgentRunResult result = coordinator(failing, new KnowledgeLookupTool(), publisher)
                .execute(request("run-provider-failure", "answer", Set.of()));

        assertEquals(AgentStopReason.MODEL_ERROR, result.stopReason());
        assertEquals(List.of(
                        AgentEventType.RUN_STARTED,
                        AgentEventType.MODEL_STARTED,
                        AgentEventType.MODEL_FAILED,
                        AgentEventType.RUN_STOPPED
                ),
                publisher.events().stream().map(event -> event.type()).toList());
    }

    private SpringAiOpenAiAgentModelAdapter adapter(ChatModel chatModel) {
        return new SpringAiOpenAiAgentModelAdapter(chatModel, objectMapper);
    }

    private AgentExecutionCoordinator coordinator(
            ChatModel chatModel,
            KnowledgeLookupTool tool,
            RecordingAgentEventPublisher publisher
    ) {
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        ToolExecutor executor = new ToolExecutor(
                registry,
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        AgentRunner runner = new AgentRunner(
                adapter(chatModel),
                executor,
                new SpringAiModelToolDefinitionProjector(objectMapper),
                publisher
        );
        return new AgentExecutionCoordinator(runner, RuntimeMiddlewareChain.empty());
    }

    private AgentExecutionRequest request(String runId, String prompt, Set<String> allowedTools) {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        runId,
                        "session-p5v",
                        List.of(AgentMessage.user(prompt)),
                        3,
                        allowedTools,
                        Set.of()
                ),
                "request-" + runId,
                1L
        );
    }

    private ModelToolDefinition definition(String name) {
        return new ModelToolDefinition(
                name,
                "Test definition " + name,
                Map.of(
                        "type", "object",
                        "properties", Map.of("query", Map.of("type", "string")),
                        "required", List.of("query")
                )
        );
    }

    private ChatResponse toolCallResponse(String id, String name, String query) {
        AssistantMessage output = new AssistantMessage("", Map.of(), List.of(
                providerCall(id, name, "{\"query\":\"" + query + "\"}")
        ));
        return response(output, "TOOL_CALLS", new DefaultUsage(5, 3));
    }

    private ChatResponse finalResponse(String content, int inputTokens, int outputTokens) {
        return response(
                new AssistantMessage(content),
                "STOP",
                new DefaultUsage(inputTokens, outputTokens)
        );
    }

    private AssistantMessage.ToolCall providerCall(
            String id,
            String name,
            String arguments
    ) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private ChatResponse response(AssistantMessage output, String finishReason, DefaultUsage usage) {
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason(finishReason)
                .build();
        Generation generation = new Generation(output, generationMetadata);
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
        if (usage != null) {
            metadata.usage(usage);
        }
        return new ChatResponse(List.of(generation), metadata.build());
    }

    private record KnowledgeLookupInput(@NotBlank String query) {
    }

    private static final class KnowledgeLookupTool
            implements AgentTool<KnowledgeLookupInput, String> {

        private static final ToolDescriptor<KnowledgeLookupInput> DESCRIPTOR =
                new ToolDescriptor<>(
                        "knowledge_lookup",
                        "Look up one deterministic knowledge fact",
                        KnowledgeLookupInput.class,
                        ToolRisk.LOW,
                        true,
                        true,
                        false
                );

        private int executions;

        @Override
        public ToolDescriptor<KnowledgeLookupInput> descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public String execute(KnowledgeLookupInput input) {
            executions++;
            return "Redis Sentinel monitors Redis and supports automatic failover.";
        }

        private int executions() {
            return executions;
        }
    }

    private static final class CapturingChatModel implements ChatModel {

        private final Deque<ChatResponse> responses;
        private final List<Prompt> prompts = new ArrayList<>();

        private CapturingChatModel(ChatResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            ChatResponse response = responses.pollFirst();
            if (response == null) {
                throw new IllegalStateException("No response remains");
            }
            return response;
        }

        private List<Prompt> prompts() {
            return List.copyOf(prompts);
        }
    }
}
