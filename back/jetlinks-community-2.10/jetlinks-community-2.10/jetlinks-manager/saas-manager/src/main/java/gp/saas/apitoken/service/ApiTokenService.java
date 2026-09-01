package gp.saas.apitoken.service;

import gp.saas.apitoken.dto.ApiTokenRequest;
import gp.saas.apitoken.dto.ApiTokenResponse;
import gp.saas.apitoken.dto.ApiTokenGrantOptions;
import gp.saas.apitoken.entity.ApiTokenAuditEntity;
import gp.saas.apitoken.entity.ApiTokenEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.Permission;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimplePermission;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.authorization.token.UserTokenManager;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.hswebframework.web.exception.ValidationException;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.service.LocalDeviceProductService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiTokenService extends GenericReactiveCrudService<ApiTokenEntity, String> {
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_REVOKED = "revoked";
    public static final String PRINCIPAL_PREFIX = "api-token:";
    public static final long MIN_LIFETIME = 5 * 60_000L;
    public static final long MAX_LIFETIME = 365 * 24 * 60 * 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiTokenAuditService auditService;
    private final UserTokenManager tokenManager;
    private final LocalDeviceProductService productService;
    private final LocalDeviceInstanceService deviceService;
    private final ConcurrentHashMap<String, Long> usageWindow = new ConcurrentHashMap<>();

    @Value("${gplink.api-token.default-lifetime-ms:2592000000}")
    private long defaultLifetime;
    @Value("${gplink.api-token.min-lifetime-ms:300000}")
    private long configuredMinLifetime;
    @Value("${gplink.api-token.max-lifetime-ms:31536000000}")
    private long configuredMaxLifetime;

    public Mono<ApiTokenResponse> create(ApiTokenRequest request) {
        return currentOperator("save").flatMap(operator -> validateAndNormalize(request, operator)
            .flatMap(normalized -> issue(normalized, operator, "created")));
    }

    public Mono<PagerResult<ApiTokenResponse>> list(QueryParamEntity query) {
        return super.queryPager(query, ApiTokenResponse::masked);
    }

    public Mono<ApiTokenResponse> get(String id) { return findById(id).map(ApiTokenResponse::masked); }

    public Mono<ApiTokenResponse> rotate(String id) {
        return rotate(id, null);
    }

    public Mono<ApiTokenResponse> rotate(String id, ApiTokenRequest request) {
        return currentOperator("rotate").flatMap(operator -> findById(id)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "API token not found")))
            .flatMap(old -> validateActive(old).then(
                request == null
                    ? Mono.just(toRequest(old))
                    : validateAndNormalize(request, operator)
            ).flatMap(normalized -> tokenManager.signOutByUserId(old.getPrincipalId())
                .then(rotateIssue(old, normalized, operator)))));
    }

    public Mono<ApiTokenGrantOptions> grantOptions() {
        return currentOperatorAny("save", "rotate").map(operator -> {
            List<ApiTokenGrantOptions.ResourceOption> resources = ApiTokenPermissionCatalog
                .resources()
                .stream()
                .map(resource -> new ApiTokenGrantOptions.ResourceOption(
                    resource.getId(),
                    resource.getName(),
                    resource.getGroup(),
                    resource.getDescription(),
                    resource.isHighRisk(),
                    resource.getActions().stream()
                        .filter(action -> operator.hasPermission(resource.getId(), action.getId()))
                        .toList()
                ))
                .filter(resource -> !resource.getActions().isEmpty())
                .toList();
            return new ApiTokenGrantOptions(
                resources,
                defaultLifetime,
                Math.max(MIN_LIFETIME, configuredMinLifetime),
                Math.min(MAX_LIFETIME, configuredMaxLifetime)
            );
        });
    }

    public Mono<Void> revoke(String id) {
        return currentOperator("revoke").flatMap(operator -> findById(id)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "API token not found")))
            .flatMap(token -> {
                if (STATUS_REVOKED.equals(token.getStatus())) return Mono.empty();
                token.setStatus(STATUS_REVOKED); token.setRevokedAt(System.currentTimeMillis());
                return tokenManager.signOutByUserId(token.getPrincipalId())
                    .then(updateById(id, token))
                    .then(audit(token, "revoked", operator));
            }));
    }

    public Flux<ApiTokenAuditEntity> audit(String id) {
        return auditService.createQuery().where("token_id", id).orderBy(SortOrder.desc(ApiTokenAuditEntity::getCreateTime)).fetch();
    }

    public Mono<Void> recordUsage(String id, String ip) {
        long now = System.currentTimeMillis();
        Long previous = usageWindow.putIfAbsent(id, now);
        if (previous != null && now - previous < 60_000L) return Mono.empty();
        usageWindow.put(id, now);
        return findById(id).flatMap(entity -> {
            entity.setLastUsedAt(now); entity.setLastUsedIp(ip);
            return updateById(id, entity);
        }).then();
    }

    public Mono<Void> cleanupExpired() {
        long now = System.currentTimeMillis();
        return createQuery().where("status", STATUS_ACTIVE).and("expires_at", "lt", now).fetch()
            .flatMap(entity -> {
                entity.setStatus(STATUS_REVOKED); entity.setRevokedAt(now);
                return tokenManager.signOutByUserId(entity.getPrincipalId()).then(updateById(entity.getId(), entity)).then(audit(entity, "expired", null));
            }).then();
    }

    @Scheduled(fixedDelayString = "${gplink.api-token.cleanup-interval-ms:60000}")
    public void cleanupExpiredScheduled() {
        cleanupExpired().subscribe();
    }

    public Mono<Authentication> authentication(ApiTokenEntity entity) {
        SimpleUser user = new SimpleUser(PRINCIPAL_PREFIX + entity.getId(), entity.getName(), entity.getName(), "api-token", Collections.emptyMap());
        SimpleAuthentication authentication = new SimpleAuthentication();
        authentication.setUser(user);
        List<Permission> permissions = new ArrayList<>();
        if (entity.getPermissions() != null) entity.getPermissions().forEach((resource, actions) -> {
            SimplePermission permission = new SimplePermission(); permission.setId(resource); permission.setName(resource); permission.setActions(new HashSet<>(actions)); permissions.add(permission);
        });
        authentication.setPermissions(permissions);
        authentication.setAttribute("apiTokenId", entity.getId());
        authentication.setAttribute("absoluteExpiresAt", entity.getExpiresAt());
        authentication.setAttribute("productIds", new ArrayList<>(safe(entity.getProductIds())));
        authentication.setAttribute("deviceIds", new ArrayList<>(safe(entity.getDeviceIds())));
        return Mono.just(authentication);
    }

    private Mono<ApiTokenResponse> issue(ApiTokenRequest request, Authentication operator, String event) {
        String id = org.hswebframework.web.id.IDGenerator.SNOW_FLAKE_STRING.generate();
        String raw = generateToken();
        ApiTokenEntity entity = new ApiTokenEntity(); entity.setId(id); entity.setName(request.getName()); entity.setDescription(request.getDescription());
        entity.setTokenHash(hash(raw)); entity.setTokenHint(raw.substring(Math.max(0, raw.length() - 8))); entity.setPrincipalId(PRINCIPAL_PREFIX + id);
        entity.setStatus(STATUS_ACTIVE); entity.setExpiresAt(request.getExpiresAt()); entity.setPermissions(request.getPermissions());
        entity.setProductIds(safe(request.getProductIds())); entity.setDeviceIds(safe(request.getDeviceIds()));
        entity.setCreatorId(operator.getUser().getId()); entity.setCreatorName(operator.getUser().getName()); entity.setCreateTime(System.currentTimeMillis());
        return insert(entity).then(authentication(entity))
            .flatMap(authentication -> tokenManager.signIn(raw, "api-token", entity.getPrincipalId(), Math.max(1, entity.getExpiresAt() - System.currentTimeMillis()), authentication))
            .then(audit(entity, event, operator)).thenReturn(ApiTokenResponse.issued(entity, raw));
    }

    private Mono<ApiTokenResponse> rotateIssue(ApiTokenEntity entity,
                                               ApiTokenRequest request,
                                               Authentication operator) {
        String raw = generateToken();
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setExpiresAt(request.getExpiresAt());
        entity.setPermissions(request.getPermissions());
        entity.setProductIds(safe(request.getProductIds()));
        entity.setDeviceIds(safe(request.getDeviceIds()));
        entity.setTokenHash(hash(raw));
        entity.setTokenHint(raw.substring(Math.max(0, raw.length() - 8)));
        entity.setStatus(STATUS_ACTIVE);
        entity.setRevokedAt(null);
        return updateById(entity.getId(), entity)
            .then(authentication(entity))
            .flatMap(authentication -> tokenManager.signIn(raw, "api-token", entity.getPrincipalId(), Math.max(1, entity.getExpiresAt() - System.currentTimeMillis()), authentication))
            .then(audit(entity, "rotated", operator))
            .thenReturn(ApiTokenResponse.issued(entity, raw));
    }

    private Mono<ApiTokenRequest> validateAndNormalize(ApiTokenRequest request, Authentication operator) {
        if (request == null || !StringUtils.hasText(request.getName())) {
            return Mono.error(new ValidationException("name is required"));
        }
        long now = System.currentTimeMillis(); long expiresAt = request.getExpiresAt() == null ? now + defaultLifetime : request.getExpiresAt();
        long min = Math.max(MIN_LIFETIME, configuredMinLifetime); long max = Math.min(MAX_LIFETIME, configuredMaxLifetime);
        if (expiresAt - now < min || expiresAt - now > max) return Mono.error(new ValidationException("expiresAt must be between 5 minutes and 365 days"));
        Map<String, List<String>> permissions = new LinkedHashMap<>();
        if (request.getPermissions() != null) request.getPermissions().forEach((resource, actions) -> {
            if (StringUtils.hasText(resource) && actions != null && !actions.isEmpty()) permissions.put(resource, actions.stream().filter(StringUtils::hasText).distinct().toList());
        });
        if (permissions.isEmpty()) {
            return Mono.error(new ValidationException("at least one permission is required"));
        }
        for (Map.Entry<String, List<String>> entry : permissions.entrySet()) {
            if (entry.getValue().stream().anyMatch(action -> !ApiTokenPermissionCatalog.supports(entry.getKey(), action))) {
                return Mono.error(new ValidationException("unknown or unsupported API token permission"));
            }
            if (!operator.hasPermission(entry.getKey(), entry.getValue())) return Mono.error(new AccessDenyException("permission exceeds current administrator"));
        }
        ApiTokenRequest normalized = new ApiTokenRequest();
        normalized.setName(request.getName() == null ? null : request.getName().trim());
        normalized.setDescription(request.getDescription() == null ? null : request.getDescription().trim());
        normalized.setExpiresAt(expiresAt);
        normalized.setPermissions(permissions);
        normalized.setProductIds(normalizeIds(request.getProductIds()));
        normalized.setDeviceIds(normalizeIds(request.getDeviceIds()));
        return validateScopes(normalized, operator).thenReturn(normalized);
    }

    private Mono<Void> validateScopes(ApiTokenRequest request, Authentication operator) {
        if (!request.getProductIds().isEmpty() && !operator.hasPermission("device-product", "query")) {
            return Mono.error(new AccessDenyException("product scope exceeds current administrator"));
        }
        if (!request.getDeviceIds().isEmpty() && !operator.hasPermission("device-instance", "query")) {
            return Mono.error(new AccessDenyException("device scope exceeds current administrator"));
        }
        Mono<Void> products = Flux.fromIterable(request.getProductIds())
            .flatMap(id -> productService.findById(id)
                .switchIfEmpty(Mono.error(new ValidationException("unknown product scope: " + id))))
            .then();
        Mono<Void> devices = Flux.fromIterable(request.getDeviceIds())
            .flatMap(id -> deviceService.findById(id)
                .switchIfEmpty(Mono.error(new ValidationException("unknown device scope: " + id))))
            .then();
        return Mono.when(products, devices);
    }

    private static ApiTokenRequest toRequest(ApiTokenEntity entity) {
        ApiTokenRequest request = new ApiTokenRequest(); request.setName(entity.getName()); request.setDescription(entity.getDescription()); request.setExpiresAt(entity.getExpiresAt()); request.setPermissions(entity.getPermissions()); request.setProductIds(entity.getProductIds()); request.setDeviceIds(entity.getDeviceIds()); return request;
    }

    private Mono<Authentication> currentOperator(String action) {
        return Authentication.currentReactive().switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required")))
            .filter(auth -> auth.hasPermission("api-token", action)).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "api-token permission required")));
    }

    private Mono<Authentication> currentOperatorAny(String... actions) {
        return Authentication.currentReactive()
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required")))
            .filter(auth -> java.util.Arrays.stream(actions).anyMatch(action -> auth.hasPermission("api-token", action)))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "api-token permission required")));
    }

    private Mono<Void> audit(ApiTokenEntity token, String event, Authentication operator) {
        ApiTokenAuditEntity audit = new ApiTokenAuditEntity(); audit.setId(org.hswebframework.web.id.IDGenerator.SNOW_FLAKE_STRING.generate()); audit.setTokenId(token.getId()); audit.setEventType(event); audit.setOperatorId(operator == null ? "system" : operator.getUser().getId()); audit.setOperatorName(operator == null ? "system" : operator.getUser().getName()); audit.setCreateTime(System.currentTimeMillis()); return auditService.insert(audit).then();
    }

    private Mono<Void> validateActive(ApiTokenEntity token) {
        if (STATUS_REVOKED.equals(token.getStatus())) {
            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "API token is revoked"));
        }
        if (token.getExpiresAt() == null || token.getExpiresAt() <= System.currentTimeMillis()) {
            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "API token is expired"));
        }
        return Mono.empty();
    }
    private static byte[] randomBytes() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return bytes; }
    public static String hash(String raw) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(64); for (byte b : digest) out.append(String.format("%02x", b)); return out.toString(); } catch (Exception e) { throw new IllegalStateException(e); } }
    public static String generateToken() { return "gpl_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes()); }
    private static <T> List<T> safe(List<T> list) { return list == null ? new ArrayList<>() : new ArrayList<>(list); }
    private static List<String> normalizeIds(List<String> ids) {
        if (ids == null) return new ArrayList<>();
        return ids.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), ArrayList::new));
    }
}
