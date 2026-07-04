package com.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionChunk(
        String id,
        String object,
        long created,
        String model,
        List<ChunkChoice> choices
) {
    public static ChatCompletionChunk ofContent(String id, String model, String content) {
        return new ChatCompletionChunk(
                id,
                "chat.completion.chunk",
                System.currentTimeMillis() / 1000,
                model,
                List.of(new ChunkChoice(0, new Delta(content, null), null))
        );
    }

    public static ChatCompletionChunk ofFinish(String id, String model, String finishReason) {
        return new ChatCompletionChunk(
                id,
                "chat.completion.chunk",
                System.currentTimeMillis() / 1000,
                model,
                List.of(new ChunkChoice(0, new Delta(null, null), finishReason))
        );
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkChoice(
            int index,
            Delta delta,
            @JsonProperty("finish_reason") String finishReason
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(
            String content,
            String role
    ) {
    }
}
