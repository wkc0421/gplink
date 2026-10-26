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
package org.jetlinks.community.network.tcp.parser;

import io.vertx.core.buffer.Buffer;
import lombok.SneakyThrows;
import org.jetlinks.community.ValueObject;
import org.jetlinks.community.network.tcp.parser.strateies.PipePayloadParser;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 长度字段粘拆包解析规则, 使用指定的字节数据来表示接下来的包长度.
 *
 * @author zhouhao
 * @since 1.0
 */
public class LengthFieldPayloadParserBuilder implements PayloadParserBuilderStrategy {
    @Override
    public PayloadParserType getType() {
        return PayloadParserType.LENGTH_FIELD;
    }

    @Override
    @SneakyThrows
    public Supplier<PayloadParser> buildLazy(ValueObject config) {
        //偏移量
        int offset = config.getInt("offset")
                           .orElse(0);

        //包长度
        int len = config.getInt("length")
                        .orElseGet(() -> config
                            .getInt("to")
                            .orElse(4) - offset);

        //是否为小端模式
        boolean le = config.getBoolean("little")
                           .orElse(false);

        int maximum = PayloadParserLimits.maxFrameLength(config);
        if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
        PayloadParserLimits.check((long) offset + len, maximum);
        int initLength = offset + len;

        Function<Buffer, Long> lengthParser;
        switch (len) {
            case 1:
                lengthParser = buffer -> (long) buffer.getUnsignedByte(offset);
                break;
            case 2:
                lengthParser =
                    le ? buffer -> (long) buffer.getUnsignedShortLE(offset)
                        : buffer -> (long) buffer.getUnsignedShort(offset);
                break;
            case 3:
                lengthParser =
                    le ? buffer -> (long) buffer.getUnsignedMediumLE(offset)
                        : buffer -> (long) buffer.getUnsignedMedium(offset);
                break;
            case 4:
                lengthParser =
                    le ? buffer -> buffer.getUnsignedIntLE(offset)
                        : buffer -> buffer.getUnsignedInt(offset);
                break;
            case 8:
                lengthParser =
                    le ? buffer -> buffer.getLongLE(offset)
                        : buffer -> buffer.getLong(offset);
                break;
            default:
                throw new IllegalArgumentException("illegal length:" + len);
        }


        return () -> new PipePayloadParser(maximum)
            //先读取初始长度
            .fixed(initLength)
            .handler((buffer, parser) -> {
                //获取长度字段，然后读取接下来的长度
                long next = lengthParser.apply(buffer);
                PayloadParserLimits.check(next, maximum);
                PayloadParserLimits.check((long) initLength + next, maximum);
                parser.result(buffer)
                      .fixed((int) next);
            })
            .handler((buffer, parser) -> parser
                .result(buffer)
                .complete());
    }
}
