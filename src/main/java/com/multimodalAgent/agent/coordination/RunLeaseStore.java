package com.multimodalAgent.agent.coordination;

/**
 * Port for ephemeral active-run ownership. Implementations must compare the lease token when
 * renewing and releasing, but no Redis-specific type is part of this contract.
 */
public interface RunLeaseStore {

    RunLeaseAcquireResult tryAcquire(String runId);

    RunLeaseRenewResult renew(RunLease lease);

    RunLeaseReleaseResult release(RunLease lease);
}
