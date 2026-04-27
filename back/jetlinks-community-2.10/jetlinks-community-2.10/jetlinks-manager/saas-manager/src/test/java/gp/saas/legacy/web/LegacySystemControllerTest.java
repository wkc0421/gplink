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
import gp.saas.legacy.service.LegacySystemService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacySystemControllerTest {

    private final LegacySystemService service = Mockito.mock(LegacySystemService.class);
    private final LegacySystemController controller = new LegacySystemController(service);

    @Test
    void shouldReturnEmptyForGcWhenPasswordIsInvalid() {
        StepVerifier.create(controller.gc("bad-password"))
                    .verifyComplete();

        verify(service, never()).runGc();
    }

    @Test
    void shouldReturnGcPayloadWhenPasswordIsValid() {
        JSONObject payload = new JSONObject();
        payload.put("success", true);
        payload.put("releasedMemory", 128L);
        when(service.runGc()).thenReturn(payload);

        StepVerifier.create(controller.gc("yada8888"))
                    .assertNext(result -> {
                        assertTrue(result.getBooleanValue("success"));
                        assertEquals(128L, result.getLongValue("releasedMemory"));
                    })
                    .verifyComplete();

        verify(service).runGc();
    }

    @Test
    void shouldRejectSoftRestartWhenPasswordIsInvalid() {
        StepVerifier.create(controller.softRestart("bad-password"))
                    .assertNext(result -> {
                        assertTrue(!result.getBooleanValue("success"));
                        assertEquals("无效的访问密码", result.getString("message"));
                    })
                    .verifyComplete();

        verify(service, never()).softRestart();
    }

    @Test
    void shouldReturnSoftRestartPayloadWhenPasswordIsValid() {
        JSONObject payload = new JSONObject();
        payload.put("success", true);
        payload.put("beanDefinitionCount", 42);
        when(service.softRestart()).thenReturn(payload);

        StepVerifier.create(controller.softRestart("yada8888"))
                    .assertNext(result -> {
                        assertTrue(result.getBooleanValue("success"));
                        assertEquals(42, result.getIntValue("beanDefinitionCount"));
                    })
                    .verifyComplete();

        verify(service).softRestart();
    }

    @Test
    void shouldRejectMemoryAnalysisWhenPasswordIsInvalid() {
        StepVerifier.create(controller.memoryAnalysis("bad-password"))
                    .assertNext(result -> {
                        assertTrue(!result.getBooleanValue("success"));
                        assertEquals("无效的访问密码", result.getString("message"));
                    })
                    .verifyComplete();

        verify(service, never()).memoryAnalysis();
    }

    @Test
    void shouldReturnMemoryAnalysisPayloadWhenPasswordIsValid() {
        JSONObject payload = new JSONObject();
        payload.put("success", true);
        payload.put("jvm", new JSONObject());
        when(service.memoryAnalysis()).thenReturn(payload);

        Mono<JSONObject> result = controller.memoryAnalysis("yada8888");

        StepVerifier.create(result)
                    .assertNext(response -> {
                        assertTrue(response.getBooleanValue("success"));
                        assertTrue(response.containsKey("jvm"));
                    })
                    .verifyComplete();

        verify(service).memoryAnalysis();
    }
}
