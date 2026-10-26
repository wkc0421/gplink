package org.jetlinks.community.network.tcp.parser.strateies;

import io.vertx.core.buffer.Buffer;
import org.jetlinks.community.ValueObject;
import org.jetlinks.community.network.tcp.parser.LengthFieldPayloadParserBuilder;
import org.jetlinks.community.network.tcp.parser.PayloadParser;
import org.jetlinks.community.network.tcp.parser.PayloadParserLimits;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PayloadParserLimitsTest {
    @Test
    void defaultsAndConfigurationBounds() {
        assertEquals(1024 * 1024, PayloadParserLimits.maxFrameLength(ValueObject.of(Map.of())));
        for (Object invalid : List.of(0, -1, 16777217, Long.MAX_VALUE, "invalid")) {
            assertThrows(IllegalArgumentException.class,
                () -> PayloadParserLimits.maxFrameLength(ValueObject.of(Map.of("maxFrameLength", invalid))));
        }
        assertEquals(1, PayloadParserLimits.maxFrameLength(ValueObject.of(Map.of("maxFrameLength", 1))));
        assertEquals(16777216, PayloadParserLimits.maxFrameLength(ValueObject.of(Map.of("maxFrameLength", 16777216))));
    }

    @Test
    void delimiterLimitsPartialAndCompleteFramesButNotWholeTcpChunk() throws Exception {
        PayloadParser parser = delimited(4);
        List<String> frames = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean();
        parser.handlePayload().subscribe(buffer -> frames.add(buffer.toString()), ignored -> {}, () -> completed.set(true));
        parser.handle(Buffer.buffer("1234\n12\n123"));
        parser.handle(Buffer.buffer("4\n"));
        assertEquals(List.of("1234", "12", "1234"), frames);
        parser.handle(Buffer.buffer("1234"));
        assertThrows(IllegalStateException.class, () -> parser.handle(Buffer.buffer("5")));
        assertTrue(completed.get());
        assertNull(field(parser, "recordParser"));
        parser.reset();
        parser.handle(Buffer.buffer("1\n"));
        assertEquals(3, frames.size());

        PayloadParser complete = delimited(4);
        assertThrows(IllegalArgumentException.class, () -> complete.handle(Buffer.buffer("12345\n")));
        assertNull(field(complete, "recordParser"));
    }

    @Test
    void fixedFramesRespectBoundsAndCanBeSplitAndCoalesced() {
        FixLengthPayloadParserBuilder builder = new FixLengthPayloadParserBuilder();
        assertThrows(IllegalArgumentException.class, () -> builder.build(ValueObject.of(Map.of("size", 5, "maxFrameLength", 4))));
        assertThrows(IllegalArgumentException.class, () -> builder.build(ValueObject.of(Map.of("size", 0))));
        PayloadParser parser = builder.build(ValueObject.of(Map.of("size", 4, "maxFrameLength", 4)));
        List<String> frames = new ArrayList<>();
        parser.handlePayload().subscribe(buffer -> frames.add(buffer.toString()));
        parser.handle(Buffer.buffer("12"));
        parser.handle(Buffer.buffer("3412341234"));
        assertEquals(List.of("1234", "1234", "1234"), frames);
        parser.close();
    }

    @Test
    void lengthFieldsRejectOverflowBeforeIntConversionAndCountHeader() throws Exception {
        for (int size : List.of(4, 8)) {
            for (boolean little : List.of(false, true)) {
                PayloadParser parser = length(size, little, 16);
                Buffer header = Buffer.buffer();
                if (size == 4) {
                    if (little) header.appendIntLE(-1); else header.appendInt(-1);
                } else {
                    if (little) header.appendLongLE(0x100000001L); else header.appendLong(0x100000001L);
                }
                assertThrows(IllegalArgumentException.class, () -> parser.handle(header));
                assertNull(field(parser, "recordParser"));
                assertTrue(((List<?>) field(parser, "result")).isEmpty());
            }
        }
        PayloadParser negative = length(8, false, 16);
        assertThrows(IllegalArgumentException.class, () -> negative.handle(Buffer.buffer().appendLong(Long.MIN_VALUE)));
        PayloadParser tooLarge = length(1, false, 4);
        assertThrows(IllegalArgumentException.class, () -> tooLarge.handle(Buffer.buffer().appendByte((byte) 4)));
        PayloadParser parser = length(1, false, 4);
        List<Integer> sizes = new ArrayList<>();
        parser.handlePayload().subscribe(buffer -> sizes.add(buffer.length()));
        parser.handle(Buffer.buffer(new byte[]{3, 1}));
        parser.handle(Buffer.buffer(new byte[]{2, 3, 0, 3, 1, 2, 3}));
        assertEquals(List.of(4, 1, 4), sizes);
        parser.close();
    }

    @Test
    void pipeAccumulationAndMissingDirectCompletionAreBoundedAndReleased() throws Exception {
        PipePayloadParser pipe = new PipePayloadParser(4).fixed(2);
        pipe.handler((buffer, parser) -> parser.result(buffer));
        pipe.handle(Buffer.buffer("1234"));
        assertThrows(IllegalArgumentException.class, () -> pipe.handle(Buffer.buffer("56")));
        assertNull(field(pipe, "recordParser"));
        assertTrue(((List<?>) field(pipe, "result")).isEmpty());
        assertTrue(((List<?>) field(pipe, "pipe")).isEmpty());
        PipePayloadParser direct = new PipePayloadParser(4).direct(buffer -> null);
        direct.handle(Buffer.buffer("1234"));
        assertThrows(IllegalArgumentException.class, () -> direct.handle(Buffer.buffer("5")));
        assertNull(field(direct, "directMapper"));
    }

    @Test
    void scriptParserUsesConfiguredLimit() throws Exception {
        PayloadParser parser = new ScriptPayloadParserBuilder().build(ValueObject.of(Map.of(
            "maxFrameLength", 4, "lang", "javascript",
            "script", "parser.fixed(2).handler(function(buffer,p){p.result(buffer);});")));
        parser.handle(Buffer.buffer("1234"));
        assertThrows(RuntimeException.class, () -> parser.handle(Buffer.buffer("56")));
        assertNull(field(parser, "recordParser"));
        assertTrue(((List<?>) field(parser, "result")).isEmpty());
    }

    @Test
    void resetDiscardsIncompletePipeFrame() {
        PipePayloadParser parser = new PipePayloadParser(4).fixed(4);
        parser.handler((buffer, pipe) -> pipe.result(buffer).complete());
        List<String> frames = new ArrayList<>();
        parser.handlePayload().subscribe(buffer -> frames.add(buffer.toString()));
        parser.handle(Buffer.buffer("12"));
        parser.reset();
        parser.handle(Buffer.buffer("abcd"));
        assertEquals(List.of("abcd"), frames);
        parser.close();
    }

    private PayloadParser delimited(int maximum) {
        return new DelimitedPayloadParserBuilder().build(ValueObject.of(Map.of(
            "delimited", "\\n", "maxFrameLength", maximum)));
    }

    private PayloadParser length(int size, boolean little, int maximum) {
        return new LengthFieldPayloadParserBuilder().build(ValueObject.of(Map.of(
            "length", size, "little", little, "maxFrameLength", maximum)));
    }

    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
