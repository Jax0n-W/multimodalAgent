package com.multimodalAgent.agent.coordination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLeaseSessionTest {

    @Test
    void shouldStartActiveWithExecutionAuthority() {
        RunLeaseSession session = session();

        assertEquals(RunLeaseState.ACTIVE, session.state());
        assertTrue(session.hasExecutionAuthority());
        assertTrue(session.firstFailure().isEmpty());
        assertDoesNotThrow(session::assertExecutionAuthority);
    }

    @Test
    void shouldMoveFromActiveToLost() {
        RunLeaseSession session = session();

        session.markLost(RunLeaseFailureKind.EXPLICIT_LEASE_LOSS);

        assertEquals(RunLeaseState.LOST, session.state());
        assertFalse(session.hasExecutionAuthority());
    }

    @Test
    void shouldNeverRestoreAOnceLostSessionToActive() {
        RunLeaseSession session = session();

        session.markLost(RunLeaseFailureKind.EXPLICIT_LEASE_LOSS);
        session.markLost(RunLeaseFailureKind.COORDINATION_UNAVAILABLE);

        assertEquals(RunLeaseState.LOST, session.state());
        assertFalse(session.hasExecutionAuthority());
    }

    @Test
    void shouldMoveFromActiveToClosing() {
        RunLeaseSession session = session();

        session.beginClosing();

        assertEquals(RunLeaseState.CLOSING, session.state());
    }

    @Test
    void shouldMoveFromLostToClosing() {
        RunLeaseSession session = session();
        session.markLost(RunLeaseFailureKind.EXPLICIT_LEASE_LOSS);

        session.beginClosing();

        assertEquals(RunLeaseState.CLOSING, session.state());
    }

    @Test
    void shouldMoveFromClosingToClosed() {
        RunLeaseSession session = session();
        session.beginClosing();

        session.close();

        assertEquals(RunLeaseState.CLOSED, session.state());
    }

    @Test
    void shouldRejectTransitionsOutOfClosed() {
        RunLeaseSession session = session();
        session.beginClosing();
        session.close();

        assertThrows(
                IllegalStateException.class,
                () -> session.markLost(RunLeaseFailureKind.EXPLICIT_LEASE_LOSS)
        );
        assertThrows(IllegalStateException.class, session::beginClosing);
        assertThrows(IllegalStateException.class, session::close);
        assertEquals(RunLeaseState.CLOSED, session.state());
    }

    @Test
    void shouldGrantExecutionAuthorityOnlyWhileActive() {
        RunLeaseSession active = session();
        RunLeaseSession closing = session();
        closing.beginClosing();
        RunLeaseSession closed = session();
        closed.beginClosing();
        closed.close();

        assertDoesNotThrow(active::assertExecutionAuthority);
        assertThrows(ExecutionCoordinationException.class, closing::assertExecutionAuthority);
        assertThrows(ExecutionCoordinationException.class, closed::assertExecutionAuthority);
    }

    @Test
    void shouldExposeExplicitLeaseLossAsItsOwnException() {
        RunLeaseSession session = session();
        session.markLost(RunLeaseFailureKind.EXPLICIT_LEASE_LOSS);

        RunLeaseLostException exception = assertThrows(
                RunLeaseLostException.class,
                session::assertExecutionAuthority
        );

        assertEquals("run-1", exception.runId());
    }

    @Test
    void shouldExposeCoordinationUnavailabilityAsItsOwnException() {
        RunLeaseSession session = session();
        session.markLost(RunLeaseFailureKind.COORDINATION_UNAVAILABLE);

        CoordinationUnavailableException exception = assertThrows(
                CoordinationUnavailableException.class,
                session::assertExecutionAuthority
        );

        assertEquals("run-1", exception.runId());
    }

    @Test
    void shouldPreserveTheFirstCoordinationFailure() {
        RunLeaseSession session = session();

        session.markLost(RunLeaseFailureKind.EXPLICIT_LEASE_LOSS);
        session.markLost(RunLeaseFailureKind.COORDINATION_UNAVAILABLE);

        assertEquals(
                RunLeaseFailureKind.EXPLICIT_LEASE_LOSS,
                session.firstFailure().orElseThrow()
        );
        assertThrows(RunLeaseLostException.class, session::assertExecutionAuthority);
    }

    @Test
    void shouldRejectInvalidLifecycleTransitions() {
        RunLeaseSession active = session();
        RunLeaseSession lost = session();
        lost.markLost(RunLeaseFailureKind.EXPLICIT_LEASE_LOSS);

        assertThrows(IllegalStateException.class, active::close);
        assertThrows(IllegalStateException.class, lost::close);
    }

    private RunLeaseSession session() {
        return new RunLeaseSession(new RunLease("run-1", "token-1"));
    }
}
