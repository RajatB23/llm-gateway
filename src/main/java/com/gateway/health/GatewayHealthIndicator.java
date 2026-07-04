package com.gateway.health;

import com.gateway.routing.ModelRouter;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class GatewayHealthIndicator implements ReactiveHealthIndicator {

    private final ModelRouter modelRouter;

    public GatewayHealthIndicator(ModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }

    @Override
    public Mono<Health> health() {
        if (modelRouter.hasRoutes()) {
            return Mono.just(Health.up()
                    .withDetail("routesLoaded", true)
                    .withDetail("routeCount", modelRouter.routeCount())
                    .build());
        }
        return Mono.just(Health.down()
                .withDetail("routesLoaded", false)
                .withDetail("reason", "No routes configured")
                .build());
    }
}
