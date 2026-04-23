package org.jetlinks.protocol.modbus.mapping;

/**
 * Byte/word order for multi-register values.
 * Modbus transmits each 16-bit register in big-endian (high byte first).
 * Multi-register numbers can be sent high-word-first (ABCD, default)
 * or low-word-first (CDAB). Some devices also swap bytes inside each
 * register (BADC / DCBA).
 */
public enum ByteOrder {
    ABCD, // big-endian, high word first (standard)
    CDAB, // low word first, byte order per register unchanged
    BADC, // high word first, bytes swapped inside register
    DCBA; // low word first, bytes swapped inside register

    public static ByteOrder parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return ABCD;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported byteOrder: " + raw);
        }
    }
}
