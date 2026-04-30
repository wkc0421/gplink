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
package org.jetlinks.community.logging.logback;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

class SystemLoggingAppenderTest {

    @Test
    void sanitizePostgresTextShouldRemoveNullCharacters() {
        Assertions.assertEquals("abcdef", SystemLoggingAppender.sanitizePostgresText("abc\u0000def"));
    }

    @Test
    void sanitizePostgresTextShouldKeepOriginalStringWhenNoNullCharacters() {
        String text = "normal log message";

        Assertions.assertSame(text, SystemLoggingAppender.sanitizePostgresText(text));
    }

    @Test
    void sanitizeContextShouldRemoveNullCharactersFromKeysAndValues() {
        Map<String, String> sanitized = SystemLoggingAppender.sanitizeContext(
            Collections.singletonMap("trace\u0000Id", "span\u0000Id"));

        Assertions.assertEquals("spanId", sanitized.get("traceId"));
    }
}
