package com.multimodalAgent.agent.service.mcp;

/** A delivery failure for which the adapter can prove no external side effect occurred. */
public class DefinitelyNotDeliveredException extends RuntimeException {
    public DefinitelyNotDeliveredException(String message) {
        super(message);
    }
}
