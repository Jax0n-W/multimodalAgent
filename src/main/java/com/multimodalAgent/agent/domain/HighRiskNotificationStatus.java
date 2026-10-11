package com.multimodalAgent.agent.domain;

public enum HighRiskNotificationStatus {
    PENDING,
    CLAIMED,
    PROCESSING,
    DELIVERED,
    FAILED_RETRYABLE,
    UNCERTAIN,
    PERMANENT_FAILURE
}
