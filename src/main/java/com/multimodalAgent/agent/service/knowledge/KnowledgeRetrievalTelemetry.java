package com.multimodalAgent.agent.service.knowledge;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Metrics, observations and structured logs for truthful retrieval outcomes. */
@Component
public class KnowledgeRetrievalTelemetry {

    public static final String LEXICAL_ONLY_DEGRADED = "LEXICAL_ONLY_DEGRADED";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalTelemetry.class);

    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    public KnowledgeRetrievalTelemetry(
            MeterRegistry meterRegistry,
            ObservationRegistry observationRegistry
    ) {
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
    }

    public void record(KnowledgeRetrievalResult result) {
        try {
            recordSafely(result);
        } catch (RuntimeException telemetryFailure) {
            log.error("knowledge_retrieval_telemetry_failed status={} cause={}",
                    result.status(), telemetryFailure.getClass().getSimpleName());
        }
    }

    private void recordSafely(KnowledgeRetrievalResult result) {
        String mode = result.requestedMode().name();
        String status = result.status().name();
        Timer.builder("knowledge.retrieval.duration")
                .description("End-to-end knowledge retrieval duration")
                .tag("mode", mode)
                .tag("status", status)
                .register(meterRegistry)
                .record(result.latencyNanos(), TimeUnit.NANOSECONDS);

        Observation observation = Observation.createNotStarted(
                        "knowledge.retrieval.result", observationRegistry)
                .lowCardinalityKeyValue("retrieval.mode", mode)
                .lowCardinalityKeyValue("retrieval.status", status);
        observation.start();
        if (result.status() == KnowledgeRetrievalStatus.LEXICAL_ONLY_DEGRADED) {
            observation.lowCardinalityKeyValue("retrieval.degradation", LEXICAL_ONLY_DEGRADED)
                    .event(Observation.Event.of(LEXICAL_ONLY_DEGRADED));
            Counter.builder("knowledge.retrieval.degraded")
                    .description("Knowledge retrieval degradation count")
                    .tag("marker", LEXICAL_ONLY_DEGRADED)
                    .tag("mode", mode)
                    .register(meterRegistry)
                    .increment();
            log.warn("knowledge_retrieval_degraded marker={} mode={} detail={}",
                    LEXICAL_ONLY_DEGRADED, mode, result.detail());
        }
        observation.stop();
    }

    public void recordRerankFallback(String reason) {
        try {
            recordRerankFallbackSafely(reason);
        } catch (RuntimeException telemetryFailure) {
            log.error("knowledge_rerank_telemetry_failed cause={}",
                    telemetryFailure.getClass().getSimpleName());
        }
    }

    private void recordRerankFallbackSafely(String reason) {
        Counter.builder("knowledge.retrieval.rerank.fallback")
                .description("Post-RRF reranker fallback count")
                .register(meterRegistry)
                .increment();
        Observation observation = Observation.createNotStarted(
                        "knowledge.retrieval.rerank.fallback", observationRegistry)
                .lowCardinalityKeyValue("retrieval.rerank.status", "RRF_PRESERVED")
                .start();
        observation.event(Observation.Event.of("RERANK_FALLBACK"));
        observation.stop();
        log.warn("knowledge_rerank_fallback order=RRF_PRESERVED reason={}", reason);
    }
}
