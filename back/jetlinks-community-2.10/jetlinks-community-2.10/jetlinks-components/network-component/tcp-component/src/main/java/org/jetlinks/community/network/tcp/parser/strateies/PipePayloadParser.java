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
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.community.network.tcp.parser.PayloadParser;
import org.jetlinks.community.network.tcp.parser.PayloadParserLimits;
import org.jetlinks.core.utils.Reactors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * <pre>{@code
 * PipePayloadParser payloadParser =
 * //先读取4个字节
 * new PipePayloadParser()
 *       .fixed(4)
 *        //第一次读取数据
 *       .handler((buffer,parser) -> {
 *            //4字节转为int，表示接下来要读取的包长度
 *            int len = buffer.getInt(0);
 *            parser
 *              .result(buffer) //将已读取的4字节设置到结果中
 *              .fixed(len);//设置接下来要读取的字节长度
 *         })
 *         //第二次读取数据
 *       .handler((buffer,parser) -> parser
 *                .result(buffer) //设置结果
 *                .complete() //完成本次读取，输出结果，开始下一次读取
 *               );
 * }</pre>
 */
@Slf4j
public class PipePayloadParser implements PayloadParser {

    private final static AtomicIntegerFieldUpdater<PipePayloadParser> CURRENT_PIPE =
        AtomicIntegerFieldUpdater.newUpdater(PipePayloadParser.class, "currentPipe");

    private final Sinks.Many<Buffer> sink = Reactors.createMany();

    private final List<BiConsumer<Buffer, PipePayloadParser>> pipe = new CopyOnWriteArrayList<>();

    private final List<Buffer> result = new CopyOnWriteArrayList<>();

    private volatile RecordParser recordParser;

    private Function<Buffer, Buffer> directMapper;

    private Consumer<RecordParser> firstInit;

    private volatile int currentPipe;
    private final int maxFrameLength;
    private long resultLength;
    private long directBytes;
    private boolean closed;

    public PipePayloadParser() {
        this(PayloadParserLimits.DEFAULT_MAX_FRAME_LENGTH);
    }

    public PipePayloadParser(int maxFrameLength) {
        if (maxFrameLength < 1 || maxFrameLength > PayloadParserLimits.MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("invalid maxFrameLength: " + maxFrameLength);
        }
        this.maxFrameLength = maxFrameLength;
    }

    public Buffer newBuffer() {
        return Buffer.buffer();
    }

    public PipePayloadParser result(String buffer) {
        return result(Buffer.buffer(buffer));
    }

    public PipePayloadParser result(byte[] buffer) {
        return result(Buffer.buffer(buffer));
    }

//    public PipePayloadParser handler(Consumer<Buffer> handler) {
//
//        return handler((payloadParser, buffer) -> handler.accept(buffer));
//    }

    public PipePayloadParser handler(BiConsumer<Buffer, PipePayloadParser> handler) {
        pipe.add(handler);
        return this;
    }


    public PipePayloadParser delimited(String delimited) {
        if (delimited == null || delimited.isEmpty()) {
            throw new IllegalArgumentException("delimiter must not be empty");
        }
        ensureRemaining(1);
        if (recordParser == null) {
            setParser(RecordParser.newDelimited(delimited));
            firstInit = (parser -> parser.delimitedMode(delimited));
            return this;
        }
        recordParser.delimitedMode(delimited);
        updateRecordLimit();
        return this;
    }

    public PipePayloadParser fixed(int size) {
        ensureRemaining(size);
        if (size == 0) {
            complete();
            return this;
        }
        if (recordParser == null) {
            setParser(RecordParser.newFixed(size));
            firstInit = (parser -> parser.fixedSizeMode(size));
            return this;
        }
        recordParser.fixedSizeMode(size);
        updateRecordLimit();
        return this;
    }

    public PipePayloadParser direct(Function<Buffer, Buffer> mapper) {
        this.directMapper = mapper;
        return this;
    }

    private BiConsumer<Buffer, PipePayloadParser> getNextHandler() {
        int i = CURRENT_PIPE.getAndIncrement(this);
        if (i < pipe.size()) {
            return pipe.get(i);
        }
        CURRENT_PIPE.set(this, 0);
        return pipe.get(0);
    }

    private void setParser(RecordParser parser) {
        this.recordParser = parser;
        updateRecordLimit();
        this.recordParser.handler(buffer -> {
            ensureRemaining(buffer.length());
            getNextHandler().accept(buffer, this);
        });
    }

    private void ensureRemaining(long length) {
        if (closed) throw new IllegalStateException("TCP parser is closed");
        PayloadParserLimits.check(length, maxFrameLength);
        PayloadParserLimits.check(resultLength + length, maxFrameLength);
    }

    private void updateRecordLimit() {
        if (recordParser != null) {
            // Vert.x treats zero as unlimited. Full frames must complete before reading more.
            recordParser.maxRecordSize((int) Math.max(1L, maxFrameLength - resultLength));
        }
    }

    public PipePayloadParser complete() {
        directBytes = 0;
        CURRENT_PIPE.set(this, 0);
        Buffer completed = null;
        if (!this.result.isEmpty()) {
            completed = Buffer.buffer();
            for (Buffer buf : this.result) {
                completed.appendBuffer(buf);
            }
            this.result.clear();
        }
        resultLength = 0;
        if (recordParser != null) {
            firstInit.accept(recordParser);
            updateRecordLimit();
        }
        if (completed != null) sink.emitNext(completed, Reactors.emitFailureHandler());
        return this;

    }

    public PipePayloadParser result(Buffer buffer) {
        ensureRemaining(buffer.length());
        this.result.add(buffer);
        resultLength += buffer.length();
        updateRecordLimit();
        return this;
    }

    @Override
    public synchronized void handle(Buffer buffer) {
        if (closed) return;
        if (recordParser == null && directMapper == null) {
            log.error("record parser not init");
            return;
        }
        try {
            if (recordParser != null) {
                recordParser.handle(buffer);
                return;
            }
            directBytes += buffer.length();
            Buffer buf = directMapper.apply(buffer);
            if (null != buf) {
                PayloadParserLimits.check(buf.length(), maxFrameLength);
                directBytes = 0;
                sink.emitNext(buf, Reactors.emitFailureHandler());
            }
            PayloadParserLimits.check(directBytes, maxFrameLength);
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
    public synchronized void reset() {
        if (closed) return;
        this.result.clear();
        resultLength = 0;
        directBytes = 0;
        CURRENT_PIPE.set(this, 0);
        if (recordParser != null) {
            RecordParser replacement = RecordParser.newFixed(1);
            firstInit.accept(replacement);
            setParser(replacement);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        recordParser = null;
        directMapper = null;
        firstInit = null;
        pipe.clear();
        sink.tryEmitComplete();
        CURRENT_PIPE.set(this, 0);
        this.result.clear();
        resultLength = 0;
        directBytes = 0;
    }

}
