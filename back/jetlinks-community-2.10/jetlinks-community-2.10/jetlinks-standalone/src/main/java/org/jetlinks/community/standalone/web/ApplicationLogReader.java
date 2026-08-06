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

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Component
@RequiredArgsConstructor
public class ApplicationLogReader {

    static final int DEFAULT_LINES = 200;

    static final int MAX_LINES = 10000;

    private static final int READ_BUFFER_SIZE = 64 * 1024;

    private final Environment environment;

    public Mono<ApplicationLogResponse> latest(Integer lines) {
        int maxLines = normalizeLines(lines);
        Path logFile = resolveLogFile().toAbsolutePath().normalize();
        return Mono
            .fromCallable(() -> readLatest(logFile, maxLines))
            .subscribeOn(Schedulers.boundedElastic());
    }

    ApplicationLogResponse readLatest(Path logFile, int maxLines) throws IOException {
        if (!Files.isRegularFile(logFile)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "log file not found: " + logFile);
        }
        if (!Files.isReadable(logFile)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "log file is not readable: " + logFile);
        }

        TailContent tail = tail(logFile, maxLines);
        return new ApplicationLogResponse(
            logFile.toString(),
            tail.getFileSize(),
            Files.getLastModifiedTime(logFile).toMillis(),
            maxLines,
            tail.getLineCount(),
            tail.getContent(),
            tail.isTruncated());
    }

    int normalizeLines(Integer lines) {
        if (lines == null || lines <= 0) {
            return DEFAULT_LINES;
        }
        return Math.min(lines, MAX_LINES);
    }

    Path resolveLogFile() {
        String logFile = firstConfiguredValue("LOG_FILE", "logging.file.name");
        if (StringUtils.hasText(logFile)) {
            return Paths.get(logFile.trim());
        }

        String logPath = firstConfiguredValue("LOG_PATH", "logging.file.path");
        if (StringUtils.hasText(logPath)) {
            return Paths.get(logPath.trim()).resolve("spring.log");
        }

        String logTemp = firstConfiguredValue("LOG_TEMP");
        if (!StringUtils.hasText(logTemp)) {
            logTemp = System.getProperty("java.io.tmpdir", "/tmp");
        }
        return Paths.get(logTemp.trim()).resolve("spring.log");
    }

    private String firstConfiguredValue(String... keys) {
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (StringUtils.hasText(value)) {
                return value;
            }

            value = System.getProperty(key);
            if (StringUtils.hasText(value)) {
                return value;
            }

            value = System.getenv(key);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    static TailContent tail(Path path, int maxLines) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize == 0) {
                return new TailContent(0, "", false, 0);
            }

            long start = findStart(channel, fileSize, maxLines);
            long contentLength = fileSize - start;
            if (contentLength > Integer.MAX_VALUE) {
                throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "requested log content is too large; reduce the number of lines");
            }

            ByteBuffer buffer = ByteBuffer.allocate((int) contentLength);
            channel.position(start);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) {
                    break;
                }
            }

            String content = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            int lineCount = (int) content.lines().count();
            content = removeTrailingLineSeparator(content);
            return new TailContent(lineCount, content, start > 0, fileSize);
        }
    }

    private static long findStart(FileChannel channel, long fileSize, int maxLines) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
        long cursor = fileSize;
        int lineSeparators = 0;
        int byteToRight = -1;
        boolean atFileEnd = true;

        while (cursor > 0) {
            int bytesToRead = (int) Math.min(READ_BUFFER_SIZE, cursor);
            cursor -= bytesToRead;
            buffer.clear();
            buffer.limit(bytesToRead);
            channel.position(cursor);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) {
                    break;
                }
            }

            for (int index = buffer.position() - 1; index >= 0; index--) {
                int current = buffer.get(index) & 0xff;
                boolean lineSeparator = current == '\n' || (current == '\r' && byteToRight != '\n');
                if (lineSeparator) {
                    if (atFileEnd) {
                        atFileEnd = false;
                    } else if (++lineSeparators == maxLines) {
                        return cursor + index + 1;
                    }
                } else if (current != '\r') {
                    atFileEnd = false;
                }
                byteToRight = current;
            }
        }
        return 0;
    }

    private static String removeTrailingLineSeparator(String content) {
        if (content.endsWith("\r\n")) {
            return content.substring(0, content.length() - 2);
        }
        if (content.endsWith("\n") || content.endsWith("\r")) {
            return content.substring(0, content.length() - 1);
        }
        return content;
    }

    @Getter
    static final class TailContent {

        private final int lineCount;

        private final String content;

        private final boolean truncated;

        private final long fileSize;

        private TailContent(int lineCount, String content, boolean truncated, long fileSize) {
            this.lineCount = lineCount;
            this.content = content;
            this.truncated = truncated;
            this.fileSize = fileSize;
        }
    }
}
