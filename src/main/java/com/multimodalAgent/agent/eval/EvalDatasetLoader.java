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

    public static final String CONTRACT_RESOURCE = "/eval/p9.4-runtime-contract-suite.json";
    public static final String REAL_MODEL_RESOURCE = "/eval/p9.4-real-model-baseline-suite.json";

    private final ObjectMapper objectMapper;

    public EvalDatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public EvalSuite loadDefault() throws IOException {
        return loadResource(CONTRACT_RESOURCE);
    }

    public EvalSuite loadRealModel() throws IOException {
        EvalSuite contractSuite = loadDefault();
        RealSuiteManifest manifest;
        try (InputStream input = Objects.requireNonNull(
                EvalDatasetLoader.class.getResourceAsStream(REAL_MODEL_RESOURCE),
                "Missing eval dataset: " + REAL_MODEL_RESOURCE
        )) {
            manifest = objectMapper.readValue(input, RealSuiteManifest.class);
        }
        java.util.Map<String, EvalCase> byId = contractSuite.cases().stream()
                .collect(java.util.stream.Collectors.toMap(EvalCase::caseId, value -> value));
        List<EvalCase> cases = manifest.caseIds().stream()
                .map(caseId -> Objects.requireNonNull(
                        byId.get(caseId), "Unknown real-model caseId: " + caseId
                ))
                .toList();
        return new EvalSuite(manifest.suiteId(), manifest.suiteVersion(), cases);
    }

    private EvalSuite loadResource(String resource) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                EvalDatasetLoader.class.getResourceAsStream(resource),
                "Missing eval dataset: " + resource
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
                new EvalExecutionConfig(
                        source.execution().maxIterations() == null
                                ? 3
                                : source.execution().maxIterations(),
                        set(source.execution().allowedTools()),
                        new EvalExecutionBudget(
                                source.execution().budget() == null
                                        ? null
                                        : source.execution().budget().maxModelCalls(),
                                source.execution().budget() == null
                                        ? null
                                        : source.execution().budget().maxToolCalls()
                        )
                ),
                new EvalOracle(
                        source.oracle().expectedStopReason(),
                        set(source.oracle().expectedTools()),
                        set(source.oracle().forbiddenTools()),
                        source.oracle().maxExpectedModelCalls(),
                        source.oracle().maxExpectedToolCalls()
                )
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
            DatasetExecution execution,
            DatasetOracle oracle
    ) {
    }

    private record DatasetExecution(
            Integer maxIterations,
            List<String> allowedTools,
            DatasetBudget budget
    ) {
    }

    private record DatasetBudget(Long maxModelCalls, Long maxToolCalls) {
    }

    private record DatasetOracle(
            AgentStopReason expectedStopReason,
            List<String> expectedTools,
            List<String> forbiddenTools,
            Long maxExpectedModelCalls,
            Long maxExpectedToolCalls
    ) {
    }

    private record DatasetMessage(String role, String content) {
    }

    private record RealSuiteManifest(
            String suiteId,
            String suiteVersion,
            List<String> caseIds
    ) {
    }
}
