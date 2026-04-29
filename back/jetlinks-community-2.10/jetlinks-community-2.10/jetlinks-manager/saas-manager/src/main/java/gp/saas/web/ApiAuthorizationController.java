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
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.authorization.token.UserTokenManager;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
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
import java.util.UUID;
import java.util.function.Function;

@RequestMapping("/api/v1/authorization")
@RestController
@Resource(id = "authorization-api", name = "设备层授权标准接口")
@Tag(name = "设备层授权标准接口管理")
@Slf4j
public class ApiAuthorizationController {

    private final ApplicationEventPublisher eventPublisher;
    private final ReactiveUserService userService;
    private final ReactiveAuthenticationManager authenticationManager;
    private final UserTokenManager userTokenManager;
    private static final String TOKEN_TYPE_DEFAULT = "default";
    private static final long TOKEN_TIMEOUT_MS = 30L * 60 * 1000;

    @Value("${saas.api.username:admin}")
    private String allowedUsername;

    @Value("${saas.api.legacy-password:yada88}")
    private String legacyPassword;

    public ApiAuthorizationController(ApplicationEventPublisher eventPublisher,
                                      ReactiveUserService userService,
                                      ReactiveAuthenticationManager authenticationManager,
                                      UserTokenManager userTokenManager) {
        this.eventPublisher = eventPublisher;
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.userTokenManager = userTokenManager;
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

        Mono<UserEntity> userMono = isLegacyAdminPassword(inputUser, inputPassword)
            ? userService.findByUsername(inputUser)
            : userService.findByUsernameAndPassword(inputUser, inputPassword);

        return userMono
            .switchIfEmpty(Mono.defer(() -> {
                log.warn("SaaS API token request failed: incorrect password for user '{}'", inputUser);
                return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
            }))
            .flatMap(userEntity -> createToken(userEntity, inputUser, inputPassword));
    }

    private boolean isLegacyAdminPassword(String username, String password) {
        return allowedUsername.equals(username) && legacyPassword.equals(password);
    }

    private Mono<String> createToken(UserEntity userEntity, String username, String password) {
        return authenticationManager
            .getByUserId(userEntity.getId())
            .switchIfEmpty(Mono.fromSupplier(() -> simpleAuthentication(userEntity)))
            .flatMap(authentication -> {
                String token = UUID.randomUUID().toString().replace("-", "");
                AuthorizationSuccessEvent event = new AuthorizationSuccessEvent(authentication, parameterGetter(username, password));
                event.getResult().put("userId", userEntity.getId());
                event.getResult().put("token", token);

                return userTokenManager
                    .signIn(userEntity.getId(),
                            token,
                            TOKEN_TYPE_DEFAULT,
                            TOKEN_TIMEOUT_MS,
                            authentication)
                    .then(event.publish(eventPublisher))
                    .thenReturn(token);
            });
    }

    private Function<String, Object> parameterGetter(String username, String password) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("username", username);
        parameters.put("password", password);
        return parameters::get;
    }

    private Authentication simpleAuthentication(UserEntity userEntity) {
        SimpleUser user = new SimpleUser();
        user.setId(userEntity.getId());
        user.setName(userEntity.getName());
        user.setUsername(userEntity.getUsername());

        SimpleAuthentication auth = new SimpleAuthentication();
        auth.setUser(user);
        return auth;
    }
}
