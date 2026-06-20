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
package org.jetlinks.community.rule.engine.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.crud.service.ReactiveCrudService;
import org.hswebframework.web.crud.web.reactive.ReactiveServiceCrudController;
import org.jetlinks.community.rule.engine.alarm.numeric.NumericAlarmRuleService;
import org.jetlinks.community.rule.engine.entity.NumericAlarmRuleEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/alarm/numeric-rule")
@Resource(id = "alarm-config", name = "Numeric alarm rule")
@Authorize
@Tag(name = "Numeric alarm rule")
@AllArgsConstructor
public class NumericAlarmRuleController implements ReactiveServiceCrudController<NumericAlarmRuleEntity, String> {

    private final NumericAlarmRuleService service;

    @Override
    public ReactiveCrudService<NumericAlarmRuleEntity, String> getService() {
        return service;
    }

    @PostMapping("/{id}/_enable")
    @Operation(summary = "Enable numeric alarm rule")
    public Mono<Void> enable(@PathVariable String id) {
        return service.enable(id);
    }

    @PostMapping("/{id}/_disable")
    @Operation(summary = "Disable numeric alarm rule")
    public Mono<Void> disable(@PathVariable String id) {
        return service.disable(id);
    }
}
