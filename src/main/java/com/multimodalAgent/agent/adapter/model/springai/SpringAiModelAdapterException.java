package com.multimodalAgent.agent.adapter.model.springai;

import com.multimodalAgent.agent.runtime.model.gateway.ModelFailureKind;
import com.multimodalAgent.agent.runtime.model.gateway.ModelProviderException;

public class SpringAiModelAdapterException extends ModelProviderException {

    public SpringAiModelAdapterException(String message) {
        this(ModelFailureKind.PROVIDER_ERROR, message);
    }

    public SpringAiModelAdapterException(String message, Throwable cause) {
        this(ModelFailureKind.PROVIDER_ERROR, message, cause);
    }

    public SpringAiModelAdapterException(ModelFailureKind kind, String message) {
        super(kind, message);
    }

    public SpringAiModelAdapterException(
            ModelFailureKind kind,
            String message,
            Throwable cause
    ) {
        super(kind, message, cause);
    }
}
