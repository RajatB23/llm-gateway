package com.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.providers.openai")
public record OpenAiProviderProperties(String baseUrl, String apiKey) {
}
