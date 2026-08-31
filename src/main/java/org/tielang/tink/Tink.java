package org.tielang.tink;

import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * tink data-flow node frame protocol (universal, language-agnostic).
 *
 * <p>Frame = {@code [len u32 BE][payload][crc u32 BE]}; {@code crc} =
 * CRC32-IEEE (0xEDB88320). Mirrors {@code std/tink.tie} (tie standard library)
 * and the Rust / C / Python / JS / C++ tink libraries; pure functions over
 * byte arrays, IO (stdin/stdout) left to the caller. Java 17+, no
 * dependencies.
 *
 * <pre>{@code
 * byte[] frame = Tink.frameEncode(new byte[]{1, 2, 3});
 * var got = Tink.frameNext(frame, 0);   // Optional<Frame>
 * assert got.isPresent();
 * }</pre>
 */
public final class Tink {

    private Tink() {}

    /**
     * Result of parsing a frame: the payload bytes (a copy) plus the position
     * right after the frame ({@code next = payload length + 8}).
     */
    public record Frame(byte[] payload, int next) {}

    /** CRC32-IEEE over a byte array (bit-loop, no table; matches zlib.crc32).
     * Check vector: {@code crc32("123456789") == 0xCBF43926}. */
    public static long crc32(byte[] data) {
        int crc = 0xFFFFFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int k = 0; k < 8; k++) {
                crc = (crc >>> 1) ^ ((crc & 1) != 0 ? 0xEDB88320 : 0);
            }
        }
        return Integer.toUnsignedLong(crc ^ 0xFFFFFFFF);
    }

    /** Encode a payload into a full frame: {@code [len u32 BE][payload][crc]}. */
    public static byte[] frameEncode(byte[] payload) {
        int n = payload.length;
        byte[] out = new byte[n + 8];
        out[0] = (byte) (n >>> 24);
        out[1] = (byte) (n >>> 16);
        out[2] = (byte) (n >>> 8);
        out[3] = (byte) n;
        System.arraycopy(payload, 0, out, 4, n);
        long c = crc32(payload);
        out[n + 4] = (byte) (c >>> 24);
        out[n + 5] = (byte) (c >>> 16);
        out[n + 6] = (byte) (c >>> 8);
        out[n + 7] = (byte) c;
        return out;
    }

    /** Parse one frame at {@code pos} (verifies CRC). Returns the {@link Frame}
     * with a copy of the payload and {@code next} = position after the frame;
     * {@link Optional#empty()} on out-of-bounds or CRC mismatch. */
    public static Optional<Frame> frameNext(byte[] bytes, int pos) {
        if (bytes.length < pos + 8) {
            return Optional.empty();
        }
        long n = readBe32(bytes, pos);
        long end = (long) pos + 8 + n;
        if (bytes.length < end) {
            return Optional.empty();
        }
        byte[] payload = Arrays.copyOfRange(bytes, pos + 4, pos + 4 + (int) n);
        long want = readBe32(bytes, (int) (end - 4));
        if (crc32(payload) != want) {
            return Optional.empty();
        }
        return Optional.of(new Frame(payload, (int) end));
    }

    /** Skip one frame at {@code pos} without copying or verifying (zero-copy).
     * Returns {@code next} = position after the frame, or
     * {@link OptionalLong#empty()} on out-of-bounds. */
    public static OptionalLong frameSkip(byte[] bytes, int pos) {
        if (bytes.length < pos + 8) {
            return OptionalLong.empty();
        }
        long n = readBe32(bytes, pos);
        long end = (long) pos + 8 + n;
        if (bytes.length < end) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(end);
    }

    private static long readBe32(byte[] b, int off) {
        return Integer.toUnsignedLong(((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF));
    }
}