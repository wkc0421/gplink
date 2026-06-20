package org.jetlinks.community.device.modbus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/device/modbus/access")
@Tag(name = "Modbus simplified access")
public class ModbusAccessController {

    private final ModbusAccessService accessService;

    public ModbusAccessController(ModbusAccessService accessService) {
        this.accessService = accessService;
    }

    @PostMapping("/_save")
    @SaveAction
    @Operation(summary = "Create or update a simplified Modbus access configuration")
    public Mono<ModbusAccessResponse> save(@RequestBody Mono<ModbusAccessRequest> request) {
        return request.flatMap(accessService::save);
    }
}
