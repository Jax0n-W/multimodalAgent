package com.multimodalAgent.agent.service.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.access.AccessDeniedException;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeRetrievalFailureClassifierTest {

    @Test
    void classifiesKnownTimeoutAndBackendFailures() {
        assertEquals(
                KnowledgeRetrievalFailureClassifier.ReasonCode.TIMEOUT,
                KnowledgeRetrievalFailureClassifier.classify(
                        new QueryTimeoutException("retrieval timed out")
                ).orElseThrow()
        );
        assertEquals(
                KnowledgeRetrievalFailureClassifier.ReasonCode.BACKEND_UNAVAILABLE,
                KnowledgeRetrievalFailureClassifier.classify(
                        new IllegalStateException("backend unavailable", new ConnectException())
                ).orElseThrow()
        );
    }

    @Test
    void securityAndIntegrityFailuresAlwaysFailClosed() {
        assertTrue(KnowledgeRetrievalFailureClassifier.classify(
                new AccessDeniedException(
                        "denied",
                        new QueryTimeoutException("misleading nested timeout")
                )
        ).isEmpty());
        assertTrue(KnowledgeRetrievalFailureClassifier.classify(
                new DataIntegrityViolationException(
                        "corrupt",
                        new ConnectException("misleading nested connection failure")
                )
        ).isEmpty());
    }

    @Test
    void unexpectedProgrammingFailureIsNotRecoverable() {
        assertTrue(KnowledgeRetrievalFailureClassifier.classify(
                new NullPointerException("bug")
        ).isEmpty());
    }
}
