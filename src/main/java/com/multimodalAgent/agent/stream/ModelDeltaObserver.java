package com.multimodalAgent.agent.stream;

/**
 * Observation-only sink for provider text deltas.
 *
 * <p>The provider adapter isolates observer failures from model execution truth. P8.3 may bridge
 * this contract to a run-scoped stream sequencer; P8.2 does not assign stream sequence numbers.</p>
 */
@FunctionalInterface
public interface ModelDeltaObserver {

    ModelDeltaObserver NOOP = delta -> {
    };

    void onDelta(ModelDelta delta);
}
