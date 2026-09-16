package com.multimodalAgent.agent.persistence.integration;

import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseFailureKind;
import com.multimodalAgent.agent.coordination.RunLeaseLostException;
import com.multimodalAgent.agent.coordination.RunLeaseSession;
import com.multimodalAgent.agent.coordination.integration.ExecutionCoordinationBoundaryMiddleware;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.ModelCallMetadata;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareFailureException;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionBoundaryMiddlewareOrderingTest {

    @Test
    void coordinationMustBeOutsidePersistenceByOrder() {
        assertEquals(100, ExecutionCoordinationBoundaryMiddleware.ORDER);
        assertEquals(200, ExecutionPersistenceBoundaryMiddleware.ORDER);
    }

    @Test
    void preChecksMustRunCoordinationThenPersistenceThenCore() {
        Fixture fixture = fixture();
        fixture.session.markLost(RunLeaseFailureKind.EXPLICIT_LEASE_LOSS);
        fixture.failures.recordFailure(
                fixture.context.runId(),
                new ExecutionPersistenceException("persistence also failed")
        );
        AtomicInteger coreCalls = new AtomicInteger();

        RuntimeMiddlewareFailureException failure = assertThrows(
                RuntimeMiddlewareFailureException.class,
                () -> fixture.chain.aroundModelCall(
                        fixture.context,
                        new ModelCallMetadata(1, 1),
                        () -> {
                            coreCalls.incrementAndGet();
                            return ModelTurn.finalAnswer("must not run");
                        }
                )
        );

        assertEquals(0, coreCalls.get());
        assertEquals(RunLeaseLostException.class, failure.getCause().getClass());
        fixture.close();
    }

    @Test
    void postChecksMustRunCoreThenPersistenceThenCoordination() {
        Fixture fixture = fixture();
        ExecutionPersistenceException persistenceFailure =
                new ExecutionPersistenceException("persistence post failed");

        RuntimeMiddlewareFailureException failure = assertThrows(
                RuntimeMiddlewareFailureException.class,
                () -> fixture.chain.aroundModelCall(
                        fixture.context,
                        new ModelCallMetadata(1, 1),
                        () -> {
                            fixture.failures.recordFailure(
                                    fixture.context.runId(),
                                    persistenceFailure
                            );
                            fixture.session.markLost(
                                    RunLeaseFailureKind.COORDINATION_UNAVAILABLE
                            );
                            return ModelTurn.finalAnswer("core terminal fact");
                        }
                )
        );

        assertSame(persistenceFailure, failure.getCause());
        fixture.close();
    }

    private Fixture fixture() {
        String runId = "run-order";
        ExecutionPersistenceFailureRegistry failures =
                new ExecutionPersistenceFailureRegistry();
        failures.open(runId);
        RunLeaseSession session = new RunLeaseSession(new RunLease(runId, "token-order"));
        AgentRuntimeContext context = AgentRuntimeContext.minimal(runId, "session-order");
        ExecutionCoordinationBoundaryMiddleware.sessionContributor(session).contribute(context);
        RuntimeMiddlewareChain chain = new RuntimeMiddlewareChain(List.of(
                new ExecutionPersistenceBoundaryMiddleware(failures),
                new ExecutionCoordinationBoundaryMiddleware()
        ));
        return new Fixture(failures, session, context, chain);
    }

    private record Fixture(
            ExecutionPersistenceFailureRegistry failures,
            RunLeaseSession session,
            AgentRuntimeContext context,
            RuntimeMiddlewareChain chain
    ) {
        private void close() {
            failures.close(context.runId());
        }
    }
}
