package com.multimodalAgent.agent.streaming;

import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunPhase;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.streaming.integration.DistributedCancellationUnavailableException;
import com.multimodalAgent.agent.streaming.integration.RemoteCancellationDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p85-api;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "multimodal-agent.ai.provider=ollama",
        "multimodal-agent.runtime.enabled=true",
        "multimodal-agent.knowledge.use-chroma=false"
})
@AutoConfigureWebTestClient
class ProductionDistributedCancellationApiTest {

    @Autowired private WebTestClient client;
    @Autowired private UserAccountRepository users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private AgentRunRepository runs;
    @MockBean private RemoteCancellationDispatcher remote;

    @Test
    void onlyDurableOwnerCanBroadcastAndRemoteFailureMapsTo503() {
        UserAccount owner = user("p85-owner-");
        UserAccount other = user("p85-other-");
        String runId = run(owner, AgentRunStatus.RUNNING);

        client.post().uri("/api/agent/runs/{runId}/cancel", runId)
                .exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/agent/runs/{runId}/cancel", runId)
                .headers(headers -> headers.setBasicAuth(other.getUsername(), "p85-password"))
                .exchange().expectStatus().isNotFound();
        client.post().uri("/api/agent/runs/{runId}/cancel", "missing-" + UUID.randomUUID())
                .headers(headers -> headers.setBasicAuth(owner.getUsername(), "p85-password"))
                .exchange().expectStatus().isNotFound();
        String waitingRun = run(owner, AgentRunStatus.WAITING_APPROVAL);
        client.post().uri("/api/agent/runs/{runId}/cancel", waitingRun)
                .headers(headers -> headers.setBasicAuth(owner.getUsername(), "p85-password"))
                .exchange().expectStatus().isEqualTo(409);
        verifyNoInteractions(remote);

        when(remote.dispatch(runId))
                .thenThrow(new DistributedCancellationUnavailableException("Redis down"));
        client.post().uri("/api/agent/runs/{runId}/cancel", runId)
                .headers(headers -> headers.setBasicAuth(owner.getUsername(), "p85-password"))
                .exchange().expectStatus().isEqualTo(503);
        verify(remote).dispatch(runId);
        verify(remote, never()).dispatch(waitingRun);
        assertEquals(AgentRunStatus.RUNNING,
                runs.findByRunId(runId).orElseThrow().getStatus());
    }

    @Test
    void ackTimeoutWhileDurablyRunningMapsTo503() {
        UserAccount owner = user("p85-timeout-");
        String runId = run(owner, AgentRunStatus.RUNNING);
        when(remote.dispatch(runId)).thenReturn(Optional.empty());
        client.post().uri("/api/agent/runs/{runId}/cancel", runId)
                .headers(headers -> headers.setBasicAuth(owner.getUsername(), "p85-password"))
                .exchange().expectStatus().isEqualTo(503);
        verify(remote).dispatch(runId);
    }

    private UserAccount user(String prefix) {
        UserAccount user = new UserAccount();
        user.setUsername(prefix + UUID.randomUUID());
        user.setDisplayName(user.getUsername());
        user.setPassword(passwords.encode("p85-password"));
        user.setRoles(Set.of("ROLE_USER"));
        return users.save(user);
    }

    private String run(UserAccount owner, AgentRunStatus status) {
        String runId = "p85-" + UUID.randomUUID();
        runs.saveAndFlush(new AgentRunEntity(
                runId, "request-" + UUID.randomUUID(), owner.getId(),
                "session-" + UUID.randomUUID(), status, AgentRunPhase.RECEIVED
        ));
        return runId;
    }
}
