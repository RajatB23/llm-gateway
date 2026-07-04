package com.gateway.routing;

import com.gateway.exception.GatewayException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRouterTest {

    @Test
    void resolve_knownModel_returnsChain() {
        RouteDefinition route = new RouteDefinition("gpt-4o", List.of(
                new RouteDefinition.ProviderTarget("openai", "gpt-4o", 0),
                new RouteDefinition.ProviderTarget("anthropic", "claude-3-5-sonnet-20241022", 1)));
        ModelRouter router = new ModelRouter(Map.of("gpt-4o", route));

        List<RouteDefinition.ProviderTarget> chain = router.resolveChain("gpt-4o");

        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).providerId()).isEqualTo("openai");
    }

    @Test
    void resolve_unknownModel_throws404() {
        ModelRouter router = new ModelRouter(Map.of());

        assertThatThrownBy(() -> router.resolve("unknown-model"))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getStatus().value()).isEqualTo(404));
    }
}
