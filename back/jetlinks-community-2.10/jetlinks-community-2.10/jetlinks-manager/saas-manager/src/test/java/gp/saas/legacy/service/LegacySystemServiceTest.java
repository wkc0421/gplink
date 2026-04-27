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
package gp.saas.legacy.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegacySystemServiceTest {

    @Test
    void shouldExposeSoftRestartMetrics() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeanDefinitionCount()).thenReturn(12);

        LegacySystemService service = new LegacySystemService(applicationContext);

        JSONObject result = service.softRestart();

        assertTrue(result.getBooleanValue("success"));
        assertTrue(result.getBooleanValue("refreshed"));
        assertEquals(12, result.getIntValue("beanDefinitionCount"));
        assertTrue(result.getLongValue("durationMs") >= 0);
    }

    @Test
    void shouldExposeMemoryAnalysisSections() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        LegacySystemService service = new LegacySystemService(applicationContext);

        JSONObject result = service.memoryAnalysis();

        assertTrue(result.getBooleanValue("success"));
        assertTrue(result.containsKey("jvm"));
        assertTrue(result.containsKey("heapMemory"));
        assertTrue(result.containsKey("nonHeapMemory"));
        assertTrue(result.containsKey("memoryPools"));
        assertTrue(result.containsKey("garbageCollectors"));
        assertTrue(result.containsKey("threads"));
        assertTrue(result.containsKey("classLoading"));
        assertTrue(result.get("memoryPools") instanceof JSONArray);
    }

    @Test
    void shouldExposeGcMetrics() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        LegacySystemService service = new LegacySystemService(applicationContext);

        JSONObject result = service.runGc();

        assertTrue(result.getBooleanValue("success"));
        assertTrue(result.containsKey("beforeUsedMemory"));
        assertTrue(result.containsKey("afterUsedMemory"));
        assertTrue(result.containsKey("releasedMemory"));
        assertTrue(result.getLongValue("durationMs") >= 0);
    }
}
