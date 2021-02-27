package com.example.discovery;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.gateway.event.RefreshRoutesResultEvent;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.SetPathGatewayFilterFactory;
import org.springframework.cloud.gateway.route.CachingRouteLocator;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

import jdk.dynalink.beans.StaticClass;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootApplication
public class DiscoveryApplication {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryApplication.class, args);
    }

    private final AtomicBoolean s = new AtomicBoolean(false);

    @Bean
    @RefreshScope
    RouteLocator gateway(RouteLocatorBuilder rlb) {
        var id = "customers";
        if (!this.s.get()) {
            this.s.set(true);
            return rlb
                    .routes()
                    .route(id, predicateSpec -> predicateSpec.path("/customers").uri("lb://customers"))
                    .build();
        } else {
            return rlb
                    .routes()
                    .route(id, predicateSpec -> predicateSpec.path("/customers").filters(fs -> fs.setPath("/ws/customers")).uri("lb://customers"))
                    .build();
        }
    }

    @Bean
    ApplicationListener<RefreshRoutesResultEvent> routesRefreshed() {
        return rre -> {
            logger.info("routes updated");
            var         crl    = (CachingRouteLocator) rre.getSource();
            Flux<Route> routes = crl.getRoutes();
            routes.map(Objects::toString).subscribe(logger::info);
        };
    }
    @Bean
    RouteLocator gateway(SetPathGatewayFilterFactory ff) {
        var singleRoute = Route.async()
                .id("test-route")
                .filter(new OrderedGatewayFilter(ff.apply(config -> config.setTemplate("/customers")), 1))
                .uri("lb://customers")
                .asyncPredicate(serverWebExchange -> {
                    var uri = serverWebExchange.getRequest().getURI();
                    var path = uri.getPath();
                    var match = path.contains("/customers");
                    return Mono.just(match);
                })
                .build();

        return () -> Flux.just(singleRoute);
    }
}
