package com.multimodalAgent.agent.runtime.model;

/** Shared wire marker for non-authoritative model-visible data. */
public final class UntrustedEvidenceProtocol {

    public static final String ENVELOPE_PREFIX = "UNTRUSTED_EVIDENCE_DATA_V1\n";

    private UntrustedEvidenceProtocol() {
    }
}
