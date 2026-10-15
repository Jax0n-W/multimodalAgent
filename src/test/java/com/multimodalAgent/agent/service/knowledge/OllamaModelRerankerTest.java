package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OllamaModelRerankerTest {

    @Test
    void appliesReturnedQueryDocumentScores() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(
                "```json\n{\"scores\":[{\"index\":0,\"score\":0.2},"
                        + "{\"index\":1,\"score\":0.9}]}\n```"
        ));
        Reranker reranker = new OllamaModelReranker(model, new ObjectMapper(), 1000);
        List<SearchResult> candidates = List.of(
                new SearchResult(1L, "a", "first", 0.03),
                new SearchResult(2L, "b", "second", 0.02)
        );

        RerankOutcome outcome = reranker.rerank("query", candidates, 2);

        assertEquals(List.of(2L, 1L), outcome.results().stream()
                .map(SearchResult::chunkId).toList());
        assertEquals(0.9, outcome.results().get(0).score(), 0.0);
    }

    @Test
    void rejectsIncompleteScoresSoCallerCanPreserveRrfOrder() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"scores\":[{\"index\":0,\"score\":0.5}]}"
        ));
        Reranker reranker = new OllamaModelReranker(model, new ObjectMapper(), 1000);

        assertThrows(IllegalStateException.class, () -> reranker.rerank(
                "query",
                List.of(
                        new SearchResult(1L, "a", "first", 0.03),
                        new SearchResult(2L, "b", "second", 0.02)
                ),
                2
        ));
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
