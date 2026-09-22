package com.multimodalAgent.agent.streaming.integration;

import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunPhase;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.runtime.control.CancelRequestResult;
import com.multimodalAgent.agent.streaming.ExecutionStreamHub;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DistributedRunCancellationServiceTest {

    private static final String RUN_ID = "remote-run";
    private static final long OWNER_ID = 7L;

    @Test
    void authorizationPrecedesDispatchAndWaitingApprovalNeverBroadcasts() {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AtomicInteger broadcasts = new AtomicInteger();
        RemoteCancellationDispatcher remote = runId -> {
            broadcasts.incrementAndGet();
            return Optional.of(CancelRequestResult.ACCEPTED);
        };
        LocalRunCancellationService service = service(runs, remote);
        assertTrue(service.cancel("unknown", OWNER_ID).isEmpty());
        assertTrue(service.cancel(RUN_ID, OWNER_ID + 1).isEmpty());
        AgentRunEntity approval = run(AgentRunStatus.WAITING_APPROVAL);
        when(runs.findByRunIdAndUserId(RUN_ID, OWNER_ID)).thenReturn(Optional.of(approval));
        assertEquals(CancelRequestResult.NOT_ACTIVE,
                service.cancel(RUN_ID, OWNER_ID).orElseThrow());
        assertEquals(0, broadcasts.get());
    }

    @Test
    void localFastPathWorksEvenWhenRemoteRedisIsUnavailable() {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        when(runs.findByRunIdAndUserId(RUN_ID, OWNER_ID))
                .thenReturn(Optional.of(run(AgentRunStatus.RUNNING)));
        ExecutionStreamHub hub = new ExecutionStreamHub();
        LocalExecutionControlRegistry controls = new LocalExecutionControlRegistry(
                new ExecutionStreamPublisher(hub)
        );
        LocalExecutionControlRegistry.Entry entry = controls.create(RUN_ID);
        controls.register(entry);
        AtomicInteger broadcasts = new AtomicInteger();
        LocalRunCancellationService service = new LocalRunCancellationService(
                runs, controls, runId -> {
                    broadcasts.incrementAndGet();
                    throw new DistributedCancellationUnavailableException("Redis down");
                }
        );
        try {
            assertEquals(CancelRequestResult.ACCEPTED,
                    service.cancel(RUN_ID, OWNER_ID).orElseThrow());
            assertEquals(0, broadcasts.get());
        } finally {
            controls.close(entry);
        }
    }

    @Test
    void remoteRedisFailureIsUnavailableNotAcceptedOrInactive() {
        AgentRunRepository runs = ownedRun(AgentRunStatus.RUNNING);
        LocalRunCancellationService service = service(runs, runId -> {
            throw new DistributedCancellationUnavailableException("Redis down");
        });
        assertThrows(DistributedCancellationUnavailableException.class,
                () -> service.cancel(RUN_ID, OWNER_ID));
    }

    @Test
    void ackTimeoutRechecksDurableTerminalBeforeReturning() {
        AgentRunEntity run = run(AgentRunStatus.RUNNING);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        when(runs.findByRunIdAndUserId(RUN_ID, OWNER_ID)).thenAnswer(invocation ->
                Optional.of(run));
        LocalRunCancellationService service = service(runs, runId -> {
            run.setStatus(AgentRunStatus.COMPLETED);
            return Optional.empty();
        });
        assertEquals(CancelRequestResult.ALREADY_TERMINAL,
                service.cancel(RUN_ID, OWNER_ID).orElseThrow());
    }

    @Test
    void ackTimeoutWhileStillRunningIsUncertain() {
        LocalRunCancellationService service = service(
                ownedRun(AgentRunStatus.RUNNING), runId -> Optional.empty()
        );
        assertThrows(DistributedCancellationUnavailableException.class,
                () -> service.cancel(RUN_ID, OWNER_ID));
    }

    private LocalRunCancellationService service(
            AgentRunRepository runs,
            RemoteCancellationDispatcher remote
    ) {
        return new LocalRunCancellationService(
                runs,
                new LocalExecutionControlRegistry(
                        new ExecutionStreamPublisher(new ExecutionStreamHub())
                ),
                remote
        );
    }

    private AgentRunRepository ownedRun(AgentRunStatus status) {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        when(runs.findByRunIdAndUserId(RUN_ID, OWNER_ID))
                .thenReturn(Optional.of(run(status)));
        return runs;
    }

    private AgentRunEntity run(AgentRunStatus status) {
        return new AgentRunEntity(
                RUN_ID, "request-remote", OWNER_ID, "session-remote", status,
                AgentRunPhase.RECEIVED
        );
    }
}
