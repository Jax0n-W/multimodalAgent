package com.multimodalAgent.agent.recovery;

public final class ToolRecoveryContractConflictException
        extends ToolRecoveryContractBindingException {

    public ToolRecoveryContractConflictException(String runId, String toolCallId) {
        super("Tool recovery contract conflict for " + runId + "/" + toolCallId);
    }
}
