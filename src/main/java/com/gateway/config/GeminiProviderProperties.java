package com.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.providers.gemini")
public record GeminiProviderProperties(String baseUrl, String apiKey) {
}
