package com.gateway.routing;

import com.gateway.exception.GatewayException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ModelRouter {

    private final Map<String, RouteDefinition> routes;

    public ModelRouter(Map<String, RouteDefinition> routes) {
        this.routes = routes;
    }

    public RouteDefinition resolve(String model) {
        RouteDefinition route = routes.get(model);
        if (route == null) {
            throw GatewayException.unknownModel(model);
        }
        return route;
    }

    public List<RouteDefinition.ProviderTarget> resolveChain(String model) {
        return resolve(model).chain();
    }

    public boolean hasRoutes() {
        return !routes.isEmpty();
    }

    public int routeCount() {
        return routes.size();
    }
}
