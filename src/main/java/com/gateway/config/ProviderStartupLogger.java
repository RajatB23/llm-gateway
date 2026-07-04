package com.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ProviderStartupLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProviderStartupLogger.class);

    private final DigitalOceanProviderProperties digitalOceanProperties;

    public ProviderStartupLogger(DigitalOceanProviderProperties digitalOceanProperties) {
        this.digitalOceanProperties = digitalOceanProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean configured = digitalOceanProperties.apiKey() != null
                && !digitalOceanProperties.apiKey().isBlank();
        log.info("DigitalOcean API key configured: {}", configured ? "yes" : "no");
        log.info("DigitalOcean base URL: {}", digitalOceanProperties.baseUrl());
    }
}
