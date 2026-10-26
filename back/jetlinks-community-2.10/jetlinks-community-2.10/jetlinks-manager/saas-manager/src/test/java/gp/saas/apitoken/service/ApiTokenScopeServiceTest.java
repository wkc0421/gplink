package gp.saas.apitoken.service;

import org.hswebframework.web.authorization.Authentication;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiTokenScopeServiceTest {

    @Test
    void productWideManagementCannotUseDeviceOnlyScope() {
        ApiTokenScopeService deviceOnly = serviceWithToken(
            Collections.emptyList(), List.of("device-1"));

        assertThrows(ResponseStatusException.class, () -> deviceOnly
            .requireDevices(Collections.emptyList(), List.of("product-1"))
            .block());

        ApiTokenScopeService productScoped = serviceWithToken(
            List.of("product-1"), Collections.emptyList());

        assertDoesNotThrow(() -> productScoped
            .requireDevices(Collections.emptyList(), List.of("product-1"))
            .block());
    }

    private ApiTokenScopeService serviceWithToken(List<String> productIds,
                                                   List<String> deviceIds) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.<String>getAttribute("apiTokenId"))
            .thenReturn(Optional.of("token-1"));
        when(authentication.<ArrayList<String>>getAttribute("productIds"))
            .thenReturn(Optional.of(new ArrayList<>(productIds)));
        when(authentication.<ArrayList<String>>getAttribute("deviceIds"))
            .thenReturn(Optional.of(new ArrayList<>(deviceIds)));
        return new ApiTokenScopeService() {
            @Override
            public Mono<Authentication> current() {
                return Mono.just(authentication);
            }
        };
    }
}
