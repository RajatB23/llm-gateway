package com.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.providers.digitalocean")
public record DigitalOceanProviderProperties(String baseUrl, String apiKey) {
}
