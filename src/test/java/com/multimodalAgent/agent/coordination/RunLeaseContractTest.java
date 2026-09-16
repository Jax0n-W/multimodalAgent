package com.multimodalAgent.agent.coordination;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunLeaseContractTest {

    @Test
    void shouldRejectNullOrBlankRunId() {
        assertThrows(IllegalArgumentException.class, () -> new RunLease(null, "token-1"));
        assertThrows(IllegalArgumentException.class, () -> new RunLease(" ", "token-1"));
    }

    @Test
    void shouldRejectNullOrBlankLeaseToken() {
        assertThrows(IllegalArgumentException.class, () -> new RunLease("run-1", null));
        assertThrows(IllegalArgumentException.class, () -> new RunLease("run-1", " "));
    }

    @Test
    void shouldKeepLeaseTokenDistinctFromRunIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new RunLease("run-1", "run-1"));

        RunLease lease = new RunLease("run-1", "token-1");

        assertEquals("run-1", lease.runId());
        assertEquals("token-1", lease.leaseToken());
    }

    @Test
    void shouldFreezeTheFourLeaseStates() {
        Set<RunLeaseState> states = Arrays.stream(RunLeaseState.values())
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(
                Set.of(
                        RunLeaseState.ACTIVE,
                        RunLeaseState.LOST,
                        RunLeaseState.CLOSING,
                        RunLeaseState.CLOSED
                ),
                states
        );
    }

    @Test
    void shouldRepresentAllAcquireOutcomesWithoutBooleanAmbiguity() {
        RunLease lease = new RunLease("run-1", "token-1");

        RunLeaseAcquireResult acquired = new RunLeaseAcquireResult.Acquired(lease);
        RunLeaseAcquireResult active = new RunLeaseAcquireResult.AlreadyActive("run-1");
        RunLeaseAcquireResult unavailable = new RunLeaseAcquireResult.Unavailable("run-1");

        assertEquals(lease, assertInstanceOf(
                RunLeaseAcquireResult.Acquired.class,
                acquired
        ).lease());
        assertEquals("run-1", assertInstanceOf(
                RunLeaseAcquireResult.AlreadyActive.class,
                active
        ).runId());
        assertEquals("run-1", assertInstanceOf(
                RunLeaseAcquireResult.Unavailable.class,
                unavailable
        ).runId());
    }

    @Test
    void shouldRepresentRenewOutcomesWithoutBooleanAmbiguity() {
        assertEquals(
                Set.of(
                        RunLeaseRenewResult.RENEWED,
                        RunLeaseRenewResult.EXPLICIT_LEASE_LOSS,
                        RunLeaseRenewResult.COORDINATION_UNAVAILABLE
                ),
                Set.of(RunLeaseRenewResult.values())
        );
    }

    @Test
    void shouldRepresentReleaseOutcomesWithoutBooleanAmbiguity() {
        assertEquals(
                Set.of(
                        RunLeaseReleaseResult.RELEASED,
                        RunLeaseReleaseResult.NO_LONGER_OWNER,
                        RunLeaseReleaseResult.COORDINATION_UNAVAILABLE
                ),
                Set.of(RunLeaseReleaseResult.values())
        );
    }
}
