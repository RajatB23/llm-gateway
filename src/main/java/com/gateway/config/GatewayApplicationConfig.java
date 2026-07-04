package com.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        GatewayTimeoutProperties.class,
        OpenAiProviderProperties.class,
        AnthropicProviderProperties.class,
        GeminiProviderProperties.class,
        DigitalOceanProviderProperties.class,
        GatewayAuthProperties.class
})
public class GatewayApplicationConfig {
}
