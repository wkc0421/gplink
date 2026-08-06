package gp.saas.web;

import gp.saas.entity.TokenRequestEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.ReactiveAuthenticationManager;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.events.AuthorizationSuccessEvent;
import org.hswebframework.web.authorization.simple.PlainTextUsernamePasswordAuthenticationRequest;
import org.hswebframework.web.system.authorization.api.service.reactive.ReactiveUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@RequestMapping({
    "/v1/authorization",
    "/api/v1/authorization"
})
@RestController
@Resource(id = "authorization-api", name = "设备层授权标准接口")
@Tag(name = "设备层授权标准接口管理")
@Slf4j
public class ApiAuthorizationController {

    private final ApplicationEventPublisher eventPublisher;
    private final ReactiveUserService userService;
    private final ReactiveAuthenticationManager authenticationManager;

    @Value("${saas.api.username:admin}")
    private String allowedUsername;

    @Value("${saas.api.legacy-password:yada88}")
    private String legacyPassword;

    public ApiAuthorizationController(ApplicationEventPublisher eventPublisher,
                                      ReactiveUserService userService,
                                      ReactiveAuthenticationManager authenticationManager) {
        this.eventPublisher = eventPublisher;
        this.userService = userService;
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/token")
    @QueryAction
    @Authorize(ignore = true)
    @Operation(summary = "获取token")
    public Mono<String> getToken(@RequestBody TokenRequestEntity tokenRequestEntity) {
        String inputUser = tokenRequestEntity.getUser();
        String inputPassword = tokenRequestEntity.getPassword();

        if (!allowedUsername.equals(inputUser)) {
            log.warn("SaaS API token request denied: username '{}' is not the configured API user", inputUser);
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        }

        return authenticate(inputUser, inputPassword)
            .flatMap(authentication -> createToken(authentication, inputUser, inputPassword));
    }

    private boolean isLegacyAdminPassword(String username, String password) {
        return allowedUsername.equals(username) && legacyPassword.equals(password);
    }

    private Mono<Authentication> authenticate(String username, String password) {
        if (isLegacyAdminPassword(username, password)) {
            // Keep legacy password compatibility, but require the complete authorization context.
            return userService
                .findByUsername(username)
                .flatMap(user -> authenticationManager.getByUserId(user.getId()))
                .switchIfEmpty(Mono.defer(() -> authenticationFailed(username)));
        }

        // Use the same authentication entry point as /authorize/login.
        return authenticationManager
            .authenticate(Mono.just(new PlainTextUsernamePasswordAuthenticationRequest(username, password)))
            .switchIfEmpty(Mono.defer(() -> authenticationFailed(username)));
    }

    private <T> Mono<T> authenticationFailed(String username) {
        log.warn("SaaS API token request failed: incorrect password or unavailable authorization for user '{}'", username);
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
    }

    private Mono<String> createToken(Authentication authentication, String username, String password) {
        AuthorizationSuccessEvent event = new AuthorizationSuccessEvent(authentication, parameterGetter(username, password));
        event.getResult().put("userId", authentication.getUser().getId());

        // The standard listener generates the token and stores it in UserTokenManager.
        return event
            .publish(eventPublisher)
            .then(Mono.defer(() -> Mono.justOrEmpty(event.getResult().get("token"))))
            .map(String::valueOf)
            .switchIfEmpty(Mono.error(new IllegalStateException("标准认证流程未生成 token")));
    }

    private Function<String, Object> parameterGetter(String username, String password) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("username", username);
        parameters.put("password", password);
        return parameters::get;
    }
}
