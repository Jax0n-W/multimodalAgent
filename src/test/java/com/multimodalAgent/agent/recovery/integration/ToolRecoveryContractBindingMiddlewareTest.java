package com.multimodalAgent.agent.recovery.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.recovery.ToolRecoveryContractBindingStore;
import com.multimodalAgent.agent.recovery.ToolRecoveryContractSnapshot;
import com.multimodalAgent.agent.runtime.budget.BudgetSession;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.event.AgentEventEmitter;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareFailureException;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyContext;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRecoveryContractBindingMiddlewareTest {

    private static final String RUN_ID = "run-binding";
    private static final String TOOL_NAME = "side_effect_tool";

    @Test
    void bindingHappensBeforeExternalToolExecution() {
        List<String> sequence = new ArrayList<>();
        RecordingStore store = new RecordingStore(sequence, false);
        CountingTool tool = new CountingTool(sequence);
        RecordingAgentEventPublisher events = new RecordingAgentEventPublisher();

        execute(tool, store, events);

        assertEquals(List.of("CONTRACT_BOUND", "TOOL_EXECUTED"), sequence);
        assertEquals(1, tool.executionCount());
        assertTrue(events.events().stream()
                .anyMatch(event -> event.type() == AgentEventType.TOOL_STARTED));
        assertEquals(TOOL_NAME, store.contract().toolName());
    }

    @Test
    void bindingFailurePreventsToolStartedAndExternalInvocation() {
        List<String> sequence = new ArrayList<>();
        RecordingStore store = new RecordingStore(sequence, true);
        CountingTool tool = new CountingTool(sequence);
        RecordingAgentEventPublisher events = new RecordingAgentEventPublisher();

        assertThrows(RuntimeMiddlewareFailureException.class, () ->
                execute(tool, store, events)
        );

        assertEquals(0, tool.executionCount());
        assertEquals(List.of("CONTRACT_BIND_FAILED"), sequence);
        assertFalse(events.events().stream()
                .anyMatch(event -> event.type() == AgentEventType.TOOL_STARTED));
    }

    private void execute(
            CountingTool tool,
            ToolRecoveryContractBindingStore store,
            RecordingAgentEventPublisher events
    ) {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(List.of(tool)),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        ToolPolicyContext policy = new ToolPolicyContext(
                RUN_ID,
                "session-binding",
                Set.of(TOOL_NAME),
                Set.of()
        );
        executor.execute(
                new ToolCall("call-binding", TOOL_NAME, Map.of("value", "payload")),
                policy,
                new AgentEventEmitter(RUN_ID, events),
                1,
                AgentRuntimeContext.minimal(RUN_ID, "session-binding"),
                new RuntimeMiddlewareChain(List.of(
                        new ToolRecoveryContractBindingMiddleware(store)
                )),
                new BudgetSession(ExecutionBudget.unlimited(), Optional.empty())
        );
    }

    private record Input(String value) {
    }

    private static final class CountingTool implements AgentTool<Input, String> {

        private static final ToolDescriptor<Input> DESCRIPTOR = new ToolDescriptor<>(
                TOOL_NAME,
                "Side effect test tool",
                Input.class,
                ToolRisk.MEDIUM,
                false,
                false,
                false
        );

        private final List<String> sequence;
        private final AtomicInteger executions = new AtomicInteger();

        private CountingTool(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public ToolDescriptor<Input> descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public String execute(Input input) {
            executions.incrementAndGet();
            sequence.add("TOOL_EXECUTED");
            return input.value();
        }

        private int executionCount() {
            return executions.get();
        }
    }

    private static final class RecordingStore implements ToolRecoveryContractBindingStore {

        private final List<String> sequence;
        private final boolean fail;
        private ToolRecoveryContractSnapshot contract;

        private RecordingStore(List<String> sequence, boolean fail) {
            this.sequence = sequence;
            this.fail = fail;
        }

        @Override
        public void bind(
                String runId,
                String toolCallId,
                ToolRecoveryContractSnapshot contract
        ) {
            if (fail) {
                sequence.add("CONTRACT_BIND_FAILED");
                throw new IllegalStateException("binding unavailable");
            }
            this.contract = contract;
            sequence.add("CONTRACT_BOUND");
        }

        private ToolRecoveryContractSnapshot contract() {
            return contract;
        }
    }
}
