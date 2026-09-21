package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.runtime.control.CancelRequestResult;

import java.util.Objects;

public record CancelRunResponse(CancelRequestResult result) {

    public CancelRunResponse {
        Objects.requireNonNull(result, "result must not be null");
    }
}
