package com.multimodalAgent.agent.streaming;

import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingClient;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiStreamEvent;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunPhase;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.control.ExecutionControlState;
import com.multimodalAgent.agent.runtime.event.RunStoppedEvent;
import com.multimodalAgent.agent.stream.ControlEvent;
import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.stream.RuntimeEventPayload;
import com.multimodalAgent.agent.streaming.integration.LocalExecutionControlRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p84-production;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "multimodal-agent.ai.provider=ollama",
        "multimodal-agent.runtime.enabled=true",
        "multimodal-agent.knowledge.use-chroma=false"
})
@AutoConfigureWebTestClient
class ProductionCancellationIntegrationTest {

    @Autowired private WebTestClient client;
    @Autowired private UserAccountRepository users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private AgentRunRepository runs;
    @Autowired private ExecutionStreamHub hub;
    @Autowired private LocalExecutionControlRegistry controls;
    @MockBean private OpenAiCompatibleStreamingClient provider;

    @Test
    void durableButLocallyInactiveRunAndWaitingApprovalReturnConflict() {
        UserAccount owner = user("p84-inactive-");
        for (AgentRunStatus status : List.of(
                AgentRunStatus.RUNNING, AgentRunStatus.WAITING_APPROVAL
        )) {
            String runId = "p84-inactive-" + UUID.randomUUID();
            runs.saveAndFlush(new AgentRunEntity(
                    runId,
                    "request-" + UUID.randomUUID(),
                    owner.getId(),
                    "session-" + UUID.randomUUID(),
                    status,
                    AgentRunPhase.RECEIVED
            ));
            client.post().uri("/api/agent/runs/{runId}/cancel", runId)
                    .headers(headers -> headers.setBasicAuth(owner.getUsername(), "p84-password"))
                    .exchange().expectStatus().isEqualTo(409);
            assertEquals(status, runs.findByRunId(runId).orElseThrow().getStatus());
        }
        assertEquals(0, controls.activeCount());
    }

    @Test
    void authenticatedOwnerCancelsAfterDurableAdmissionAndPersistenceRecordsCancelled()
            throws Exception {
        UserAccount owner = user("p84-owner-");
        UserAccount other = user("p84-other-");
        String runId = "p84-" + UUID.randomUUID();
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(provider.stream(any())).thenReturn(Flux.defer(() -> {
            providerEntered.countDown();
            try {
                if (!releaseProvider.await(20, TimeUnit.SECONDS)) {
                    throw new AssertionError("Provider was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return Flux.just(
                    textChunk("completed model work", OpenAiApi.ChatCompletionFinishReason.STOP),
                    OpenAiStreamEvent.Done.INSTANCE
            );
        }));

        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> client.post()
                .uri("/api/agent/runs")
                .headers(headers -> headers.setBasicAuth(owner.getUsername(), "p84-password"))
                .bodyValue(Map.of("runId", runId, "message", "hello"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.stopReason").isEqualTo("CANCELLED"));
        ExecutionStreamSubscription subscription = null;
        Disposable receiver = null;
        try {
            assertTrue(providerEntered.await(10, TimeUnit.SECONDS));
            assertTrue(runs.existsByRunIdAndUserId(runId, owner.getId()));
            assertEquals(1, controls.activeCount());
            assertTrue(hub.isOpen(runId));

            subscription = hub.subscribe(runId);
            BlockingQueue<ExecutionStreamEvent> queue = new LinkedBlockingQueue<>();
            receiver = subscription.events().subscribe(queue::offer);

            client.post().uri("/api/agent/runs/{runId}/cancel", runId)
                    .exchange().expectStatus().isUnauthorized();
            client.post().uri("/api/agent/runs/{runId}/cancel", runId)
                    .headers(headers -> headers.setBasicAuth(other.getUsername(), "p84-password"))
                    .exchange().expectStatus().isNotFound();
            client.post().uri("/api/agent/runs/{runId}/cancel", "unknown-" + UUID.randomUUID())
                    .headers(headers -> headers.setBasicAuth(owner.getUsername(), "p84-password"))
                    .exchange().expectStatus().isNotFound();

            client.post().uri("/api/agent/runs/{runId}/cancel", runId)
                    .headers(headers -> headers.setBasicAuth(owner.getUsername(), "p84-password"))
                    .exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.result").isEqualTo("ACCEPTED");
            client.post().uri("/api/agent/runs/{runId}/cancel", runId)
                    .headers(headers -> headers.setBasicAuth(owner.getUsername(), "p84-password"))
                    .exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.result").isEqualTo("ALREADY_REQUESTED");

            releaseProvider.countDown();
            running.get(20, TimeUnit.SECONDS);
            assertEquals(AgentRunStatus.CANCELLED,
                    runs.findByRunId(runId).orElseThrow().getStatus());
            assertEquals(AgentStopReason.CANCELLED,
                    runs.findByRunId(runId).orElseThrow().getStopReason());
            assertEquals(0, controls.activeCount());
            assertFalse(hub.isOpen(runId));

            List<ExecutionStreamEvent> observed = takeUntilStopped(queue);
            List<ControlEvent> controlEvents = observed.stream()
                    .filter(event -> event.payload() instanceof ControlEvent)
                    .map(event -> (ControlEvent) event.payload()).toList();
            assertEquals(List.of(new ControlEvent(ExecutionControlState.CANCEL_REQUESTED)),
                    controlEvents);
            assertTrue(observed.stream().anyMatch(event ->
                    event.payload() instanceof RuntimeEventPayload payload
                            && payload.event() instanceof RunStoppedEvent stopped
                            && stopped.stopReason() == AgentStopReason.CANCELLED));
            for (int index = 1; index < observed.size(); index++) {
                assertTrue(observed.get(index).streamSequence()
                        > observed.get(index - 1).streamSequence());
            }

            client.post().uri("/api/agent/runs/{runId}/cancel", runId)
                    .headers(headers -> headers.setBasicAuth(owner.getUsername(), "p84-password"))
                    .exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.result").isEqualTo("ALREADY_TERMINAL");
        } finally {
            releaseProvider.countDown();
            if (receiver != null) {
                receiver.dispose();
            }
            if (subscription != null) {
                subscription.close();
            }
        }
    }

    private UserAccount user(String prefix) {
        UserAccount user = new UserAccount();
        user.setUsername(prefix + UUID.randomUUID());
        user.setDisplayName(user.getUsername());
        user.setPassword(passwords.encode("p84-password"));
        user.setRoles(Set.of("ROLE_USER"));
        return users.save(user);
    }

    private List<ExecutionStreamEvent> takeUntilStopped(
            BlockingQueue<ExecutionStreamEvent> queue
    ) throws InterruptedException {
        List<ExecutionStreamEvent> events = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            ExecutionStreamEvent event = queue.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            events.add(event);
            if (event.payload() instanceof RuntimeEventPayload payload
                    && payload.event() instanceof RunStoppedEvent) {
                return events;
            }
        }
        throw new AssertionError("RUN_STOPPED was not observed");
    }

    private OpenAiStreamEvent textChunk(
            String content,
            OpenAiApi.ChatCompletionFinishReason finishReason
    ) {
        OpenAiApi.ChatCompletionMessage message = new OpenAiApi.ChatCompletionMessage(
                content, OpenAiApi.ChatCompletionMessage.Role.ASSISTANT
        );
        OpenAiApi.ChatCompletionChunk.ChunkChoice choice =
                new OpenAiApi.ChatCompletionChunk.ChunkChoice(
                        finishReason, 0, message, null
                );
        return new OpenAiStreamEvent.Chunk(new OpenAiApi.ChatCompletionChunk(
                "response-p84", List.of(choice), 1L, "test-model",
                null, null, "chat.completion.chunk", null
        ));
    }
}
