package com.qwerlty.myojbackendgateway;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.cloud.sentinel.log.dir=${java.io.tmpdir}",
        "security.gateway-token=test-gateway-token"
})
class MyojBackendGatewayApplicationTests {

    @Resource
    private ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory;

    @Resource
    private RouteLocator routeLocator;

    @Resource
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Resource
    private TimeLimiterRegistry timeLimiterRegistry;

    @Test
    void contextLoads() {
        assertThat(circuitBreakerFactory.getClass().getName()).contains("Resilience4J");
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes)
                .extracting("id")
                .contains("myoj-backend-ai-stream", "myoj-backend-ai-service")
                .hasSize(6);
        Route streamRoute = routes.stream()
                .filter(route -> "myoj-backend-ai-stream".equals(route.getId()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertThat(streamRoute.getOrder()).isEqualTo(-10);
        assertThat(streamRoute.getMetadata()).containsEntry("response-timeout", -1);
        assertThat(streamRoute.getFilters())
                .noneSatisfy(filter -> assertThat(filter.getClass().getName())
                        .contains("CircuitBreaker"));
        assertThat(circuitBreakerRegistry.circuitBreaker("judgeCircuit")
                .getCircuitBreakerConfig().getSlowCallDurationThreshold())
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(timeLimiterRegistry.timeLimiter("aiCircuit")
                .getTimeLimiterConfig().getTimeoutDuration())
                .isEqualTo(Duration.ofSeconds(121));
    }

}
