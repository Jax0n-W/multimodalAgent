package com.multimodalAgent.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.dto.AgentRunStartRequest;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.streaming.ExecutionStreamHub;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;
import com.multimodalAgent.agent.streaming.integration.LocalExecutionControlRegistry;
import com.multimodalAgent.agent.streaming.integration.StreamingAgentExecutionService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunControllerTest {

    @Test
    void legacyRequestFallsBackToRunIdAsSessionId() throws Exception {
        AgentRunStartRequest request = new ObjectMapper().readValue(
                "{\"runId\":\"run-1\",\"message\":\"hello\"}",
                AgentRunStartRequest.class
        );

        AgentExecutionRequest captured = execute(request);

        assertEquals("run-1", captured.runSpec().runId());
        assertEquals("run-1", captured.runSpec().sessionId());
        assertEquals(41L, captured.userId());
    }

    @Test
    void explicitSessionIdIsStableAndUserIdentityComesFromAuthentication() {
        AgentExecutionRequest captured = execute(new AgentRunStartRequest(
                "run-2", "stable-session", "hello again"
        ));

        assertEquals("run-2", captured.runSpec().runId());
        assertEquals("stable-session", captured.runSpec().sessionId());
        assertEquals(41L, captured.userId());
        assertEquals(List.of(AgentMessage.user("hello again")), captured.runSpec().messages());
    }

    @Test
    void requestHasNoClientControlledUserIdAndHonorsDatabaseIdentityLengths() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        AgentRunStartRequest invalid = new AgentRunStartRequest(
                "r".repeat(65), "s".repeat(65), "hello"
        );

        assertEquals(2, validator.validate(invalid).size());
        assertFalse(List.of(AgentRunStartRequest.class.getRecordComponents()).stream()
                .anyMatch(component -> component.getName().equals("userId")));
    }

    private AgentExecutionRequest execute(AgentRunStartRequest request) {
        AtomicReference<AgentExecutionRequest> captured = new AtomicReference<>();
        ExecutionStreamHub hub = new ExecutionStreamHub();
        ExecutionStreamPublisher publisher = new ExecutionStreamPublisher(hub);
        StreamingAgentExecutionService service = new StreamingAgentExecutionService(
                executionRequest -> {
                    captured.set(executionRequest);
                    return completed(executionRequest);
                },
                hub,
                publisher,
                new LocalExecutionControlRegistry(publisher)
        );
        AgentRunController controller = new AgentRunController(
                service, ExecutionBudget.unlimited()
        );
        CurrentUser user = mock(CurrentUser.class);
        when(user.getId()).thenReturn(41L);

        controller.run(user, request).block();
        return captured.get();
    }

    private AgentRunResult completed(AgentExecutionRequest request) {
        return new AgentRunResult(
                "done",
                AgentStopReason.COMPLETED,
                1,
                List.of(),
                request.runSpec().messages(),
                TokenUsage.ZERO,
                null,
                null,
                null
        );
    }
}
