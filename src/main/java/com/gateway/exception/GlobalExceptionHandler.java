package com.gateway.exception;

import com.gateway.dto.ErrorResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleGatewayException(GatewayException ex, ServerWebExchange exchange) {
        String requestId = MDC.get("requestId");
        ErrorResponse body = ErrorResponse.of(ex.getErrorType(), ex.getMessage(), requestId);
        return Mono.just(ResponseEntity.status(ex.getStatus()).body(body));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleValidation(WebExchangeBindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        String requestId = MDC.get("requestId");
        ErrorResponse body = ErrorResponse.of("invalid_request", message, requestId);
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ErrorResponse>> handleGeneric(Exception ex) {
        String requestId = MDC.get("requestId");
        ErrorResponse body = ErrorResponse.of("internal_error", "An unexpected error occurred", requestId);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body));
    }
}
