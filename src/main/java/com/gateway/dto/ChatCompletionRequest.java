package com.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionRequest(
        @NotBlank String model,
        @NotEmpty @Valid List<Message> messages,
        Boolean stream,
        Float temperature,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("top_p") Float topP
) {
    public boolean isStreaming() {
        return Boolean.TRUE.equals(stream);
    }
}
