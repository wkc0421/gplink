package org.jetlinks.community.network.tcp.parser;

import org.jetlinks.community.ValueObject;

/** Limits apply to a single assembled frame, not to a TCP read containing several frames. */
public final class PayloadParserLimits {
    public static final int DEFAULT_MAX_FRAME_LENGTH = 1024 * 1024;
    public static final int MAX_FRAME_LENGTH = 16 * 1024 * 1024;

    private PayloadParserLimits() {
    }

    public static int maxFrameLength(ValueObject config) {
        long value = config.getString("maxFrameLength")
                           .map(Long::parseLong)
                           .orElse((long) DEFAULT_MAX_FRAME_LENGTH);
        if (value < 1 || value > MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("maxFrameLength must be between 1 and " + MAX_FRAME_LENGTH);
        }
        return (int) value;
    }

    public static void check(long length, int maximum) {
        if (length < 0 || length > maximum) {
            throw new IllegalArgumentException("TCP frame length " + length + " exceeds maxFrameLength " + maximum);
        }
    }
}
