/*
 * Copyright 2025 JetLinks https://www.jetlinks.cn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gp.saas.legacy.web;

import com.alibaba.fastjson.JSONObject;
import gp.saas.legacy.dto.LegacyOperationResult;
import gp.saas.legacy.service.LegacySystemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/system")
@Authorize(ignore = true)
@Tag(name = "Legacy System API")
public class LegacySystemController {

    static final String LEGACY_PASSWORD = "yada8888";
    static final String INVALID_PASSWORD_MESSAGE = "无效的访问密码";

    private final LegacySystemService legacySystemService;

    public LegacySystemController(LegacySystemService legacySystemService) {
        this.legacySystemService = legacySystemService;
    }

    @GetMapping("/gc")
    @Operation(summary = "Legacy GC endpoint")
    public Mono<JSONObject> gc(@RequestParam String password) {
        if (!isValidPassword(password)) {
            return Mono.empty();
        }
        return Mono.fromSupplier(legacySystemService::runGc);
    }

    @GetMapping("/soft-restart")
    @Operation(summary = "Legacy soft restart endpoint")
    public Mono<JSONObject> softRestart(@RequestParam String password) {
        if (!isValidPassword(password)) {
            return Mono.just(LegacyOperationResult.failure(INVALID_PASSWORD_MESSAGE).toJson());
        }
        return Mono.fromSupplier(legacySystemService::softRestart);
    }

    @GetMapping("/memory-analysis")
    @Operation(summary = "Legacy memory analysis endpoint")
    public Mono<JSONObject> memoryAnalysis(@RequestParam String password) {
        if (!isValidPassword(password)) {
            return Mono.just(LegacyOperationResult.failure(INVALID_PASSWORD_MESSAGE).toJson());
        }
        return Mono.fromSupplier(legacySystemService::memoryAnalysis);
    }

    private boolean isValidPassword(String password) {
        return LEGACY_PASSWORD.equals(password);
    }
}
