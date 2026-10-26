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
package org.jetlinks.community.network.tcp.parser.strateies;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.parsetools.RecordParser;
import org.jetlinks.community.network.tcp.parser.PayloadParser;
import org.jetlinks.community.network.tcp.parser.PayloadParserBuilderStrategy;
import org.jetlinks.community.network.tcp.parser.PayloadParserType;
import org.jetlinks.community.network.tcp.parser.PayloadParserLimits;
import org.jetlinks.core.utils.Reactors;
import org.jetlinks.community.ValueObject;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.function.Supplier;

public abstract class VertxPayloadParserBuilder implements PayloadParserBuilderStrategy {
    @Override
    public abstract PayloadParserType getType();

    protected abstract Supplier<RecordParser> createParser(ValueObject config);

    @Override
    public Supplier<PayloadParser> buildLazy(ValueObject config) {
        int maximum = PayloadParserLimits.maxFrameLength(config);
        Supplier<RecordParser> parser = createParser(config);
        return () -> new RecordPayloadParser(() -> parser.get().maxRecordSize(maximum), maximum);
    }

    static class RecordPayloadParser implements PayloadParser {
        private final Supplier<RecordParser> recordParserSupplier;
        private final int maximum;
        private final Sinks.Many<Buffer> sink = Reactors.createMany();

        private RecordParser recordParser;
        private boolean closed;

        public RecordPayloadParser(Supplier<RecordParser> recordParserSupplier, int maximum) {
            this.recordParserSupplier = recordParserSupplier;
            this.maximum = maximum;
            reset();
        }

        @Override
        public synchronized void handle(Buffer buffer) {
            if (closed) return;
            try {
                recordParser.handle(buffer);
            } catch (RuntimeException error) {
                close();
                throw error;
            }
        }

        @Override
        public Flux<Buffer> handlePayload() {
            return sink.asFlux();
        }

        @Override
        public synchronized void close() {
            closed = true;
            recordParser = null;
            sink.tryEmitComplete();
        }

        @Override
        public synchronized void reset() {
            if (closed) return;
            this.recordParser = recordParserSupplier.get();
            this.recordParser.handler(payload -> {
                PayloadParserLimits.check(payload.length(), maximum);
                sink.emitNext(payload, Reactors.emitFailureHandler());
            });
        }
    }

}
