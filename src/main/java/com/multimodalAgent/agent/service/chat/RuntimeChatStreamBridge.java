package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.ModelCompletedEvent;
import com.multimodalAgent.agent.runtime.event.RunCompletedEvent;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.stream.ModelDelta;
import com.multimodalAgent.agent.stream.RuntimeEventPayload;
import com.multimodalAgent.agent.streaming.ExecutionStreamSubscription;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Filters the unified live stream down to the one final student-visible model turn. */
final class RuntimeChatStreamBridge {

    private static final int MAX_VISIBLE_CHARS = 12_000;

    private final String sessionId;
    private final FluxSink<ServerSentEvent<ChatStreamEvent>> sink;
    private final Map<Integer, List<String>> deltas = new HashMap<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final StringBuilder visible = new StringBuilder();
    private Disposable receiver;
    private Integer finalIteration;
    private boolean runCompleted;

    RuntimeChatStreamBridge(
            String sessionId,
            FluxSink<ServerSentEvent<ChatStreamEvent>> sink
    ) {
        this.sessionId = sessionId;
        this.sink = sink;
    }

    synchronized void attach(ExecutionStreamSubscription subscription) {
        if (receiver != null) {
            throw new IllegalStateException("Runtime stream observer already attached");
        }
        receiver = subscription.events().subscribe(
                this::accept,
                drained::completeExceptionally,
                () -> drained.complete(null)
        );
    }

    private synchronized void accept(ExecutionStreamEvent event) {
        if (event.payload() instanceof ModelDelta delta) {
            int total = deltas.values().stream()
                    .flatMap(List::stream)
                    .mapToInt(String::length)
                    .sum();
            if (total + delta.content().length() > MAX_VISIBLE_CHARS) {
                throw new IllegalStateException("Student-visible model output exceeded limit");
            }
            deltas.computeIfAbsent(delta.iteration(), ignored -> new ArrayList<>())
                    .add(delta.content());
            return;
        }
        if (!(event.payload() instanceof RuntimeEventPayload payload)) {
            return;
        }
        if (payload.event() instanceof ModelCompletedEvent completed) {
            if (completed.finishReason() == ModelFinishReason.STOP) {
                finalIteration = completed.iteration();
            } else {
                deltas.remove(completed.iteration());
            }
            return;
        }
        if (payload.event() instanceof RunCompletedEvent completed) {
            if (finalIteration == null || finalIteration != completed.iteration()) {
                throw new IllegalStateException("Completed run has no final STOP model turn");
            }
            runCompleted = true;
            for (String content : deltas.getOrDefault(finalIteration, List.of())) {
                visible.append(content);
                if (!sink.isCancelled()) {
                    sink.next(event("token", ChatStreamEvent.token(sessionId, content)));
                }
            }
        }
    }

    void awaitDrainAndVerify(AgentRunResult result) {
        try {
            drained.join();
        } catch (CompletionException exception) {
            throw new IllegalStateException("Runtime observation failed", exception.getCause());
        }
        synchronized (this) {
            if (result.stopReason() != AgentStopReason.COMPLETED || !runCompleted) {
                return;
            }
            if (visible.isEmpty() && !result.finalContent().isEmpty()) {
                visible.append(result.finalContent());
                if (!sink.isCancelled()) {
                    sink.next(event(
                            "token",
                            ChatStreamEvent.token(sessionId, result.finalContent())
                    ));
                }
            }
            if (!visible.toString().equals(result.finalContent())) {
                throw new IllegalStateException(
                        "Student-visible output does not match durable final content"
                );
            }
            if (visible.isEmpty()) {
                throw new IllegalStateException("Model completed without reply content");
            }
        }
    }

    private ServerSentEvent<ChatStreamEvent> event(String name, ChatStreamEvent data) {
        return ServerSentEvent.builder(data).event(name).build();
    }
}
