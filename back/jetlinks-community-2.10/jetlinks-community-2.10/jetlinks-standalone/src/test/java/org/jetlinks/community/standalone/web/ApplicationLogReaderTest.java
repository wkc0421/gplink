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
package org.jetlinks.community.standalone.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationLogReaderTest {

    @Test
    void shouldReadLatestLines() throws IOException {
        Path file = Files.createTempFile("gplink-log", ".log");
        try {
            Files.writeString(
                file,
                String.join(System.lineSeparator(), List.of("one", "two", "three", "four", "five"))
                    + System.lineSeparator(),
                StandardCharsets.UTF_8);

            ApplicationLogReader.TailContent tail = ApplicationLogReader.tail(file, 3);

            assertEquals(3, tail.getLineCount());
            assertEquals(String.join(System.lineSeparator(), "three", "four", "five"), tail.getContent());
            assertTrue(tail.isTruncated());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void shouldReadLatestLinesAcrossMultipleBuffers() throws IOException {
        Path file = Files.createTempFile("gplink-large-log", ".log");
        try {
            List<String> allLines = java.util.stream.IntStream
                .range(0, 5000)
                .mapToObj(index -> String.format("%05d-%080d", index, index))
                .collect(java.util.stream.Collectors.toList());
            Files.writeString(
                file,
                String.join("\n", allLines) + "\n",
                StandardCharsets.UTF_8);

            ApplicationLogReader.TailContent tail = ApplicationLogReader.tail(file, 1200);

            assertEquals(1200, tail.getLineCount());
            assertEquals(String.join("\n", allLines.subList(3800, 5000)), tail.getContent());
            assertTrue(tail.isTruncated());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void shouldHandleCrLfLineSeparators() throws IOException {
        Path file = Files.createTempFile("gplink-crlf-log", ".log");
        try {
            Files.writeString(file, "one\r\ntwo\r\nthree\r\n", StandardCharsets.UTF_8);

            ApplicationLogReader.TailContent tail = ApplicationLogReader.tail(file, 2);

            assertEquals(2, tail.getLineCount());
            assertEquals("two\r\nthree", tail.getContent());
            assertTrue(tail.isTruncated());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void shouldNormalizeLineLimit() {
        ApplicationLogReader reader = new ApplicationLogReader(new MockEnvironment());

        assertEquals(ApplicationLogReader.DEFAULT_LINES, reader.normalizeLines(null));
        assertEquals(ApplicationLogReader.DEFAULT_LINES, reader.normalizeLines(0));
        assertEquals(20, reader.normalizeLines(20));
        assertEquals(ApplicationLogReader.MAX_LINES, reader.normalizeLines(ApplicationLogReader.MAX_LINES + 1));
    }

    @Test
    void shouldResolveConfiguredLogFile() {
        Path logFile = Paths.get("logs", "gplink.log");
        MockEnvironment environment = new MockEnvironment()
            .withProperty("LOG_FILE", logFile.toString());

        ApplicationLogReader reader = new ApplicationLogReader(environment);

        assertEquals(logFile, reader.resolveLogFile());
    }
}
