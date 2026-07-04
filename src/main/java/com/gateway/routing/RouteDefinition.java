package com.gateway.routing;

import java.util.List;

public record RouteDefinition(
        String modelAlias,
        List<ProviderTarget> chain
) {
    public record ProviderTarget(
            String providerId,
            String upstreamModel,
            int priority,
            Double costPerInputToken,
            Double costPerOutputToken
    ) {
        public ProviderTarget(String providerId, String upstreamModel, int priority) {
            this(providerId, upstreamModel, priority, null, null);
        }
    }
}
