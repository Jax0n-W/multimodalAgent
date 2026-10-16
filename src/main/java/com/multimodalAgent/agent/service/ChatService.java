package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.dto.ChatRequest;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.service.chat.UnifiedChatRuntimeService;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Stable chat API boundary. Every text and multimodal request executes through the unified
 * Agent Runtime; existing campus business behavior is hosted by the runtime orchestration service.
 */
@Service
public class ChatService {

    private static final String SAFE_ERROR_MESSAGE =
            "服务暂时不可用，请稍后重试。本次请求不会自动重新执行。";

    private final UnifiedChatRuntimeService runtime;

    public ChatService(UnifiedChatRuntimeService runtime) {
        this.runtime = runtime;
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> streamChat(
            Long userId,
            ChatRequest request
    ) {
        return stream(userId, request, null);
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> streamMultimodal(
            Long userId,
            ChatRequest request,
            MultimodalAnalysis analysis
    ) {
        return stream(userId, request, analysis);
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> stream(
            Long userId,
            ChatRequest request,
            MultimodalAnalysis analysis
    ) {
        return runtime.stream(userId, request, analysis)
                .onErrorResume(ignored -> Flux.just(ServerSentEvent
                        .builder(ChatStreamEvent.error(null, SAFE_ERROR_MESSAGE))
                        .event("error")
                        .build()));
    }
}
