package com.multimodalAgent.agent.stream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P8ContractDocumentationTest {

    private static final Path ADR = Path.of(
            "docs",
            "adr",
            "ADR-007-unified-streaming-execution-control.md"
    );

    @Test
    void freezesOneRunScopedStreamSequenceAuthority() throws IOException {
        String contract = contract();

        assertTrue(contract.contains("""
                One live execution stream for one runId has exactly one streamSequence authority.
                Individual RuntimeEvent, ModelDelta, and ControlEvent producers do not own independent sequence spaces.
                """.strip()));
    }

    @Test
    void separatesCoreTruthFromCallerVisibleInfrastructureOutcome() throws IOException {
        String contract = contract();

        assertTrue(contract.contains("""
                Failure precedence is phase-aware, not a global ranking.
                Core execution truth and caller-visible infrastructure outcome are distinct layers.
                """.strip()));
        assertTrue(contract.contains("""
                Caller-visible infrastructure failure precedence remains governed by the
                frozen P6/P7 execution-shell contracts until the outer execution lifecycle has completed.
                """.strip()));
        assertTrue(contract.contains("""
                Once a Core terminal fact has been established, no later persistence, coordination, streaming, or
                control observation may emit or synthesize a different Core terminal fact.
                """.strip()));
    }

    @Test
    void preservesP7FailureClassificationsWithoutConflatingStopAndRelease() throws IOException {
        String contract = contract();

        assertTrue(contract.contains("""
                Failures already classified by P7 as terminal cleanup diagnostics remain diagnostic-only.
                """.strip()));
        assertTrue(contract.contains("""
                P8 does not reclassify P7 watchdog-stop, coordination-boundary, or execution-shell failures.
                """.strip()));
        assertTrue(contract.contains("""
                Its existing P7 failure behavior therefore remains distinct from a
                Redis release failure and is not declared diagnostic-only by this ADR.
                """.strip()));
    }

    @Test
    void definesWaitingApprovalAsCurrentInvocationOutcome() throws IOException {
        String contract = contract();

        assertTrue(contract.contains("""
                `RUN_WAITING_APPROVAL` is a stable outcome of the current invocation or execution segment. It is
                not a declaration that the durable Run is permanently terminal or can never continue.
                """.strip()));
    }

    @Test
    void rejectsTheSupersededGlobalContracts() throws IOException {
        String contract = contract();

        assertFalse(contract.contains("Within one live publisher for one Run"));
        assertFalse(contract.contains("> cancellation outcome"));
        assertFalse(contract.contains("A later Redis release or\n"
                + "watchdog-cleanup failure follows the frozen P7 terminal-cleanup contract"));
    }

    private String contract() throws IOException {
        return Files.readString(ADR).replace("\r\n", "\n");
    }
}
