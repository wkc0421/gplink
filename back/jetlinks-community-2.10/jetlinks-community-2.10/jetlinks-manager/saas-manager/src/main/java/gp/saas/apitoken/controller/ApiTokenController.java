package gp.saas.apitoken.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import gp.saas.apitoken.dto.ApiTokenRequest;
import gp.saas.apitoken.dto.ApiTokenResponse;
import gp.saas.apitoken.dto.ApiTokenGrantOptions;
import gp.saas.apitoken.entity.ApiTokenAuditEntity;
import gp.saas.apitoken.service.ApiTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.hswebframework.web.exception.ValidationException;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping({"/api-token", "/api/v1/api-token"})
@RequiredArgsConstructor
@Authorize
@Resource(id = "api-token", name = "API Token")
public class ApiTokenController {
    private final ApiTokenService service;
    private final ObjectMapper objectMapper;

    @PostMapping
    @SaveAction
    public Mono<ApiTokenResponse> create(@Valid @RequestBody ApiTokenRequest request) { return service.create(request); }

    @PostMapping("/_query")
    @QueryAction
    public Mono<PagerResult<ApiTokenResponse>> query(@RequestBody QueryParamEntity query) { return service.list(query); }

    @GetMapping("/{id}")
    @QueryAction
    public Mono<ApiTokenResponse> get(@PathVariable String id) { return service.get(id); }

    @GetMapping("/grant-options")
    public Mono<ApiTokenGrantOptions> grantOptions() { return service.grantOptions(); }

    @PostMapping("/{id}/rotate")
    @ResourceAction(id = "rotate", name = "轮换")
    public Mono<ApiTokenResponse> rotate(@PathVariable String id,
                                         ServerWebExchange exchange) {
        return DataBufferUtils
            .join(exchange.getRequest().getBody())
            .map(buffer -> {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                DataBufferUtils.release(buffer);
                return bytes;
            })
            .defaultIfEmpty(new byte[0])
            .flatMap(bytes -> {
                if (bytes.length == 0) {
                    return service.rotate(id);
                }
                if (bytes.length > 64 * 1024) {
                    return Mono.error(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "request body is too large"));
                }
                try {
                    return service.rotate(id, objectMapper.readValue(bytes, ApiTokenRequest.class));
                } catch (Exception e) {
                    return Mono.error(new ValidationException("invalid API token request"));
                }
            });
    }

    @PostMapping("/{id}/revoke")
    @ResourceAction(id = "revoke", name = "吊销")
    public Mono<Void> revoke(@PathVariable String id) { return service.revoke(id); }

    @GetMapping("/{id}/audit")
    @QueryAction
    public Flux<ApiTokenAuditEntity> audit(@PathVariable String id) { return service.audit(id); }
}
