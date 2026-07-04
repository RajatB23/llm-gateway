package com.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        ErrorBody error
) {
    public static ErrorResponse of(String type, String message, String requestId) {
        return new ErrorResponse(new ErrorBody(type, message, requestId));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(
            String type,
            String message,
            @JsonProperty("request_id") String requestId
    ) {
    }
}
