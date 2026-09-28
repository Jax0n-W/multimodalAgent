package com.multimodalAgent.agent.recovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable identity derived from the logical checkpoint position, never from randomness. */
public final class RecoveryCheckpointIds {

    private RecoveryCheckpointIds() {
    }

    public static String stable(
            String runId,
            long sequence,
            RecoveryCheckpointBoundary boundary,
            int iteration,
            String discriminator
    ) {
        String material = "recovery-checkpoint\u0000" + runId + "\u0000" + sequence
                + "\u0000" + boundary.name() + "\u0000" + iteration
                + "\u0000" + (discriminator == null ? "" : discriminator);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "rcp-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }
}
