package org.jetlinks.community.device.web;

import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.community.device.service.data.HistoryStatisticsManagementService;
import org.jetlinks.community.device.service.data.HistoryStatisticsManagementService.*;
import org.jetlinks.community.timescaledb.thing.counter.CounterTask;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/** Scope-specific permissions are checked explicitly by the facade, including read-only POST requests. */
@RestController
@RequestMapping("/device/history-statistics")
@Authorize
public class HistoryStatisticsController {
    private final HistoryStatisticsManagementService service;

    public HistoryStatisticsController(HistoryStatisticsManagementService service) { this.service = service; }

    @PostMapping("/_query")
    public Mono<Page<Row>> query(@RequestBody Query request) { return service.query(request); }

    @PostMapping("/candidates/_query")
    public Mono<Page<Row>> candidates(@RequestBody CandidateQuery request) { return service.candidates(request); }

    @PostMapping("/_batch")
    public Mono<BatchResult> batch(@RequestBody BatchRequest request) { return service.batch(request); }

    @PostMapping("/device/{deviceId}/{propertyId}/_rebuild")
    public Mono<Map<String, String>> rebuild(@PathVariable String deviceId, @PathVariable String propertyId,
                                             @RequestBody DeviceProductHistoryStatisticsController.RebuildRequest request) {
        return service.rebuildDevice(deviceId, propertyId, request == null ? null : request.getFrom(), request == null ? null : request.getTo());
    }

    @GetMapping("/device/{deviceId}/tasks/{taskId}")
    public Mono<CounterTask> task(@PathVariable String deviceId, @PathVariable String taskId) {
        return service.deviceTask(deviceId, taskId, false);
    }

    @PostMapping("/device/{deviceId}/tasks/{taskId}/_cancel")
    public Mono<CounterTask> cancel(@PathVariable String deviceId, @PathVariable String taskId) {
        return service.deviceTask(deviceId, taskId, true);
    }
}
