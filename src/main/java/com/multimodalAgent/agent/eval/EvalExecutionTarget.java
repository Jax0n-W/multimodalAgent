package com.multimodalAgent.agent.eval;

/** Thin boundary for evaluating the existing Runtime or a future legacy execution path. */
public interface EvalExecutionTarget {

    String targetName();

    EvalObservation execute(EvalCase evalCase);
}
