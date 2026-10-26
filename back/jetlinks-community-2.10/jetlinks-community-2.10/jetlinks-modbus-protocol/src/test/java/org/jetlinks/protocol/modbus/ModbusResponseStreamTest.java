package org.jetlinks.protocol.modbus;

import org.jetlinks.protocol.modbus.frame.ModbusCrc16;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class ModbusResponseStreamTest {
    @Test
    public void completeFrameAndPartialNextFrameAreBothPreserved() {
        ModbusResponseStream stream = new ModbusResponseStream();
        byte[] first = frame(1, 3, 2, 0, 42);
        byte[] second = frame(1, 4, 4, 0, 1, 0, 2);
        byte[] chunk = new byte[first.length + 3];
        System.arraycopy(first, 0, chunk, 0, first.length);
        System.arraycopy(second, 0, chunk, first.length, 3);
        ModbusResponseStream.Result result = stream.accept(chunk);
        assertEquals(1, result.frames.size());
        assertArrayEquals(first, result.frames.get(0));
        assertEquals(3, result.bufferedBytes);
        assertEquals(0, result.discardedBytes);
        result = stream.accept(Arrays.copyOfRange(second, 3, second.length));
        assertEquals(1, result.frames.size());
        assertArrayEquals(second, result.frames.get(0));
        assertEquals(0, result.bufferedBytes);
    }

    @Test
    public void writeAndExceptionResponseLengthsAreCorrect() {
        ModbusResponseStream stream = new ModbusResponseStream();
        for (int function : new int[]{5, 6, 15, 16}) {
            byte[] response = frame(1, function, 0, 1, 0, 2);
            assertEquals(8, response.length);
            ModbusResponseStream.Result result = stream.accept(response);
            assertEquals(1, result.frames.size());
            assertArrayEquals(response, result.frames.get(0));
        }
        byte[] exception = frame(1, 0x83, 2);
        ModbusResponseStream.Result result = stream.accept(exception);
        assertEquals(1, result.frames.size());
        assertArrayEquals(exception, result.frames.get(0));
        assertEquals(0, result.bufferedBytes);
    }

    @Test
    public void badCrcDoesNotPoisonTheFollowingValidFrame() {
        ModbusResponseStream stream = new ModbusResponseStream();
        byte[] corrupt = frame(1, 3, 4, 0, 1, 0, 2);
        corrupt[corrupt.length - 1] ^= 1;
        ModbusResponseStream.Result rejected = stream.accept(corrupt);
        assertTrue(rejected.frames.isEmpty());
        assertTrue(rejected.crcFailures > 0);
        byte[] valid = frame(1, 3, 2, 0, 42);
        ModbusResponseStream.Result recovered = stream.accept(valid);
        assertEquals(1, recovered.frames.size());
        assertArrayEquals(valid, recovered.frames.get(0));
        assertEquals(0, recovered.bufferedBytes);
    }

    @Test
    public void malformedLargeInputIsBoundedAndTheMaximumValidFrameStillDecodes() {
        ModbusResponseStream stream = new ModbusResponseStream();
        byte[] noise = new byte[1024 * 1024];
        Arrays.fill(noise, (byte) 0x7f);
        ModbusResponseStream.Result result = stream.accept(noise);
        assertTrue(result.frames.isEmpty());
        assertTrue(result.bufferedBytes <= ModbusResponseStream.MAX_ADU_LENGTH);
        assertTrue(stream.accept(new byte[]{1, 3, (byte) 255}).frames.isEmpty());
        byte[] body = new byte[253];
        body[0] = 1;
        body[1] = 3;
        body[2] = (byte) 250;
        byte[] valid = ModbusCrc16.appended(body);
        result = stream.accept(valid);
        assertEquals(255, valid.length);
        assertEquals(1, result.frames.size());
        assertArrayEquals(valid, result.frames.get(0));
        assertEquals(0, result.bufferedBytes);
    }

    @Test
    public void resetAndCloseReleasePartialFramesAndClosedStreamsCannotRevive() {
        ModbusResponseStream stream = new ModbusResponseStream();
        byte[] response = frame(1, 3, 4, 0, 1, 0, 2);
        assertTrue(stream.accept(Arrays.copyOf(response, 5)).frames.isEmpty());
        assertEquals(5, stream.bufferedBytes());
        stream.reset();
        assertEquals(0, stream.bufferedBytes());
        assertEquals(1, stream.accept(response).frames.size());
        stream.accept(Arrays.copyOf(response, 5));
        stream.close();
        assertEquals(0, stream.bufferedBytes());
        assertTrue(stream.accept(response).frames.isEmpty());
        assertEquals(0, stream.bufferedBytes());
    }

    @Test
    public void differentConnectionsCannotCombineTheirFragments() {
        ModbusResponseStream left = new ModbusResponseStream();
        ModbusResponseStream right = new ModbusResponseStream();
        byte[] response = frame(1, 3, 4, 0, 1, 0, 2);
        assertTrue(left.accept(Arrays.copyOf(response, 4)).frames.isEmpty());
        assertTrue(right.accept(Arrays.copyOfRange(response, 4, response.length)).frames.isEmpty());
        ModbusResponseStream.Result result = left.accept(Arrays.copyOfRange(response, 4, response.length));
        assertEquals(1, result.frames.size());
        assertArrayEquals(response, result.frames.get(0));
    }

    @Test
    public void aValidFrameInsidePayloadDoesNotSplitAnIncompleteOuterFrame() {
        ModbusResponseStream stream = new ModbusResponseStream();
        byte[] inner = frame(1, 0x83, 2);
        byte[] body = new byte[9];
        body[0] = 1;
        body[1] = 3;
        body[2] = 6;
        System.arraycopy(inner, 0, body, 3, inner.length);
        byte[] outer = ModbusCrc16.appended(body);
        assertTrue(stream.accept(Arrays.copyOf(outer, 8)).frames.isEmpty());
        ModbusResponseStream.Result result = stream.accept(Arrays.copyOfRange(outer, 8, outer.length));
        assertEquals(1, result.frames.size());
        assertArrayEquals(outer, result.frames.get(0));
    }

    private static byte[] frame(int... body) {
        byte[] bytes = new byte[body.length];
        for (int i = 0; i < body.length; i++) bytes[i] = (byte) body[i];
        return ModbusCrc16.appended(bytes);
    }
}
