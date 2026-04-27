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

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyMetadataUtilsTest {

    @Test
    void shouldReturnEmptyObjectForBlankMetadata() {
        assertTrue(LegacyMetadataUtils.parseMetadata(null).isEmpty());
        assertTrue(LegacyMetadataUtils.parseMetadata(" ").isEmpty());
    }

    @Test
    void shouldExtractLegacySections() {
        String metadata = "{"
            + "\"properties\":[{\"id\":\"temp\"}],"
            + "\"functions\":[{\"id\":\"read\"}]"
            + "}";

        JSONObject parsed = LegacyMetadataUtils.parseMetadata(metadata);
        JSONArray properties = LegacyMetadataUtils.getProperties(metadata);
        JSONArray functions = LegacyMetadataUtils.getFunctions(metadata);

        assertEquals("temp", parsed.getJSONArray("properties").getJSONObject(0).getString("id"));
        assertEquals("temp", properties.getJSONObject(0).getString("id"));
        assertEquals("read", functions.getJSONObject(0).getString("id"));
    }

    @Test
    void shouldDefaultMissingArraysToEmpty() {
        JSONObject parsed = LegacyMetadataUtils.parseMetadata("{\"events\":[]}");

        assertTrue(LegacyMetadataUtils.getArray(parsed, "properties").isEmpty());
        assertTrue(LegacyMetadataUtils.getArray(parsed, "functions").isEmpty());
    }

    @Test
    void shouldRejectNonObjectMetadata() {
        assertThrows(IllegalArgumentException.class, () -> LegacyMetadataUtils.parseMetadata("[]"));
    }
}
