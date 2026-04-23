package gp.saas.web;

import gp.saas.entity.TokenRequestEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.events.AuthorizationSuccessEvent;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
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

@RequestMapping("/api/v1/authorization")
@RestController
@Resource(id = "authorization-api", name = "设备层授权标准接口")
@Tag(name = "设备层授权标准接口管理")
@Slf4j
public class ApiAuthorizationController {

    private final ApplicationEventPublisher eventPublisher;
    private final ReactiveUserService userService;

    @Value("${saas.api.username:admin}")
    private String allowedUsername;

    public ApiAuthorizationController(ApplicationEventPublisher eventPublisher,
                                      ReactiveUserService userService) {
        this.eventPublisher = eventPublisher;
        this.userService = userService;
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

        return userService
            .findByUsernameAndPassword(inputUser, inputPassword)
            .switchIfEmpty(Mono.defer(() -> {
                log.warn("SaaS API token request failed: incorrect password for user '{}'", inputUser);
                return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
            }))
            .flatMap(userEntity -> {
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("username", inputUser);
                parameters.put("password", inputPassword);
                Function<String, Object> parameterGetter = parameters::get;

                SimpleUser user = new SimpleUser();
                user.setId(userEntity.getId());
                user.setName(userEntity.getName());
                user.setUsername(userEntity.getUsername());

                SimpleAuthentication auth = new SimpleAuthentication();
                auth.setUser(user);

                AuthorizationSuccessEvent event = new AuthorizationSuccessEvent(auth, parameterGetter);
                event.getResult().put("userId", user.getId());

                return event.publish(eventPublisher)
                            .then(Mono.just(event.getResult().get("token").toString()));
            });
    }
}
