package com.multimodalAgent.agent.runtime.control;

/**
 * Infrastructure-neutral terminal decision seam for a cooperative cancellation context.
 * The intent state remains separate from the local execution lifecycle implementing this port.
 */
public interface RuntimeCancellationBoundary extends ExecutionControl {

    /**
     * Atomically decides normal completion against a concurrent cancellation request.
     * A false result means cancellation won and the Core must stop with CANCELLED instead.
     */
    boolean trySealNormalCompletion();

    /** Seals a non-normal Core terminal decision without rewriting its established cause. */
    void sealCoreTerminal();
}
