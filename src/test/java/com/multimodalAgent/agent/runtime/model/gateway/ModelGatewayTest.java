package com.multimodalAgent.agent.runtime.model.gateway;

import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelGatewayTest {

    private static final ModelIdentity IDENTITY = new ModelIdentity("ollama", "mindbridge");
    private static final ModelTimeoutPolicy TIMEOUTS = new ModelTimeoutPolicy(
            Duration.ofMinutes(2), Duration.ofSeconds(30)
    );
    private static final AgentModelRequest REQUEST = new AgentModelRequest(
            List.of(AgentMessage.user("hello")), List.of()
    );

    @Test
    void recordsIdentityIterationLatencyFinishReasonAndKnownUsage() {
        List<ModelInvocationTelemetry> telemetry = new ArrayList<>();
        TokenUsage usage = new TokenUsage(12, 4);
        ModelTurn expected = new ModelTurn(
                ModelFinishReason.STOP, "answer", List.of(), usage
        );
        ModelGateway gateway = gateway(ignored -> expected, telemetry::add, 10L, 60L);

        ModelTurn actual = gateway.generate(REQUEST, 3);

        assertSame(expected, actual);
        assertEquals(1, telemetry.size());
        ModelInvocationTelemetry event = telemetry.get(0);
        assertEquals(IDENTITY, event.identity());
        assertEquals(3, event.iteration());
        assertEquals(Duration.ofNanos(50), event.latency());
        assertEquals(ModelFinishReason.STOP, event.finishReason());
        assertEquals(usage, event.tokenUsage());
        assertTrue(event.succeeded());
        assertNotNull(event.invocationId());
        assertFalse(event.invocationId().isBlank());
    }

    @Test
    void recordsLengthAsASuccessfulInvocationWithoutFailureClassification() {
        List<ModelInvocationTelemetry> telemetry = new ArrayList<>();
        ModelTurn expected = ModelTurn.outputLimit("partial answer");
        ModelGateway gateway = gateway(ignored -> expected, telemetry::add, 10L, 60L);

        ModelTurn actual = gateway.generate(REQUEST, 1);

        assertSame(expected, actual);
        ModelInvocationTelemetry event = telemetry.get(0);
        assertEquals(ModelFinishReason.LENGTH, event.finishReason());
        assertNull(event.failureKind());
        assertTrue(event.succeeded());
    }

    @Test
    void preservesTypedProviderFailureWithoutMessageGuessing() {
        List<ModelInvocationTelemetry> telemetry = new ArrayList<>();
        ModelProviderException providerFailure = new ModelProviderException(
                ModelFailureKind.RATE_LIMITED,
                "arbitrary text that contains no classification hint"
        );
        AgentModel provider = ignored -> {
            throw providerFailure;
        };
        ModelGateway gateway = gateway(provider, telemetry::add, 100L, 125L);

        ModelInvocationException failure = assertThrows(
                ModelInvocationException.class,
                () -> gateway.generate(REQUEST, 1)
        );

        assertEquals(ModelFailureKind.RATE_LIMITED, failure.failureKind());
        assertSame(providerFailure, failure.getCause());
        assertEquals(1, telemetry.size());
        ModelInvocationTelemetry event = telemetry.get(0);
        assertEquals(ModelFailureKind.RATE_LIMITED, event.failureKind());
        assertEquals(TokenUsage.UNKNOWN, event.tokenUsage());
        assertEquals(Duration.ofNanos(25), event.latency());
        assertFalse(event.succeeded());
    }

    @Test
    void classifiesUntypedAdapterFailureAsProviderError() {
        AgentModel provider = ignored -> {
            throw new IllegalStateException("boom");
        };
        ModelGateway gateway = gateway(provider, telemetry -> { }, 1L, 2L);

        ModelInvocationException failure = assertThrows(
                ModelInvocationException.class,
                () -> gateway.generate(REQUEST, 1)
        );

        assertEquals(ModelFailureKind.PROVIDER_ERROR, failure.failureKind());
        assertTrue(failure.getCause() instanceof IllegalStateException);
    }

    @Test
    void passesTheOwnedTimeoutPolicyToTimeoutAwareAdapter() {
        List<ModelTimeoutPolicy> observedPolicies = new ArrayList<>();
        TimeoutAwareAgentModel provider = (request, policy) -> {
            observedPolicies.add(policy);
            return ModelTurn.finalAnswer("ok");
        };
        ModelGateway gateway = gateway(provider, telemetry -> { }, 1L, 2L);

        gateway.generate(REQUEST, 1);

        assertEquals(List.of(TIMEOUTS), observedPolicies);
    }

    @Test
    void telemetrySinkFailureCannotChangeSuccessfulInvocation() {
        ModelGateway gateway = gateway(
                ignored -> ModelTurn.finalAnswer("ok"),
                telemetry -> {
                    throw new IllegalStateException("sink unavailable");
                },
                1L,
                2L
        );

        ModelTurn turn = gateway.generate(REQUEST, 1);

        assertEquals("ok", turn.content());
    }

    private ModelGateway gateway(
            AgentModel provider,
            ModelInvocationTelemetrySink sink,
            long... clockValues
    ) {
        AtomicInteger index = new AtomicInteger();
        LongSupplier clock = () -> clockValues[index.getAndIncrement()];
        return new ModelGateway(provider, IDENTITY, TIMEOUTS, sink, clock);
    }
}
