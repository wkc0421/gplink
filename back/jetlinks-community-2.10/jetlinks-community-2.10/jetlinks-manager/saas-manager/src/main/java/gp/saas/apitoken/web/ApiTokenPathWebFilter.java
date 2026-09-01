package gp.saas.apitoken.web;

import gp.saas.apitoken.service.ApiTokenScopeService;
import gp.saas.apitoken.service.ApiTokenService;
import gp.saas.apitoken.service.ApiTokenPermissionCatalog;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Service tokens are intentionally limited to the public /api/v1 surface. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@RequiredArgsConstructor
public class ApiTokenPathWebFilter implements WebFilter {
    private final ApiTokenScopeService scopeService;
    private final ApiTokenService tokenService;
    private final LocalDeviceInstanceService deviceService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return scopeService.current().flatMap(authentication -> {
            Mono<Void> usage = authentication.<String>getAttribute("apiTokenId")
                .map(id -> tokenService.recordUsage(id, exchange.getRequest().getRemoteAddress() == null ? null : String.valueOf(exchange.getRequest().getRemoteAddress().getAddress().getHostAddress())))
                .orElse(Mono.empty());
            if (scopeService.isApiToken(authentication)
                && !exchange.getRequest().getPath().value().startsWith("/api/v1/")) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return usage.then(exchange.getResponse().setComplete()).thenReturn(Boolean.TRUE);
            }
            Mono<Void> objectScope = scopeService.isApiToken(authentication)
                ? enforceSingleObjectScope(exchange)
                : Mono.empty();
            return usage.then(enforcePermission(exchange)).then(objectScope).then(chain.filter(exchange)).thenReturn(Boolean.TRUE);
        }).switchIfEmpty(Mono.just(Boolean.FALSE).flatMap(ignored -> chain.filter(exchange).thenReturn(Boolean.TRUE))).then();
    }

    private Mono<Void> enforceSingleObjectScope(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        String prefix = "/api/v1/";
        if (!path.startsWith(prefix)) return Mono.empty();
        String relative = path.substring(prefix.length());
        String[] parts = relative.split("/");
        if (parts.length > 0 && ("device".equals(parts[0]) || "device-data".equals(parts[0]) || "product".equals(parts[0]))) {
            return scopeService.requireAnyDataScope().then(enforceObjectScope(exchange, parts));
        }
        return Mono.empty();
    }

    private Mono<Void> enforceObjectScope(ServerWebExchange exchange, String[] parts) {
        if (parts.length < 2 || parts[1].startsWith("_")) return Mono.empty();
        if ("device".equals(parts[0]) || "device-data".equals(parts[0])) {
            String id = parts[1];
            return deviceService.findById(id).switchIfEmpty(Mono.error(new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "device is outside API token scope")))
                .flatMap(device -> scopeService.requireDevice(id, device.getProductId()));
        }
        if ("product".equals(parts[0])) return scopeService.current().flatMap(auth -> scopeService.isApiToken(auth) ? scopeService.requireDevice(null, parts[1]) : Mono.empty());
        return Mono.empty();
    }

    private Mono<Void> enforcePermission(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/v1/")) return Mono.empty();
        return ApiTokenPermissionCatalog
            .resolve(path, exchange.getRequest().getMethod())
            .map(target -> scopeService.requirePermission(target.resource(), target.action()))
            .orElseGet(Mono::empty);
    }
}
