package com.multimodalAgent.agent.service.knowledge;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OllamaEmbeddingClientTest {

    @Test
    void adaptsSpringAiFloatVectorWithoutSyntheticFallback() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("真实向量")).thenReturn(new float[]{0.25f, -0.5f});
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(model, "bge-m3");

        assertEquals(List.of(0.25, -0.5), client.embed("真实向量"));
        assertEquals("bge-m3", client.modelName());
    }

    @Test
    void rejectsInvalidBackendValues() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("bad")).thenReturn(new float[]{Float.NaN});
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(model, "bge-m3");

        assertThrows(IllegalStateException.class, () -> client.embed("bad"));
    }
}
