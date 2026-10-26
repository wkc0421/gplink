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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    @Test
    void shouldCompleteAllMissingSortIndexesInImportDisplayOrder() {
        List<JSONObject> properties = new ArrayList<>();
        for (int i = 0; i < 34; i++) {
            JSONObject property = new JSONObject();
            property.put("id", "p" + i);
            properties.add(property);
        }
        List<JSONObject> completed = LegacyMetadataUtils.completePropertySortIndexes(properties);
        for (int i = 0; i < 34; i++) {
            assertEquals("p" + i, completed.get(i).getString("id"));
            assertEquals(34 - i, completed.get(i).getIntValue("sortsIndex"));
            assertTrue(!properties.get(i).containsKey("sortsIndex"));
        }
    }

    @Test
    void shouldPreserveExplicitSortIndexesAndAvoidCollisionsWithoutMutatingInput() {
        List<JSONObject> properties = List.of(
            JSONObject.parseObject("{\"id\":\"a\",\"sortsIndex\":null}"),
            JSONObject.parseObject("{\"id\":\"b\",\"sortsIndex\":\"3\"}"),
            JSONObject.parseObject("{\"id\":\"c\",\"sortsIndex\":0}"),
            JSONObject.parseObject("{\"id\":\"d\",\"sortsIndex\":\"  \"}")
        );
        String snapshot = JSONObject.toJSONString(properties);
        List<JSONObject> completed = LegacyMetadataUtils.completePropertySortIndexes(properties);
        assertEquals(4, completed.get(0).get("sortsIndex"));
        assertEquals("3", completed.get(1).get("sortsIndex"));
        assertEquals(0, completed.get(2).get("sortsIndex"));
        assertEquals(2, completed.get(3).get("sortsIndex"));
        assertEquals(snapshot, JSONObject.toJSONString(properties));
        assertEquals(completed, LegacyMetadataUtils.completePropertySortIndexes(completed));
    }

    @Test
    void shouldCompleteBlankAndInvalidValues() {
        List<Object> invalid = Arrays.asList(null, "", " ", -1, "NaN", Double.POSITIVE_INFINITY, false);
        List<JSONObject> properties = new ArrayList<>();
        for (Object value : invalid) {
            JSONObject property = new JSONObject();
            property.put("sortsIndex", value);
            properties.add(property);
        }
        List<JSONObject> completed = LegacyMetadataUtils.completePropertySortIndexes(properties);
        for (int i = 0; i < completed.size(); i++) {
            assertEquals(completed.size() - i, completed.get(i).getIntValue("sortsIndex"));
        }
    }
}
