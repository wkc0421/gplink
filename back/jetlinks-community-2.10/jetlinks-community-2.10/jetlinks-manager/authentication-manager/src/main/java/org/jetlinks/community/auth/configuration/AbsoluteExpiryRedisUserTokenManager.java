package org.jetlinks.community.auth.configuration;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.token.UserToken;
import org.hswebframework.web.authorization.token.AuthenticationUserToken;
import org.hswebframework.web.authorization.token.redis.RedisUserTokenManager;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import reactor.core.publisher.Mono;

/** Redis token manager that turns the API-token expiry into a hard deadline. */
public class AbsoluteExpiryRedisUserTokenManager extends RedisUserTokenManager {
    public AbsoluteExpiryRedisUserTokenManager(ReactiveRedisOperations<Object, Object> template) {
        super(template);
    }

    @Override
    public Mono<UserToken> getByToken(String token) {
        return super.getByToken(token).flatMap(userToken -> {
            if (userToken instanceof AuthenticationUserToken authenticationUserToken) {
                Authentication authentication = authenticationUserToken.getAuthentication();
                long expiresAt = authentication.<Long>getAttribute("absoluteExpiresAt").orElse(Long.MAX_VALUE);
                if (expiresAt <= System.currentTimeMillis()) {
                    return super.signOutByToken(token).then(Mono.empty());
                }
            }
            return Mono.just(userToken);
        });
    }
}
