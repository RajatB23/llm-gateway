package com.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.providers.anthropic")
public record AnthropicProviderProperties(String baseUrl, String apiKey) {
}
