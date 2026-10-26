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
package org.jetlinks.community.configure.redis;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.utils.SerializeUtils;
import org.jetlinks.community.codec.Serializers;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.io.UTFDataFormatException;

@Slf4j
@AllArgsConstructor
public class ObjectRedisSerializer implements RedisSerializer<Object> {

    private static final SerializeUtils.Serializer OBJECT_SERIALIZER = SerializeUtils.lookup(Serializable.class);

    static final FastThreadLocal<ByteArrayOutputStream> STREAM_LOCAL = new FastThreadLocal<ByteArrayOutputStream>() {
        @Override
        protected ByteArrayOutputStream initialValue() {
            return new ByteArrayOutputStream(1024) {
                @Override
                public void close() {
                    reset();
                }
            };
        }
    };

    @Override
    @SneakyThrows
    public byte[] serialize(Object o) throws SerializationException {
        if (o == null) {
            return null;
        }
        try {
            try {
                return serialize(o, false);
            } catch (UTFDataFormatException e) {
                if (!(o instanceof Serializable)) {
                    throw e;
                }
                // The compact STRING encoding uses writeUTF (a 65535-byte limit).
                // The existing OBJECT encoding supports long/nested strings and
                // remains readable by SerializeUtils.readObject in older releases.
                return serialize(o, true);
            }
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
            throw e;
        }
    }

    private byte[] serialize(Object value, boolean useObjectEncoding) throws IOException {
        ByteArrayOutputStream arr = STREAM_LOCAL.get();
        try (ObjectOutput output = Serializers.getDefault().createOutput(arr)) {
            if (useObjectEncoding) {
                output.writeByte(OBJECT_SERIALIZER.getCode());
                OBJECT_SERIALIZER.serialize(value, output);
            } else {
                SerializeUtils.writeObject(value, output);
            }
            output.flush();
            return arr.toByteArray();
        } finally {
            // A failed write must not leave partial bytes for the next Redis value.
            arr.reset();
        }
    }

    @Override
    @SneakyThrows
    public Object deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null) {
            return null;
        }
        try (ObjectInput input = Serializers
            .getDefault()
            .createInput(new ByteArrayInputStream(bytes))) {
            return SerializeUtils.readObject(input);
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
            throw e;
        }
    }
}
