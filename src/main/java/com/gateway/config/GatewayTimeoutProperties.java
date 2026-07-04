package com.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.timeouts")
public record GatewayTimeoutProperties(
        long connectMs,
        long readFirstByteMs,
        long readInterChunkMs,
        long totalRequestMs
) {
}
