package com.gateway.routing;

import com.gateway.adapter.ProviderAdapter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ProviderRegistry {

    private final Map<String, ProviderAdapter> adapters;

    public ProviderRegistry(List<ProviderAdapter> adapterList) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(ProviderAdapter::getProviderId, Function.identity()));
    }

    public ProviderAdapter get(String providerId) {
        ProviderAdapter adapter = adapters.get(providerId);
        if (adapter == null) {
            throw new IllegalStateException("No adapter registered for provider: " + providerId);
        }
        return adapter;
    }

    public boolean hasProvider(String providerId) {
        return adapters.containsKey(providerId);
    }
}
