package com.multimodalAgent.agent.streaming.integration;

import com.multimodalAgent.agent.runtime.control.CancelRequestResult;

import java.util.Optional;

/** Delivers a command to a remote active execution; an empty result means ACK timeout. */
public interface RemoteCancellationDispatcher {

    Optional<CancelRequestResult> dispatch(String runId);
}
