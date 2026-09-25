package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.SpringAiModelAdapterException;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingTurnAccumulatorTest {

    @Test
    void preservesTextAndWhitespaceInProviderEncounterOrder() {
        StreamingTurnAccumulator accumulator = accumulator();
        List<String> observed = new ArrayList<>();

        accumulator.accept(chunk("Hello", null), observed::add);
        accumulator.accept(chunk(" ", null), observed::add);
        accumulator.accept(chunk("world", OpenAiApi.ChatCompletionFinishReason.STOP), observed::add);
        accumulator.accept(OpenAiStreamEvent.Done.INSTANCE, observed::add);

        ModelTurn turn = accumulator.complete();

        assertEquals(List.of("Hello", " ", "world"), observed);
        assertEquals(ModelFinishReason.STOP, turn.finishReason());
        assertEquals("Hello world", turn.content());
    }

    @Test
    void preservesWhitespaceOnlyDeltas() {
        StreamingTurnAccumulator accumulator = accumulator();
        List<String> observed = new ArrayList<>();

        accumulator.accept(chunk(" ", null), observed::add);
        accumulator.accept(chunk("\n", OpenAiApi.ChatCompletionFinishReason.STOP), observed::add);
        accumulator.accept(OpenAiStreamEvent.Done.INSTANCE, observed::add);

        assertEquals(List.of(" ", "\n"), observed);
        assertEquals(" \n", accumulator.complete().content());
    }

    @Test
    void manyChunksStillProduceOneCompleteTurn() {
        StreamingTurnAccumulator accumulator = accumulator();

        for (int index = 0; index < 500; index++) {
            accumulator.accept(chunk("x", null), ignored -> {
            });
        }
        accumulator.accept(chunk("", OpenAiApi.ChatCompletionFinishReason.STOP), ignored -> {
        });
        accumulator.accept(OpenAiStreamEvent.Done.INSTANCE, ignored -> {
        });

        ModelTurn turn = accumulator.complete();

        assertEquals(500, turn.content().length());
        assertEquals(ModelFinishReason.STOP, turn.finishReason());
    }

    @Test
    void finalUsageChunkIsAuthoritative() {
        StreamingTurnAccumulator accumulator = accumulator();

        accumulator.accept(chunk("done", OpenAiApi.ChatCompletionFinishReason.STOP), ignored -> {
        });
        accumulator.accept(usageChunk(7, 3), ignored -> {
        });
        accumulator.accept(OpenAiStreamEvent.Done.INSTANCE, ignored -> {
        });

        ModelTurn turn = accumulator.complete();

        assertTrue(accumulator.usageProvided());
        assertEquals(new TokenUsage(7, 3), turn.tokenUsage());
    }

    @Test
    void missingProviderUsageIsNotEstimated() {
        StreamingTurnAccumulator accumulator = accumulator();

        accumulator.accept(chunk("done", OpenAiApi.ChatCompletionFinishReason.STOP), ignored -> {
        });
        accumulator.accept(OpenAiStreamEvent.Done.INSTANCE, ignored -> {
        });

        ModelTurn turn = accumulator.complete();

        assertFalse(accumulator.usageProvided());
        assertEquals(TokenUsage.UNKNOWN, turn.tokenUsage());
        assertFalse(turn.tokenUsage().isComplete());
    }

    @Test
    void duplicateUsageChunksFailClosedInsteadOfOverwritingUsage() {
        StreamingTurnAccumulator accumulator = accumulator();

        accumulator.accept(usageChunk(7, 3), ignored -> {
        });

        assertThrows(
                SpringAiModelAdapterException.class,
                () -> accumulator.accept(usageChunk(8, 4), ignored -> {
                })
        );
    }

    @Test
    void missingDoneFrameFailsClosedEvenAfterFinishReason() {
        StreamingTurnAccumulator accumulator = accumulator();
        accumulator.accept(chunk("partial", OpenAiApi.ChatCompletionFinishReason.STOP), ignored -> {
        });

        assertThrows(SpringAiModelAdapterException.class, accumulator::complete);
    }

    @Test
    void lengthPreservesPartialTextAsAnOutputLimitTurn() {
        StreamingTurnAccumulator accumulator = accumulator();
        accumulator.accept(chunk("partial", OpenAiApi.ChatCompletionFinishReason.LENGTH), ignored -> {
        });
        accumulator.accept(OpenAiStreamEvent.Done.INSTANCE, ignored -> {
        });

        ModelTurn turn = accumulator.complete();

        assertEquals(ModelFinishReason.LENGTH, turn.finishReason());
        assertEquals("partial", turn.content());
        assertTrue(turn.toolCalls().isEmpty());
    }

    @Test
    void lengthDiscardsPartialToolCallFragmentsInsteadOfExposingThem() {
        StreamingTurnAccumulator accumulator = accumulator();
        accumulator.accept(toolChunk(
                new OpenAiApi.ChatCompletionMessage.ToolCall(
                        0,
                        "call-partial",
                        "function",
                        new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(
                                "dangerous_write",
                                "{\"value\":"
                        )
                ),
                OpenAiApi.ChatCompletionFinishReason.LENGTH
        ), ignored -> {
        });
        accumulator.accept(OpenAiStreamEvent.Done.INSTANCE, ignored -> {
        });

        ModelTurn turn = accumulator.complete();

        assertEquals(ModelFinishReason.LENGTH, turn.finishReason());
        assertTrue(turn.toolCalls().isEmpty());
    }

    private StreamingTurnAccumulator accumulator() {
        return new StreamingTurnAccumulator("test-provider", new ObjectMapper());
    }

    private OpenAiStreamEvent chunk(
            String content,
            OpenAiApi.ChatCompletionFinishReason finishReason
    ) {
        OpenAiApi.ChatCompletionMessage delta = new OpenAiApi.ChatCompletionMessage(
                content,
                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT
        );
        OpenAiApi.ChatCompletionChunk.ChunkChoice choice =
                new OpenAiApi.ChatCompletionChunk.ChunkChoice(
                        finishReason,
                        0,
                        delta,
                        null
                );
        return new OpenAiStreamEvent.Chunk(new OpenAiApi.ChatCompletionChunk(
                "response-1",
                List.of(choice),
                1L,
                "test-model",
                null,
                null,
                "chat.completion.chunk",
                null
        ));
    }

    private OpenAiStreamEvent usageChunk(int promptTokens, int completionTokens) {
        return new OpenAiStreamEvent.Chunk(new OpenAiApi.ChatCompletionChunk(
                "response-1",
                List.of(),
                1L,
                "test-model",
                null,
                null,
                "chat.completion.chunk",
                new OpenAiApi.Usage(
                        completionTokens,
                        promptTokens,
                        promptTokens + completionTokens
                )
        ));
    }

    private OpenAiStreamEvent toolChunk(
            OpenAiApi.ChatCompletionMessage.ToolCall fragment,
            OpenAiApi.ChatCompletionFinishReason finishReason
    ) {
        OpenAiApi.ChatCompletionMessage delta = new OpenAiApi.ChatCompletionMessage(
                null,
                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT,
                null,
                null,
                List.of(fragment),
                null,
                null,
                null
        );
        OpenAiApi.ChatCompletionChunk.ChunkChoice choice =
                new OpenAiApi.ChatCompletionChunk.ChunkChoice(
                        finishReason,
                        0,
                        delta,
                        null
                );
        return new OpenAiStreamEvent.Chunk(new OpenAiApi.ChatCompletionChunk(
                "response-1",
                List.of(choice),
                1L,
                "test-model",
                null,
                null,
                "chat.completion.chunk",
                null
        ));
    }
}
