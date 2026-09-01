package gp.saas.apitoken.service;

import org.hswebframework.web.authorization.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared data-scope checks for controllers that expose product/device data. */
@Service
public class ApiTokenScopeService {
    public Mono<Authentication> current() {
        return Authentication.currentReactive();
    }

    public Mono<Void> requirePermission(String resource, String action) {
        return current().flatMap(auth -> isApiToken(auth) && !auth.hasPermission(resource, action)
            ? forbidden("permission denied") : Mono.empty());
    }

    public Mono<Void> requireDevice(String deviceId, String productId) {
        return current().flatMap(auth -> {
            if (!isApiToken(auth)) return Mono.empty();
            Set<String> products = values(auth, "productIds");
            Set<String> devices = values(auth, "deviceIds");
            if (!products.isEmpty() && productId != null && products.contains(productId)) return Mono.empty();
            if (!devices.isEmpty() && deviceId != null && devices.contains(deviceId)) return Mono.empty();
            return forbidden("device is outside API token scope");
        });
    }

    public Mono<Void> requireAnyDataScope() {
        return current().flatMap(auth -> {
            if (!isApiToken(auth)) return Mono.empty();
            return values(auth, "productIds").isEmpty() && values(auth, "deviceIds").isEmpty()
                ? forbidden("API token has no product or device scope") : Mono.empty();
        });
    }

    public Mono<Void> requireDevices(Collection<String> deviceIds, Collection<String> productIds) {
        return current().flatMap(auth -> {
            if (!isApiToken(auth)) return Mono.empty();
            Set<String> allowedDevices = values(auth, "deviceIds");
            Set<String> allowedProducts = values(auth, "productIds");
            boolean allDevicesKnown = deviceIds == null || deviceIds.stream().allMatch(allowedDevices::contains);
            boolean allProductsKnown = productIds == null || productIds.stream().allMatch(allowedProducts::contains);
            return allDevicesKnown && allProductsKnown ? Mono.empty() : forbidden("request contains data outside API token scope");
        });
    }

    public boolean isApiToken(Authentication authentication) {
        return authentication != null && authentication.getAttribute("apiTokenId").isPresent();
    }

    private static Set<String> values(Authentication auth, String key) {
        return auth.getAttribute(key).map(value -> new HashSet<>((List<String>) value)).orElseGet(HashSet::new);
    }

    private static Mono<Void> forbidden(String message) { return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, message)); }
}
