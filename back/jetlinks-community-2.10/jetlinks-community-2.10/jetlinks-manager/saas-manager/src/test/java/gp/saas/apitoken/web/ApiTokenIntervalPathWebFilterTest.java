package gp.saas.apitoken.web;

import gp.saas.apitoken.service.ApiTokenScopeService;
import gp.saas.apitoken.service.ApiTokenService;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiTokenIntervalPathWebFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {"/devices/interval", "/devices/interval/week/history", "/devices/interval/quarter/history"})
    void treatsDevicesIntervalAsCollectionRatherThanDeviceId(String path) {
        ApiTokenScopeService scope = mock(ApiTokenScopeService.class);
        ApiTokenService tokenService = mock(ApiTokenService.class);
        LocalDeviceInstanceService devices = mock(LocalDeviceInstanceService.class);
        Authentication auth = mock(Authentication.class);
        when(scope.current()).thenReturn(Mono.just(auth));
        when(scope.isApiToken(auth)).thenReturn(true);
        when(auth.<String>getAttribute("apiTokenId")).thenReturn(Optional.of("token-1"));
        when(scope.requireAnyDataScope()).thenReturn(Mono.empty());
        when(scope.requirePermission("device-opt-api", "query")).thenReturn(Mono.empty());
        when(tokenService.recordUsage(eq("token-1"), nullable(String.class))).thenReturn(Mono.empty());
        ApiTokenPathWebFilter filter = new ApiTokenPathWebFilter(scope, tokenService, devices);
        AtomicBoolean reachedController = new AtomicBoolean();
        WebFilterChain chain = exchange -> {
            reachedController.set(true);
            return Mono.empty();
        };

        filter.filter(MockServerWebExchange.from(MockServerHttpRequest
            .post("/api/v1/device-data" + path)), chain).block();

        assertTrue(reachedController.get());
        verify(scope).requireAnyDataScope();
        verify(devices, never()).findById(anyString());
    }

    @Test
    void treatsDevicesAggregationAsCollectionRatherThanDeviceId() {
        ApiTokenScopeService scope = mock(ApiTokenScopeService.class);
        ApiTokenService tokenService = mock(ApiTokenService.class);
        LocalDeviceInstanceService devices = mock(LocalDeviceInstanceService.class);
        Authentication auth = mock(Authentication.class);
        when(scope.current()).thenReturn(Mono.just(auth));
        when(scope.isApiToken(auth)).thenReturn(true);
        when(auth.<String>getAttribute("apiTokenId")).thenReturn(Optional.of("token-1"));
        when(scope.requireAnyDataScope()).thenReturn(Mono.empty());
        when(scope.requirePermission("device-opt-api", "query")).thenReturn(Mono.empty());
        when(tokenService.recordUsage(eq("token-1"), nullable(String.class))).thenReturn(Mono.empty());
        ApiTokenPathWebFilter filter = new ApiTokenPathWebFilter(scope, tokenService, devices);
        AtomicBoolean reachedController = new AtomicBoolean();
        WebFilterChain chain = exchange -> {
            reachedController.set(true);
            return Mono.empty();
        };

        filter.filter(MockServerWebExchange.from(MockServerHttpRequest
            .post("/api/v1/device-data/devices/agg/_query")), chain).block();

        assertTrue(reachedController.get());
        verify(scope).requireAnyDataScope();
        verify(devices, never()).findById(anyString());
    }
}
