package gp.saas.legacy.web;

import gp.saas.apitoken.service.ApiTokenScopeService;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.community.device.entity.*;
import org.jetlinks.community.device.service.*;
import org.jetlinks.community.device.service.data.*;
import org.jetlinks.community.timescaledb.TimescaleDBUtils;
import org.jetlinks.community.timescaledb.thing.simulation.*;
import org.jetlinks.core.metadata.*;
import org.jetlinks.core.metadata.types.NumberType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.*;
import java.util.*;

@RestController
@RequestMapping("/device-data/simulation")
@Resource(id="device-instance",name="Device instance")
@Authorize
public class DeviceDataSimulationController {
    private final LocalDeviceInstanceService devices;
    private final LocalDeviceProductService products;
    private final DeviceDataService data;
    private final DeviceDataStorageProperties storage;
    private final ApiTokenScopeService scope;
    private final ObjectProvider<SimulationTaskService> services;

    public DeviceDataSimulationController(LocalDeviceInstanceService devices, LocalDeviceProductService products,
        DeviceDataService data, DeviceDataStorageProperties storage, ApiTokenScopeService scope, ObjectProvider<SimulationTaskService> services) {
        this.devices=devices; this.products=products; this.data=data; this.storage=storage; this.scope=scope; this.services=services;
        SimulationTaskService service=services.getIfAvailable();
        if(service!=null) service.setMetadataValidator(this::validateStoredSpec);
    }

    public static class Request {
        public String deviceId,propertyId,requestId,mode,min,max,initial,increment;
        public long startTime,endTime;
        public int intervalMinutes;
        public boolean snapshots;
    }

    @GetMapping("/devices/{deviceId}/properties") @QueryAction
    public Flux<SimulationSpec> properties(@PathVariable String deviceId) {
        return device(deviceId).flatMap(d -> products.findById(d.getProductId())
            .zipWith(devices.getMetadata(deviceId)).zipWith(products.getMetadata(d.getProductId()))
            .map(pair -> new Context(d,pair.getT1().getT1(),pair.getT1().getT2(),pair.getT2())))
            .flatMapMany(ctx -> Flux.fromIterable(ctx.metadata.getProperties())
                .filter(p -> p.getValueType() instanceof NumberType)
                .map(p -> property(ctx,p)));
    }

    @PostMapping("/_preview") @QueryAction
    public Mono<Map<String,Object>> preview(@RequestBody Request request) {
        return resolve(request).flatMap(s -> service().preview(s));
    }

    @PostMapping("/tasks") @SaveAction
    public Mono<Map<String,Object>> submit(@RequestBody Request request) {
        if(request.requestId==null || !request.requestId.matches("[A-Za-z0-9_-]{8,80}")) return Mono.error(bad("缺少有效幂等请求ID"));
        return resolve(request).flatMap(s -> Authentication.currentReactive().flatMap(auth -> {
            String creator=auth.getUser().getId();
            return service().existing(creator,s.requestId).flatMap(existing -> {
                SimulationSpec old=(SimulationSpec)existing.get("request");
                if(!sameRequest(old,s)) return Mono.error(bad("幂等请求ID已被其他参数使用"));
                return authorizeTask(existing);
            }).switchIfEmpty(Mono.defer(() -> service().preview(s).flatMap(check -> {
                if(((Number)check.get("existing")).longValue()>0) return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,"所选区间已有数据，不能补录"));
                if(s.snapshots && !Boolean.TRUE.equals(check.get("snapshotEnabled")) && !auth.hasPermission("device-instance","save")) {
                    return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,"启用设备快照配置需要设备写权限"));
                }
                s.allowEnableSnapshots=auth.hasPermission("device-instance","save");
                if(!Boolean.TRUE.equals(check.get("canInitialize"))) return Mono.error(bad("聚合结构不兼容，需先修复"));
                Mono<Void> init=Boolean.TRUE.equals(check.get("storageReady")) ? Mono.empty()
                    : products.getMetadata(s.productId).flatMap(metadata -> data.registerMetadata(s.productId,metadata));
                return init.then(service().submit(s,creator));
            })));
        }));
    }

    @GetMapping("/tasks") @QueryAction
    public Flux<Map<String,Object>> list(@RequestParam(defaultValue="0") int pageIndex,@RequestParam(defaultValue="20") int pageSize) {
        return Authentication.currentReactive().flatMapMany(auth -> service().list(auth.getUser().getId(),pageIndex,pageSize)
            .concatMap(task -> authorizeTask(task).onErrorResume(ResponseStatusException.class,e -> e.getStatusCode().value()==403 || e.getStatusCode().value()==404 ? Mono.empty() : Mono.error(e))));
    }

    @GetMapping("/tasks/{taskId}") @QueryAction
    public Mono<Map<String,Object>> task(@PathVariable String taskId) {
        return service().task(taskId).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"任务不存在")))
            .flatMap(this::authorizeTask);
    }

    @PostMapping("/tasks/{taskId}/_retry") @SaveAction
    public Mono<Map<String,Object>> retry(@PathVariable String taskId) {
        return task(taskId).flatMap(task -> {
            SimulationSpec old=(SimulationSpec)task.get("request");
            Request request=new Request(); request.deviceId=old.deviceId; request.propertyId=old.propertyId;
            request.startTime=old.startTime; request.endTime=old.endTime; request.intervalMinutes=old.intervalMinutes;
            request.mode=old.mode; request.min=old.min; request.max=old.max; request.initial=old.initial; request.increment=old.increment;
            request.snapshots=old.snapshots;
            return resolve(request).flatMap(current -> {
                if(!Objects.equals(old.type,current.type) || old.scale!=current.scale || !Objects.equals(old.lower,current.lower)
                    || !Objects.equals(old.upper,current.upper) || !Objects.equals(old.rawTable,current.rawTable)) return Mono.error(bad("物模型或存储配置已变化，不能重试原任务"));
                return service().preview(current).flatMap(check -> Authentication.currentReactive().flatMap(auth -> {
                    if(old.snapshots && !Boolean.TRUE.equals(check.get("snapshotEnabled"))) {
                        if(!old.allowEnableSnapshots || !auth.hasPermission("device-instance","save"))
                            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,"重新启用设备快照配置需要设备写权限"));
                        return scope.requireDevice(old.deviceId,old.productId).then(service().retry(taskId));
                    }
                    return service().retry(taskId);
                }));
            });
        });
    }

    private Mono<Map<String,Object>> authorizeTask(Map<String,Object> task) {
        SimulationSpec s=(SimulationSpec)task.get("request");
        return Authentication.currentReactive().flatMap(auth -> {
            if(!auth.getUser().getId().equals(task.get("creator"))) return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,"无权查看该任务"));
            return device(s.deviceId).thenReturn(task);
        });
    }

    private Mono<SimulationSpec> resolve(Request r) {
        if(r==null || r.deviceId==null || r.propertyId==null) return Mono.error(bad("请选择设备和采集项"));
        return device(r.deviceId).flatMap(d -> products.findById(d.getProductId())
            .zipWith(devices.getMetadata(r.deviceId)).zipWith(products.getMetadata(d.getProductId()))
            .switchIfEmpty(Mono.error(bad("产品或物模型不存在")))
            .map(pair -> {
                Context c=new Context(d,pair.getT1().getT1(),pair.getT1().getT2(),pair.getT2());
                String policy=Objects.toString(d.getConfiguration()==null ? null : d.getConfiguration().get("storePolicy"),"");
                if(policy.isBlank()) policy=c.product.getStorePolicy();
                if(policy==null || policy.isBlank()) policy=storage.getDefaultPolicy();
                if(!"timescaledb-row".equals(policy)) throw bad("仅支持TimescaleDB行模式存储");
                PropertyMetadata p=c.metadata.getPropertyOrNull(r.propertyId);
                if(p==null || !(p.getValueType() instanceof NumberType)) throw bad("仅允许数值型采集项");
                SimulationSpec s=property(c,p);
                s.requestId=r.requestId; s.startTime=r.startTime; s.endTime=r.endTime; s.intervalMinutes=r.intervalMinutes;
                s.mode=r.mode; s.min=r.min; s.max=r.max; s.initial=r.initial; s.increment=r.increment; s.snapshots=r.snapshots;
                return s;
            }));
    }

    private Mono<Void> validateStoredSpec(SimulationSpec old) {
        Request r=new Request();r.deviceId=old.deviceId;r.propertyId=old.propertyId;
        return resolve(r).doOnNext(current -> {
            if(!Objects.equals(old.type,current.type)||old.scale!=current.scale||!Objects.equals(old.lower,current.lower)
                ||!Objects.equals(old.upper,current.upper)||!Objects.equals(old.productId,current.productId)
                ||!Objects.equals(old.rawTable,current.rawTable)||old.snapshots&&!current.snapshotEligible)
                throw bad("设备物模型或存储配置已变化，任务已停止");
        }).then();
    }

    private SimulationSpec property(Context c,PropertyMetadata p) {
        NumberType<?> type=(NumberType<?>)p.getValueType();
        SimulationSpec s=new SimulationSpec();
        s.deviceId=c.device.getId(); s.productId=c.product.getId(); s.propertyId=p.getId(); s.name=p.getName(); s.type=type.getId();
        s.rawTable=TimescaleDBUtils.getTableName(storage.getMetric().getPropertyPrefix()+s.productId);
        s.scale=Set.of("int","long").contains(s.type) ? 0 : type.getScale()==null ? 2 : type.getScale();
        s.lower=type.getMin()==null ? null : type.getMin().toString(); s.upper=type.getMax()==null ? null : type.getMax().toString();
        s.unit=type.getUnit()==null ? "" : type.getUnit().getSymbol();
        s.snapshotEligible=true;
        return s;
    }

    private Mono<DeviceInstanceEntity> device(String id) {
        return devices.findById(id).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"设备不存在")))
            .flatMap(d -> scope.requireDevice(id,d.getProductId()).thenReturn(d));
    }
    private SimulationTaskService service() {
        SimulationTaskService service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模拟补录服务不可用，请检查TimescaleDB配置");
        return service;
    }
    private record Context(DeviceInstanceEntity device,DeviceProductEntity product,DeviceMetadata metadata,DeviceMetadata productMetadata) {}
    static boolean sameRequest(SimulationSpec a,SimulationSpec b) {
        return a.sameRequest(b);
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<Void> invalid(IllegalArgumentException error) { return Mono.error(bad(error.getMessage())); }
}
