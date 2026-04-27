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
package gp.saas.legacy.dto;

import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@AllArgsConstructor(staticName = "of")
public class LegacyOperationResult {

    private final boolean success;

    private final String message;

    public static LegacyOperationResult success(String message) {
        return of(true, message);
    }

    public static LegacyOperationResult failure(String message) {
        return of(false, message);
    }

    public JSONObject toJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", success);
        payload.put("message", message);
        return new JSONObject(payload);
    }
}
