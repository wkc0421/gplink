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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyOperationResultTest {

    @Test
    void shouldRenderSuccessPayload() {
        LegacyOperationResult result = LegacyOperationResult.success("done");

        assertTrue(result.isSuccess());
        assertEquals("done", result.getMessage());
        assertTrue(result.toJson().getBooleanValue("success"));
        assertEquals("done", result.toJson().getString("message"));
    }

    @Test
    void shouldRenderFailurePayload() {
        LegacyOperationResult result = LegacyOperationResult.failure("invalid");

        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getMessage());
        assertFalse(result.toJson().getBooleanValue("success"));
        assertEquals("invalid", result.toJson().getString("message"));
    }
}
