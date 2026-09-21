package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.streaming.ExecutionStreamHub;
import com.multimodalAgent.agent.streaming.ExecutionStreamSubscription;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** WebFlux transport adapter for node-local, live-only execution observation. */
@RestController
@RequestMapping("/api/agent/runs")
public final class ExecutionStreamSseController {

    private final ExecutionStreamHub hub;
    private final AgentRunRepository runs;

    public ExecutionStreamSseController(ExecutionStreamHub hub, AgentRunRepository runs) {
        this.hub = hub;
        this.runs = runs;
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ExecutionStreamEvent>> stream(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable String runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String ignoredLastEventId
    ) {
        return Mono.fromCallable(() -> runs.existsByRunIdAndUserId(runId, user.getId()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(owned -> owned ? subscribe(runId)
                        : Flux.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    private Flux<ServerSentEvent<ExecutionStreamEvent>> subscribe(String runId) {
        final ExecutionStreamSubscription subscription;
        try {
            subscription = hub.subscribe(runId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return subscription.events()
                .map(event -> ServerSentEvent.<ExecutionStreamEvent>builder()
                        .id(Long.toString(event.streamSequence()))
                        .event(event.kind().name())
                        .data(event)
                        .build())
                .doFinally(ignored -> subscription.close());
    }
}
