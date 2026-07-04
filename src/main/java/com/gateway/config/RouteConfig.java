package com.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.gateway.routing.RouteDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class RouteConfig {

    @Bean
    public Map<String, RouteDefinition> routeDefinitions(
            @Value("${gateway.routes-file}") Resource routesFile,
            ObjectMapper objectMapper) throws IOException {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        RoutesFile file = yamlMapper.readValue(routesFile.getInputStream(), RoutesFile.class);
        if (file.routes() == null || file.routes().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, RouteDefinition> result = new LinkedHashMap<>();
        file.routes().forEach((alias, def) -> {
            List<RouteDefinition.ProviderTarget> chain = def.chain().stream()
                    .sorted(java.util.Comparator.comparingInt(RouteDefinition.ProviderTarget::priority))
                    .toList();
            result.put(alias, new RouteDefinition(alias, chain));
        });
        return result;
    }

    record RoutesFile(Map<String, RouteDefinitionEntry> routes) {
    }

    record RouteDefinitionEntry(List<RouteDefinition.ProviderTarget> chain) {
    }
}
