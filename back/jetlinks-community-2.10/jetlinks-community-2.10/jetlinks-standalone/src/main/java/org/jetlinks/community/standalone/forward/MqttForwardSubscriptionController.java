package org.jetlinks.community.standalone.forward;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.DeleteAction;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/mqtt/forward/subscription")
@RequiredArgsConstructor
@Authorize
@Resource(id = "mqtt-forward-subscription", name = "MQTT转发订阅")
@Tag(name = "MQTT temporary forward subscription")
public class MqttForwardSubscriptionController {

    private final MqttForwardSubscriptionService service;

    @PostMapping("/by-devices")
    @SaveAction
    @Operation(summary = "Create a temporary page-forward lease for devices")
    public Mono<MqttForwardLeaseResponse> createByDevices(@Valid @RequestBody DeviceSubscribeRequest request) {
        return service.createByDevices(request);
    }

    @PutMapping("/{leaseId}/renew")
    @SaveAction
    @Operation(summary = "Renew a temporary page-forward lease")
    public Mono<MqttForwardLeaseResponse> renew(@PathVariable String leaseId) {
        return service.renewLease(leaseId);
    }

    @DeleteMapping("/{leaseId}")
    @DeleteAction
    @Operation(summary = "Close a temporary page-forward lease")
    public Mono<Void> close(@PathVariable String leaseId) {
        return service.closeLease(leaseId);
    }

    @GetMapping("/active")
    @QueryAction
    @Operation(summary = "Inspect active temporary page-forward leases")
    public Mono<Map<String, Object>> getActiveSubscriptions() {
        return Mono.just(service.getActiveSubscriptions());
    }
}
