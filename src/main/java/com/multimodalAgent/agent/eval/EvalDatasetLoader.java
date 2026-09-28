package com.multimodalAgent.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.model.AgentMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Loads the checked-in synthetic P9.4 dataset; it never reads student data. */
public final class EvalDatasetLoader {

    public static final String DEFAULT_RESOURCE = "/eval/p9.4-runtime-suite.json";

    private final ObjectMapper objectMapper;

    public EvalDatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public EvalSuite loadDefault() throws IOException {
        try (InputStream input = Objects.requireNonNull(
                EvalDatasetLoader.class.getResourceAsStream(DEFAULT_RESOURCE),
                "Missing eval dataset: " + DEFAULT_RESOURCE
        )) {
            return load(input);
        }
    }

    public EvalSuite load(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input must not be null");
        DatasetDocument document = objectMapper.readValue(input, DatasetDocument.class);
        List<EvalCase> cases = document.cases().stream().map(this::toCase).toList();
        return new EvalSuite(document.suiteId(), document.suiteVersion(), cases);
    }

    private EvalCase toCase(DatasetCase source) {
        return new EvalCase(
                source.caseId(),
                source.caseVersion(),
                source.category(),
                source.messages().stream().map(this::toMessage).toList(),
                source.expectedStopReason(),
                set(source.allowedTools()),
                set(source.expectedTools()),
                set(source.forbiddenTools()),
                source.maxIterations() == null ? 3 : source.maxIterations(),
                source.maxModelCalls(),
                source.maxToolCalls()
        );
    }

    private AgentMessage toMessage(DatasetMessage source) {
        return switch (source.role().toLowerCase(java.util.Locale.ROOT)) {
            case "system" -> AgentMessage.system(source.content());
            case "user" -> AgentMessage.user(source.content());
            case "assistant" -> AgentMessage.assistant(source.content());
            default -> throw new IllegalArgumentException(
                    "Unsupported eval message role: " + source.role()
            );
        };
    }

    private Set<String> set(List<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private record DatasetDocument(
            String suiteId,
            String suiteVersion,
            List<DatasetCase> cases
    ) {
    }

    private record DatasetCase(
            String caseId,
            String caseVersion,
            String category,
            List<DatasetMessage> messages,
            AgentStopReason expectedStopReason,
            List<String> allowedTools,
            List<String> expectedTools,
            List<String> forbiddenTools,
            Integer maxIterations,
            Long maxModelCalls,
            Long maxToolCalls
    ) {
    }

    private record DatasetMessage(String role, String content) {
    }
}
