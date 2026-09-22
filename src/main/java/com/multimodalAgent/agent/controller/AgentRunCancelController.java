package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.dto.CancelRunResponse;
import com.multimodalAgent.agent.runtime.control.CancelRequestResult;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.streaming.integration.LocalRunCancellationService;
import com.multimodalAgent.agent.streaming.integration.DistributedCancellationUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Explicit cancellation endpoint; SSE disconnect remains observation-only. */
@RestController
@RequestMapping("/api/agent/runs")
@ConditionalOnProperty(
        prefix = "multimodal-agent.runtime",
        name = "enabled",
        havingValue = "true"
)
public final class AgentRunCancelController {

    private final LocalRunCancellationService cancellation;

    public AgentRunCancelController(LocalRunCancellationService cancellation) {
        this.cancellation = cancellation;
    }

    @PostMapping("/{runId}/cancel")
    public Mono<CancelRunResponse> cancel(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable String runId
    ) {
        return Mono.fromCallable(() -> cancellation.cancel(runId, user.getId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> {
                    if (result == CancelRequestResult.NOT_ACTIVE) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT);
                    }
                    return new CancelRunResponse(result);
                })
                .onErrorMap(DistributedCancellationUnavailableException.class,
                        failure -> new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "Distributed cancellation outcome is unavailable",
                                failure
                        ));
    }
}
