package org.jetlinks.protocol.modbus;

import org.jetlinks.protocol.modbus.frame.ModbusCrc16;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A connection-owned RTU response buffer. TCP read boundaries do not delimit ADUs. */
final class ModbusResponseStream {
    static final int MAX_ADU_LENGTH = 256;
    private byte[] buffer = new byte[MAX_ADU_LENGTH];
    private int size;
    private boolean resynchronizing;
    private boolean closed;

    synchronized Result accept(byte[] bytes) {
        if (closed) return new Result(Collections.emptyList(), 0, 0, 0);
        List<byte[]> frames = new ArrayList<>();
        int discarded = 0;
        int crcFailures = 0;
        for (byte value : bytes) {
            if (size == buffer.length) {
                discard(1);
                discarded++;
                resynchronizing = true;
            }
            buffer[size++] = value;
            while (size >= 2) {
                int length = responseLength(0);
                if (length < 0) {
                    discard(1);
                    discarded++;
                    resynchronizing = true;
                    continue;
                }
                if (length == 0 || size < length) {
                    // After corrupt data, a false header must not indefinitely hide
                    // the next complete, CRC-valid response already in the buffer.
                    int recovered = resynchronizing ? findCompleteFrame() : -1;
                    if (recovered > 0) {
                        discard(recovered);
                        discarded += recovered;
                        continue;
                    }
                    break;
                }
                if (!ModbusCrc16.validate(buffer, 0, length)) {
                    discard(1);
                    discarded++;
                    crcFailures++;
                    resynchronizing = true;
                    continue;
                }
                frames.add(Arrays.copyOf(buffer, length));
                discard(length);
                resynchronizing = false;
            }
        }
        return new Result(frames, discarded, crcFailures, size);
    }

    synchronized void reset() {
        size = 0;
        resynchronizing = false;
    }

    synchronized void close() {
        closed = true;
        reset();
        buffer = new byte[0];
    }

    synchronized int bufferedBytes() {
        return size;
    }

    private void discard(int count) {
        size -= count;
        System.arraycopy(buffer, count, buffer, 0, size);
    }

    private int findCompleteFrame() {
        for (int offset = 1; offset <= size - 5; offset++) {
            int length = responseLength(offset);
            if (length > 0 && length <= size - offset
                    && ModbusCrc16.validate(buffer, offset, length)) {
                return offset;
            }
        }
        return -1;
    }

    /** -1 invalid header, 0 incomplete header, positive complete ADU length. */
    private int responseLength(int offset) {
        int remaining = size - offset;
        if (remaining < 2) return 0;
        int slave = buffer[offset] & 255;
        if (slave < 1 || slave > 247) return -1;
        int rawFunction = buffer[offset + 1] & 255;
        int function = rawFunction & 127;
        if (function < 1 || (function > 6 && function != 15 && function != 16)) return -1;
        if ((rawFunction & 128) != 0) return 5;
        if (function <= 4) {
            if (remaining < 3) return 0;
            int count = buffer[offset + 2] & 255;
            if (count < 1 || count > 250 || (function >= 3 && count % 2 != 0)) return -1;
            return 5 + count;
        }
        // FC05/06/15/16 responses echo address and value/quantity: always 8 bytes.
        return 8;
    }

    static final class Result {
        final List<byte[]> frames;
        final int discardedBytes;
        final int crcFailures;
        final int bufferedBytes;

        Result(List<byte[]> frames, int discardedBytes, int crcFailures, int bufferedBytes) {
            this.frames = frames;
            this.discardedBytes = discardedBytes;
            this.crcFailures = crcFailures;
            this.bufferedBytes = bufferedBytes;
        }
    }
}
