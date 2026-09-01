package org.jetlinks.community.standalone.forward;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.jetlinks.community.network.DefaultNetworkType;
import org.jetlinks.community.network.NetworkConfigManager;
import org.jetlinks.community.network.NetworkProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping({
    "/v1/mqtt/device-data/subscriber",
    "/api/v1/mqtt/device-data/subscriber"
})
@RequiredArgsConstructor
@Authorize
@Resource(id = "device-opt-api", name = "Device MQTT subscriber")
@Validated
@Tag(name = "Legacy gpLink MQTT subscriber API")
public class LegacyDeviceMqttSubscriberController {

    private final MqttForwardSubscriptionService subscriptionService;

    private final NetworkConfigManager networkConfigManager;

    @Value("${gplink.legacy.mqtt-network-id:${gplink.change-property.mqtt-network-id:}}")
    private String mqttNetworkId;

    @Value("${gplink.legacy.topic-prefix:${gplink.change-property.topic-prefix:IOT/Business}}")
    private String mqttTopicPrefix;

    @Value("${gplink.legacy.default-qos:${gplink.change-property.default-qos:0}}")
    private int mqttQos;

    @Value("${gplink.mqtt-forward.max-legacy-batch-size:1000}")
    private int maxLegacyBatchSize = 1_000;

    @Value("${gplink.mqtt-forward.max-legacy-topics-per-request:100}")
    private int maxLegacyTopicsPerRequest = 100;

    @Value("${gplink.mqtt-forward.max-legacy-cancel-devices:1000}")
    private int maxLegacyCancelDevices = 1_000;

    @PostMapping
    @Operation(summary = "Create legacy gpLink device MQTT forwarding subscriber")
    public Mono<Map<String, Object>> create(@Valid @RequestBody LegacyDeviceMqttSubscriberRequest request) {
        return resolveMqttNetworkId()
            .flatMap(networkId -> createLeases(request, networkId))
            .map(this::createResponse);
    }

    @PostMapping("/batch")
    @Operation(summary = "Batch create legacy gpLink device MQTT forwarding subscribers")
    public Mono<Map<String, Object>> batchCreate(
        @RequestBody List<@Valid LegacyDeviceMqttSubscriberRequest> requests) {
        if (requests == null || requests.isEmpty() || requests.stream().anyMatch(Objects::isNull)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "subscriber list is required"));
        }
        if (requests.size() > maxLegacyBatchSize) {
            return Mono.error(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "subscriber list exceeds maximum size " + maxLegacyBatchSize
            ));
        }
        return resolveMqttNetworkId()
            .flatMapMany(networkId -> Flux
                .fromIterable(requests)
                .concatMap(request -> createLeases(request, networkId))
                .flatMapIterable(responses -> responses))
            .collectList()
            .map(this::createResponse);
    }

    @PostMapping("/cancel-by-device")
    @Operation(summary = "Cancel legacy gpLink device MQTT forwarding subscribers by product and device")
    public Mono<Map<String, Object>> cancelByDevice(@RequestParam String productId,
                                                   @RequestParam String deviceId,
                                                   @RequestParam(required = false) String topicName) {
        List<String> deviceIds = splitRequired(deviceId, "deviceId", maxLegacyCancelDevices);
        List<String> topicNames = splitOptional(topicName, "topicName", maxLegacyTopicsPerRequest);
        Flux<String> cancelled = topicNames.isEmpty()
            ? Flux
                .fromIterable(deviceIds)
                .concatMap(id -> subscriptionService.cancelLegacyByDevice(productId.trim(), id, null))
                .flatMapIterable(ids -> ids)
            : Flux
                .fromIterable(deviceIds)
                .concatMap(id -> Flux
                    .fromIterable(topicNames)
                    .concatMap(topic -> subscriptionService.cancelLegacyByDevice(productId.trim(), id, topic))
                    .flatMapIterable(ids -> ids));

        return cancelled.collectList().map(this::cancelResponse);
    }

    private Mono<List<MqttForwardLeaseResponse>> createLeases(LegacyDeviceMqttSubscriberRequest request,
                                                              String networkId) {
        List<String> topicNames = splitOptional(
            request.getTopicName(),
            "topicName",
            maxLegacyTopicsPerRequest
        );
        if (topicNames.isEmpty()) {
            return subscriptionService
                .createLegacyByDevices(toForwardRequest(request, null, networkId))
                .map(List::of);
        }
        return Flux
            .fromIterable(topicNames)
            .concatMap(topicName -> subscriptionService.createLegacyByDevices(toForwardRequest(request, topicName, networkId)))
            .collectList();
    }

    private DeviceSubscribeRequest toForwardRequest(LegacyDeviceMqttSubscriberRequest request,
                                                    String topicName,
                                                    String networkId) {
        DeviceSubscribeRequest forwardRequest = new DeviceSubscribeRequest();
        forwardRequest.setProductId(request.getProductId());
        forwardRequest.setDeviceIds(List.of(request.getDeviceId()));
        forwardRequest.setWatchedProperties(request.getProperties());
        forwardRequest.setMqttNetworkId(networkId);
        forwardRequest.setMqttTopicPrefix(mqttTopicPrefix);
        forwardRequest.setMqttTopicName(topicName);
        forwardRequest.setMqttQos(mqttQos);
        return forwardRequest;
    }

    private Mono<String> resolveMqttNetworkId() {
        if (mqttNetworkId != null && !mqttNetworkId.isBlank()) {
            return Mono.just(mqttNetworkId.trim());
        }
        return networkConfigManager
            .getAllConfigs()
            .filter(NetworkProperties::isEnabled)
            .filter(config -> DefaultNetworkType.MQTT_CLIENT.getId().equals(config.getType()))
            .map(NetworkProperties::getId)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .collectList()
            .flatMap(ids -> {
                if (ids.size() == 1) {
                    return Mono.just(ids.get(0));
                }
                if (ids.isEmpty()) {
                    return Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Missing MQTT network config: set gplink.legacy.mqtt-network-id or gplink.change-property.mqtt-network-id"
                    ));
                }
                return Mono.error(new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Multiple enabled MQTT_CLIENT networks found: set gplink.legacy.mqtt-network-id or gplink.change-property.mqtt-network-id"
                ));
            });
    }

    private Map<String, Object> createResponse(List<MqttForwardLeaseResponse> responses) {
        List<String> leaseIds = responses
            .stream()
            .map(MqttForwardLeaseResponse::getLeaseId)
            .collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", String.join(",", leaseIds));
        body.put("leaseIds", leaseIds);
        body.put("leases", responses);
        return body;
    }

    private Map<String, Object> cancelResponse(List<String> cancelledIds) {
        List<String> uniqueIds = cancelledIds
            .stream()
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("cancelledIds", uniqueIds);
        result.put("cancelledCount", uniqueIds.size());
        result.put("message", uniqueIds.isEmpty()
            ? "No matching legacy MQTT forward subscription"
            : "Cancelled legacy MQTT forward subscriptions");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", result);
        return body;
    }

    private List<String> splitRequired(String value, String name, int maxSize) {
        List<String> values = splitOptional(value, name, maxSize);
        if (values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is required");
        }
        return values;
    }

    private List<String> splitOptional(String value, String name, int maxSize) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> values = Arrays
            .stream(value.split("[,;]", maxSize + 2))
            .map(String::trim)
            .filter(item -> !item.isEmpty())
            .distinct()
            .collect(Collectors.toList());
        if (values.size() > maxSize) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                name + " exceeds maximum size " + maxSize
            );
        }
        return values;
    }
}
