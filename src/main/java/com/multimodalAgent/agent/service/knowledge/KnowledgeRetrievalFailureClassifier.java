package com.multimodalAgent.agent.service.knowledge;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** Classifies only known operational retrieval failures that are safe to degrade for HIGH risk. */
public final class KnowledgeRetrievalFailureClassifier {

    public enum ReasonCode {
        TIMEOUT,
        BACKEND_UNAVAILABLE,
        TRANSIENT_DATA_ACCESS
    }

    private KnowledgeRetrievalFailureClassifier() {
    }

    public static Optional<ReasonCode> classify(Throwable failure) {
        if (failure == null) {
            return Optional.empty();
        }
        Set<Throwable> chain = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && chain.add(current);
             current = current.getCause()) {
            if (isSecurityOrIntegrityFailure(current)) {
                return Optional.empty();
            }
        }
        chain.clear();
        for (Throwable current = failure; current != null && chain.add(current);
             current = current.getCause()) {
            Optional<ReasonCode> reason = operationalReason(current);
            if (reason.isPresent()) {
                return reason;
            }
        }
        return Optional.empty();
    }

    private static boolean isSecurityOrIntegrityFailure(Throwable failure) {
        return failure instanceof AccessDeniedException
                || failure instanceof AuthenticationException
                || failure instanceof PermissionDeniedDataAccessException
                || failure instanceof DataIntegrityViolationException
                || failure instanceof ConstraintViolationException;
    }

    private static Optional<ReasonCode> operationalReason(Throwable failure) {
        if (failure instanceof QueryTimeoutException
                || failure instanceof TimeoutException
                || failure instanceof SocketTimeoutException) {
            return Optional.of(ReasonCode.TIMEOUT);
        }
        if (failure instanceof TransientDataAccessException
                || failure instanceof RecoverableDataAccessException) {
            return Optional.of(ReasonCode.TRANSIENT_DATA_ACCESS);
        }
        if (failure instanceof DataAccessResourceFailureException
                || failure instanceof WebClientRequestException
                || failure instanceof ConnectException
                || failure instanceof NoRouteToHostException) {
            return Optional.of(ReasonCode.BACKEND_UNAVAILABLE);
        }
        if (failure instanceof WebClientResponseException response
                && (response.getStatusCode().is5xxServerError()
                || response.getStatusCode().value() == 429)) {
            return Optional.of(ReasonCode.BACKEND_UNAVAILABLE);
        }
        return Optional.empty();
    }
}
