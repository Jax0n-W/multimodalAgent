package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.dto.AgentRunStartRequest;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.streaming.integration.StreamingAgentExecutionService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Direct Agent Runtime entry point for callers that do not use the chat compatibility API. */
@RestController
@RequestMapping("/api/agent/runs")
public final class AgentRunController {

    private final StreamingAgentExecutionService execution;
    private final ExecutionBudget budget;

    public AgentRunController(
            StreamingAgentExecutionService execution,
            ExecutionBudget budget
    ) {
        this.execution = execution;
        this.budget = budget;
    }

    @PostMapping
    public Mono<AgentRunResult> run(
            @AuthenticationPrincipal CurrentUser user,
            @Valid @RequestBody AgentRunStartRequest request
    ) {
        AgentRunSpec spec = new AgentRunSpec(
                request.runId(),
                request.sessionId() == null ? request.runId() : request.sessionId(),
                List.of(AgentMessage.user(request.message())),
                3,
                Set.of("knowledge_search"),
                Set.of(),
                budget
        );
        AgentExecutionRequest executionRequest = new AgentExecutionRequest(
                spec,
                UUID.randomUUID().toString(),
                user.getId()
        );
        return Mono.fromCallable(() -> execution.execute(executionRequest))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
