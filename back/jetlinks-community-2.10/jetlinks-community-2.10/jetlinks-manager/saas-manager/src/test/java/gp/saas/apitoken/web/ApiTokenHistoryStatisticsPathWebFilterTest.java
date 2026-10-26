package gp.saas.apitoken.web;

import gp.saas.apitoken.service.ApiTokenScopeService;
import gp.saas.apitoken.service.ApiTokenService;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiTokenHistoryStatisticsPathWebFilterTest {

    @Test
    void bothAliasesRequireProductScopeBeforeReachingManagementController() {
        for (String path : new String[]{
            "/api/v1/device-product/product-1/history-statistics",
            "/api/v1/device/product/product-1/history-statistics/tasks/task-1"
        }) {
            Fixture fixture = new Fixture();
            when(fixture.scope.requirePermission("device-product", "query"))
                .thenReturn(Mono.empty());
            when(fixture.scope.requireDevices(
                Collections.emptyList(), List.of("product-1")))
                .thenReturn(Mono.empty());

            fixture.filter.filter(MockServerWebExchange.from(
                MockServerHttpRequest.get(path)), fixture.chain).block();

            assertTrue(fixture.reachedController.get());
            verify(fixture.scope).requirePermission("device-product", "query");
            verify(fixture.scope).requireAnyDataScope();
            verify(fixture.scope).requireDevices(
                Collections.emptyList(), List.of("product-1"));
            verify(fixture.devices, never()).findById(anyString());
        }
    }

    @Test
    void deviceOnlyTokenCannotManageAllDevicesOfAProduct() {
        Fixture fixture = new Fixture();
        when(fixture.scope.requirePermission("device-product", "save"))
            .thenReturn(Mono.empty());
        when(fixture.scope.requireDevices(
            Collections.emptyList(), List.of("product-1")))
            .thenReturn(Mono.error(new ResponseStatusException(
                HttpStatus.FORBIDDEN, "product is outside token scope")));

        assertThrows(ResponseStatusException.class, () -> fixture.filter.filter(
            MockServerWebExchange.from(MockServerHttpRequest.post(
                "/api/v1/device/product/product-1/history-statistics/energy/_rebuild")),
            fixture.chain).block());

        assertFalse(fixture.reachedController.get());
        verify(fixture.scope).requirePermission("device-product", "save");
        verify(fixture.scope).requireDevices(
            Collections.emptyList(), List.of("product-1"));
        verify(fixture.devices, never()).findById(anyString());
    }

    private static final class Fixture {
        final ApiTokenScopeService scope = mock(ApiTokenScopeService.class);
        final ApiTokenService tokens = mock(ApiTokenService.class);
        final LocalDeviceInstanceService devices = mock(LocalDeviceInstanceService.class);
        final Authentication auth = mock(Authentication.class);
        final ApiTokenPathWebFilter filter = new ApiTokenPathWebFilter(scope, tokens, devices);
        final AtomicBoolean reachedController = new AtomicBoolean();
        final WebFilterChain chain = exchange -> {
            reachedController.set(true);
            return Mono.empty();
        };

        Fixture() {
            when(scope.current()).thenReturn(Mono.just(auth));
            when(scope.isApiToken(auth)).thenReturn(true);
            when(auth.<String>getAttribute("apiTokenId"))
                .thenReturn(Optional.of("token-1"));
            when(scope.requireAnyDataScope()).thenReturn(Mono.empty());
            when(tokens.recordUsage(eq("token-1"), nullable(String.class)))
                .thenReturn(Mono.empty());
        }
    }
}
