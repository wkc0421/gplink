package org.jetlinks.protocol.modbus.mapping;

/**
 * Converts between register data bytes and numeric values, honoring byte-order
 * and dataType. Input payload length must equal {@link RegisterDataType#byteLength()}
 * (for BIT, the caller has already isolated a single bit byte).
 */
public final class RegisterCodec {

    private RegisterCodec() {
    }

    public static Object decode(RegisterMapping mapping, byte[] payload) {
        RegisterDataType type = mapping.getDataType();
        if (type == RegisterDataType.BIT) {
            return payload.length > 0 && (payload[0] & 0x01) != 0;
        }
        byte[] reordered = reorder(payload, mapping.getByteOrder());
        double raw;
        switch (type) {
            case INT16:
                raw = (short) ((reordered[0] & 0xFF) << 8 | (reordered[1] & 0xFF));
                break;
            case UINT16:
                raw = ((reordered[0] & 0xFF) << 8 | (reordered[1] & 0xFF)) & 0xFFFF;
                break;
            case INT32:
                raw = ((reordered[0] & 0xFF) << 24)
                        | ((reordered[1] & 0xFF) << 16)
                        | ((reordered[2] & 0xFF) << 8)
                        | (reordered[3] & 0xFF);
                break;
            case UINT32:
                raw = (((long) (reordered[0] & 0xFF) << 24)
                        | ((long) (reordered[1] & 0xFF) << 16)
                        | ((long) (reordered[2] & 0xFF) << 8)
                        | (reordered[3] & 0xFF)) & 0xFFFFFFFFL;
                break;
            case FLOAT32: {
                int bits = ((reordered[0] & 0xFF) << 24)
                        | ((reordered[1] & 0xFF) << 16)
                        | ((reordered[2] & 0xFF) << 8)
                        | (reordered[3] & 0xFF);
                raw = Float.intBitsToFloat(bits);
                break;
            }
            case INT64: {
                long v = 0L;
                for (int i = 0; i < 8; i++) {
                    v = (v << 8) | (reordered[i] & 0xFFL);
                }
                raw = v;
                break;
            }
            case FLOAT64: {
                long v = 0L;
                for (int i = 0; i < 8; i++) {
                    v = (v << 8) | (reordered[i] & 0xFFL);
                }
                raw = Double.longBitsToDouble(v);
                break;
            }
            default:
                throw new IllegalStateException("Unsupported type: " + type);
        }
        return raw * mapping.getScale() + mapping.getOffset();
    }

    public static byte[] encode(RegisterMapping mapping, Number value) {
        RegisterDataType type = mapping.getDataType();
        double scaled = (value.doubleValue() - mapping.getOffset()) / mapping.getScale();
        byte[] abcd;
        switch (type) {
            case INT16:
            case UINT16: {
                int v = (int) Math.round(scaled);
                abcd = new byte[]{(byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF)};
                break;
            }
            case INT32:
            case UINT32: {
                int v = (int) Math.round(scaled);
                abcd = new byte[]{
                        (byte) ((v >> 24) & 0xFF),
                        (byte) ((v >> 16) & 0xFF),
                        (byte) ((v >> 8) & 0xFF),
                        (byte) (v & 0xFF)};
                break;
            }
            case FLOAT32: {
                int v = Float.floatToRawIntBits((float) scaled);
                abcd = new byte[]{
                        (byte) ((v >> 24) & 0xFF),
                        (byte) ((v >> 16) & 0xFF),
                        (byte) ((v >> 8) & 0xFF),
                        (byte) (v & 0xFF)};
                break;
            }
            case INT64: {
                long v = Math.round(scaled);
                abcd = new byte[8];
                for (int i = 0; i < 8; i++) {
                    abcd[7 - i] = (byte) (v >> (i * 8));
                }
                break;
            }
            case FLOAT64: {
                long v = Double.doubleToRawLongBits(scaled);
                abcd = new byte[8];
                for (int i = 0; i < 8; i++) {
                    abcd[7 - i] = (byte) (v >> (i * 8));
                }
                break;
            }
            default:
                throw new IllegalStateException("Unsupported encode type: " + type);
        }
        return reorderInverse(abcd, mapping.getByteOrder());
    }

    private static byte[] reorder(byte[] src, ByteOrder order) {
        if (order == null || order == ByteOrder.ABCD || src.length < 4) {
            if (src.length == 2 && order == ByteOrder.BADC) {
                return new byte[]{src[1], src[0]};
            }
            return src;
        }
        byte[] out = new byte[src.length];
        for (int i = 0; i < src.length; i += 4) {
            byte a = safe(src, i);
            byte b = safe(src, i + 1);
            byte c = safe(src, i + 2);
            byte d = safe(src, i + 3);
            switch (order) {
                case CDAB:
                    out[i] = c;
                    out[i + 1] = d;
                    out[i + 2] = a;
                    out[i + 3] = b;
                    break;
                case BADC:
                    out[i] = b;
                    out[i + 1] = a;
                    out[i + 2] = d;
                    out[i + 3] = c;
                    break;
                case DCBA:
                    out[i] = d;
                    out[i + 1] = c;
                    out[i + 2] = b;
                    out[i + 3] = a;
                    break;
                default:
                    out[i] = a;
                    out[i + 1] = b;
                    out[i + 2] = c;
                    out[i + 3] = d;
            }
        }
        return out;
    }

    private static byte[] reorderInverse(byte[] abcd, ByteOrder order) {
        // ABCD/CDAB/BADC/DCBA are self-inverse at the 4-byte boundary.
        return reorder(abcd, order);
    }

    private static byte safe(byte[] src, int i) {
        return i < src.length ? src[i] : 0;
    }
}
