package com.multimodalAgent.agent.eval;

/** Typed refusal to compare runs whose dataset contracts are not identical. */
public final class IncompatibleEvalBaselineException extends IllegalArgumentException {

    public static final String CODE = "INCOMPATIBLE_BASELINE";

    public IncompatibleEvalBaselineException(String detail) {
        super(CODE + ": " + detail);
    }

    public String code() {
        return CODE;
    }
}
