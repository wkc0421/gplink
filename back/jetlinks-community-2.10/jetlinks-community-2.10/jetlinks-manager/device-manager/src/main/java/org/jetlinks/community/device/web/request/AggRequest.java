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
package org.jetlinks.community.device.web.request;

import lombok.Getter;
import lombok.Setter;
import org.jetlinks.community.device.service.data.DeviceDataService;

import java.util.List;

@Getter
@Setter
public class AggRequest {
    private List<DeviceDataService.DevicePropertyAggregation> columns;

    private DeviceDataService.AggregationRequest query;

    public void validate(){
        if (columns == null || columns.isEmpty() || query == null || columns.stream().anyMatch(java.util.Objects::isNull)) {
            throw new org.hswebframework.web.exception.BusinessException("columns 和 query 不能为空", 400);
        }
        columns.forEach(DeviceDataService.DevicePropertyAggregation::validate);
    }
}
