package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.streaming.ExecutionStreamHub;
import com.multimodalAgent.agent.streaming.ExecutionStreamSubscription;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/** WebFlux transport adapter for node-local, live-only execution observation. */
@RestController
@RequestMapping("/api/agent/runs")
public final class ExecutionStreamSseController {

    private final ExecutionStreamHub hub;

    public ExecutionStreamSseController(ExecutionStreamHub hub) {
        this.hub = hub;
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ExecutionStreamEvent>> stream(
            @PathVariable String runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String ignoredLastEventId
    ) {
        final ExecutionStreamSubscription subscription;
        try {
            subscription = hub.subscribe(runId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
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
