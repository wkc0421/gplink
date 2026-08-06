package org.jetlinks.community.device.modbus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/modbus/polling")
@Authorize
@Resource(id = "modbus-polling", name = "Modbus轮询")
@Tag(name = "Modbus轮询")
public class ModbusPollingController {

    private final ModbusPollingCoordinator coordinator;

    public ModbusPollingController(ModbusPollingCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @GetMapping("/status")
    @QueryAction
    @Operation(summary = "查询Modbus轮询配置刷新状态")
    public Mono<Map<String, Object>> status() {
        return Mono.fromSupplier(coordinator::getReloadStatus);
    }
}
