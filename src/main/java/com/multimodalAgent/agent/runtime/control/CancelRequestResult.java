package com.multimodalAgent.agent.runtime.control;

/**
 * Outcome exposed by a cancellation request boundary.
 *
 * <p>The in-memory {@link ExecutionControl} can produce {@link #ACCEPTED} and
 * {@link #ALREADY_REQUESTED}. A future active-execution registry owns the distinction between
 * {@link #ALREADY_TERMINAL} and {@link #NOT_ACTIVE}.</p>
 */
public enum CancelRequestResult {
    ACCEPTED,
    ALREADY_REQUESTED,
    ALREADY_TERMINAL,
    NOT_ACTIVE
}
