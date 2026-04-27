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
package gp.saas.legacy.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.util.StringUtils;

import java.util.List;

public final class LegacyMetadataUtils {

    private LegacyMetadataUtils() {
    }

    public static JSONObject parseMetadata(String metadata) {
        if (!StringUtils.hasText(metadata)) {
            return new JSONObject();
        }
        Object parsed = JSON.parse(metadata);
        if (parsed instanceof JSONObject) {
            return (JSONObject) parsed;
        }
        throw new IllegalArgumentException("Legacy metadata must be a JSON object");
    }

    public static JSONArray getProperties(String metadata) {
        return getArray(parseMetadata(metadata), "properties");
    }

    public static JSONArray getFunctions(String metadata) {
        return getArray(parseMetadata(metadata), "functions");
    }

    public static JSONArray getArray(JSONObject metadata, String key) {
        JSONArray array = metadata.getJSONArray(key);
        return array == null ? new JSONArray() : array;
    }

    public static String replaceProperties(String metadata, List<JSONObject> properties) {
        JSONObject json = parseMetadata(metadata);
        JSONArray array = new JSONArray();
        array.addAll(properties);
        json.put("properties", array);
        if (!json.containsKey("functions")) {
            json.put("functions", new JSONArray());
        }
        if (!json.containsKey("events")) {
            json.put("events", new JSONArray());
        }
        if (!json.containsKey("tags")) {
            json.put("tags", new JSONArray());
        }
        return json.toJSONString();
    }
}
