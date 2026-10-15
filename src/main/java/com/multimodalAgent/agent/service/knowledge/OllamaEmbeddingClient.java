package com.multimodalAgent.agent.service.knowledge;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;

/** Project adapter around Spring AI's Ollama embedding model. */
public class OllamaEmbeddingClient implements EmbeddingClient {

    private final EmbeddingModel embeddingModel;
    private final String modelName;

    public OllamaEmbeddingClient(EmbeddingModel embeddingModel, String modelName) {
        this.embeddingModel = embeddingModel;
        this.modelName = modelName;
    }

    @Override
    public List<Double> embed(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        float[] vector = embeddingModel.embed(text);
        if (vector == null || vector.length == 0) {
            return List.of();
        }
        List<Double> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("Ollama embedding response contains non-finite values");
            }
            values.add((double) value);
        }
        return List.copyOf(values);
    }

    @Override
    public String modelName() {
        return modelName;
    }
}
