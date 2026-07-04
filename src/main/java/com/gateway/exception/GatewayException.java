package com.gateway.exception;

import org.springframework.http.HttpStatus;

public class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String errorType;
    private final boolean retryable;

    private GatewayException(HttpStatus status, String errorType, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.errorType = errorType;
        this.retryable = retryable;
    }

    public static GatewayException unknownModel(String model) {
        return new GatewayException(HttpStatus.NOT_FOUND, "not_found",
                "Unknown model: " + model, false);
    }

    public static GatewayException allProvidersFailed(String model) {
        return new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable",
                "All providers failed for model " + model, false);
    }

    public static GatewayException providerError(String message, boolean retryable) {
        HttpStatus status = retryable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST;
        return new GatewayException(status, retryable ? "upstream_error" : "invalid_request", message, retryable);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorType() {
        return errorType;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
